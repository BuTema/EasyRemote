//! `easyremote update`: downloads the newest build from the GitHub "Latest build" release and installs it.
//!
//! Downloads go through `curl` (on every supported system: Linux, Windows 10+; inside a Flatpak it runs on the
//! host), so the agent itself carries no HTTP client. What "install" means depends on how EasyRemote was
//! installed: a Flatpak is reinstalled from the new bundle, a `.deb` is installed with `dpkg`, and a plain
//! download (tar.gz / zip, the Windows installer) gets its two programs replaced in place. The running agent
//! is restarted afterwards.

use crate::hostenv::{host_command, in_flatpak};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

const API: &str = "https://api.github.com/repos/BuTema/EasyRemote/releases/tags/latest";

#[derive(Debug, Deserialize)]
pub struct Asset {
    pub name: String,
    pub browser_download_url: String,
    #[serde(default)]
    pub digest: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Release {
    #[serde(default)]
    pub body: String,
    pub assets: Vec<Asset>,
}

impl Release {
    /// The short commit the release was built from: the release notes start "Automatic build of <sha> (run N)."
    pub fn build(&self) -> Option<String> {
        let rest = self.body.strip_prefix("Automatic build of ")?;
        let sha: String = rest.chars().take_while(|c| c.is_ascii_hexdigit()).collect();
        (sha.len() >= 7).then(|| sha[..7].to_string())
    }
}

#[derive(Debug, PartialEq, Clone, Copy)]
pub enum Kind {
    Flatpak,
    Deb,
    Portable,
}

impl Kind {
    fn detect(exe: &Path) -> Kind {
        if in_flatpak() {
            Kind::Flatpak
        } else if cfg!(target_os = "linux") && exe.starts_with("/usr/") {
            Kind::Deb
        } else {
            Kind::Portable
        }
    }
}

pub fn pick_asset(release: &Release, kind: Kind) -> Option<&Asset> {
    release.assets.iter().find(|a| match kind {
        Kind::Flatpak => a.name == "easyremote.flatpak",
        Kind::Deb => a.name.ends_with(".deb") && a.name.contains("amd64"),
        Kind::Portable if cfg!(windows) => a.name == "easyremote-windows-x86_64.zip",
        Kind::Portable => a.name == "easyremote-linux-x86_64.tar.gz",
    })
}

fn curl(args: &[&str]) -> Command {
    let mut c = host_command(if cfg!(windows) { "curl.exe" } else { "curl" });
    c.args(["-fsSL", "--retry", "2", "--connect-timeout", "15"]).args(args);
    c
}

fn fetch_release() -> Result<Release, String> {
    let out = curl(&["-H", "Accept: application/vnd.github+json", API])
        .stderr(Stdio::inherit())
        .output()
        .map_err(|e| format!("cannot run curl ({e}); install curl or download the new version from the GitHub Releases page"))?;
    if !out.status.success() {
        return Err("could not reach GitHub (no internet, or the rate limit was hit; try again later)".into());
    }
    serde_json::from_slice(&out.stdout).map_err(|e| format!("unexpected answer from GitHub: {e}"))
}

fn sha256_file(path: &Path) -> Result<String, String> {
    let data = std::fs::read(path).map_err(|e| e.to_string())?;
    Ok(hex::encode(Sha256::digest(&data)))
}

fn download(asset: &Asset, to: &Path) -> Result<(), String> {
    println!("Downloading {} ...", asset.name);
    let status = curl(&["-o", &to.to_string_lossy(), &asset.browser_download_url])
        .status()
        .map_err(|e| format!("cannot run curl: {e}"))?;
    if !status.success() {
        return Err("the download failed".into());
    }
    if let Some(want) = asset.digest.as_deref().and_then(|d| d.strip_prefix("sha256:")) {
        let got = sha256_file(to)?;
        if !got.eq_ignore_ascii_case(want) {
            return Err("the downloaded file does not match the checksum GitHub published; not installing it".into());
        }
    }
    Ok(())
}

fn find_file(dir: &Path, name: &str, depth: u32) -> Option<PathBuf> {
    for entry in std::fs::read_dir(dir).ok()?.flatten() {
        let p = entry.path();
        if p.is_file() && p.file_name().is_some_and(|n| n == name) {
            return Some(p);
        }
        if depth > 0 && p.is_dir() {
            if let Some(f) = find_file(&p, name, depth - 1) {
                return Some(f);
            }
        }
    }
    None
}

/// Puts `new` in place of `target`. The old file is moved aside first: that works for a program that is
/// running (Windows refuses to overwrite it, Linux would fail with "text file busy").
fn replace_file(new: &Path, target: &Path) -> Result<(), String> {
    let old = target.with_extension(if cfg!(windows) { "exe.old" } else { "old" });
    let _ = std::fs::remove_file(&old);
    if target.exists() {
        std::fs::rename(target, &old).map_err(|e| format!("cannot replace {}: {e}", target.display()))?;
    }
    if let Err(e) = std::fs::copy(new, target) {
        let _ = std::fs::rename(&old, target); // put the old one back
        return Err(format!("cannot write {}: {e}", target.display()));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(target, std::fs::Permissions::from_mode(0o755));
    }
    Ok(())
}

fn run_checked(cmd: &mut Command, what: &str) -> Result<(), String> {
    match cmd.status() {
        Ok(s) if s.success() => Ok(()),
        Ok(_) => Err(format!("{what} failed")),
        Err(e) => Err(format!("cannot run {what}: {e}")),
    }
}

fn install_flatpak(asset: &Asset) -> Result<(), String> {
    // everything on the host: the sandbox cannot see the host's temporary files
    let script = r#"set -e
f="$(mktemp --suffix=.flatpak)"
trap 'rm -f "$f"' EXIT
curl -fsSL --retry 2 -o "$f" "$1"
flatpak install --user -y "$f"
flatpak run "$2" autostart on"#;
    println!("Installing {} with Flatpak ...", asset.name);
    run_checked(
        host_command("sh").args(["-c", script, "sh", &asset.browser_download_url, &crate::hostenv::flatpak_id()]),
        "the Flatpak install",
    )
}

fn install_deb(asset: &Asset, exe: &Path) -> Result<(), String> {
    let file = std::env::temp_dir().join(&asset.name);
    download(asset, &file)?;
    println!("Installing with dpkg (asks for your password) ...");
    // dpkg, not apt: every build has the same package version, and apt would see nothing to do
    let root = Command::new("id").arg("-u").output().ok().is_some_and(|o| String::from_utf8_lossy(&o.stdout).trim() == "0");
    let mut cmd = if root { Command::new("dpkg") } else { Command::new("sudo") };
    if !root {
        cmd.arg("dpkg");
    }
    let r = run_checked(cmd.arg("-i").arg(&file), "dpkg");
    let _ = std::fs::remove_file(&file);
    r?;
    restart_agent(exe)
}

fn install_portable(asset: &Asset, exe: &Path) -> Result<(), String> {
    let dir = exe.parent().ok_or("cannot find the install folder")?;
    let work = std::env::temp_dir().join(format!("easyremote-update-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).map_err(|e| e.to_string())?;
    let result: Result<(), String> = (|| {
        let archive = work.join(&asset.name);
        download(asset, &archive)?;
        let unpacked = work.join("unpacked");
        std::fs::create_dir_all(&unpacked).map_err(|e| e.to_string())?;
        // tar unpacks .tar.gz everywhere and also .zip on Windows 10+ (bsdtar)
        run_checked(
            Command::new("tar").arg("-xf").arg(&archive).arg("-C").arg(&unpacked),
            "unpacking the download",
        )?;
        let suffix = if cfg!(windows) { ".exe" } else { "" };
        for name in ["easyremote", "easyremoted"] {
            let file = format!("{name}{suffix}");
            let new = find_file(&unpacked, &file, 2).ok_or(format!("{file} is missing from the download"))?;
            replace_file(&new, &dir.join(&file))?;
        }
        Ok(())
    })();
    let _ = std::fs::remove_dir_all(&work);
    result?;
    restart_agent(exe)
}

/// The new `easyremote autostart on` stops the old agent and starts the new one.
fn restart_agent(exe: &Path) -> Result<(), String> {
    println!("Restarting the agent ...");
    run_checked(Command::new(exe).args(["autostart", "on"]), "restarting the agent")
}

/// `easyremote update [--check] [--force]`
pub fn run(args: &[String]) -> Result<(), String> {
    let check_only = args.iter().any(|a| a == "--check");
    let force = args.iter().any(|a| a == "--force");
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let kind = Kind::detect(&exe);
    // leftovers of the previous update (the replaced programs were moved aside, not deleted)
    if let Some(dir) = exe.parent() {
        for name in ["easyremote", "easyremoted"] {
            let _ = std::fs::remove_file(dir.join(name).with_extension(if cfg!(windows) { "exe.old" } else { "old" }));
        }
    }

    println!("Checking GitHub for a newer build (you have {}) ...", crate::build_id());
    let release = fetch_release()?;
    let latest = release.build().ok_or("the release has no build number")?;
    let current = crate::build_id();
    if latest == current && !force {
        println!("Already up to date (build {current}).");
        return Ok(());
    }
    println!("Newest build: {latest}.");
    if check_only {
        println!("Run `easyremote update` to install it.");
        return Ok(());
    }
    let asset = pick_asset(&release, kind).ok_or("this release has no file for this system")?;
    match kind {
        Kind::Flatpak => install_flatpak(asset)?,
        Kind::Deb => install_deb(asset, &exe)?,
        Kind::Portable => install_portable(asset, &exe)?,
    }
    println!("Updated to build {latest}. The phone reconnects by itself.");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release() -> Release {
        serde_json::from_str(
            r#"{"body":"Automatic build of 5bf4d82aaaabbbbccccddddeeeeffff00001111 (run 29).\nmore","assets":[
              {"name":"EasyRemote.apk","browser_download_url":"https://x/apk"},
              {"name":"easyremote_0.1.0_amd64.deb","browser_download_url":"https://x/deb","digest":"sha256:abc"},
              {"name":"easyremote.flatpak","browser_download_url":"https://x/fp"},
              {"name":"easyremote-linux-x86_64.tar.gz","browser_download_url":"https://x/tgz"},
              {"name":"easyremote-windows-x86_64.zip","browser_download_url":"https://x/zip"}]}"#,
        )
        .unwrap()
    }

    #[test]
    fn reads_build_and_picks_assets() {
        let r = release();
        assert_eq!(r.build().as_deref(), Some("5bf4d82"));
        assert_eq!(pick_asset(&r, Kind::Flatpak).unwrap().name, "easyremote.flatpak");
        assert_eq!(pick_asset(&r, Kind::Deb).unwrap().name, "easyremote_0.1.0_amd64.deb");
        let portable = pick_asset(&r, Kind::Portable).unwrap();
        assert!(portable.name == "easyremote-linux-x86_64.tar.gz" || portable.name == "easyremote-windows-x86_64.zip");
    }

    #[test]
    fn replaces_a_file_and_keeps_the_old_one_aside() {
        let d = std::env::temp_dir().join(format!("er-upd-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let (new, target) = (d.join("new"), d.join("easyremote"));
        std::fs::write(&new, "new").unwrap();
        std::fs::write(&target, "old").unwrap();
        replace_file(&new, &target).unwrap();
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "new");
        let _ = std::fs::remove_dir_all(&d);
    }
}
