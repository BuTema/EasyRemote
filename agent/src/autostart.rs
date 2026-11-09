//! Start-with-login registration.
//!
//! * Windows: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` (no admin rights needed) via `reg.exe`.
//! * Linux: an XDG autostart entry in `~/.config/autostart` (runs inside the desktop session).

use std::path::PathBuf;
use std::process::Command;

fn daemon_path() -> Result<PathBuf, String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let dir = exe.parent().ok_or("cannot find the install directory")?;
    let name = if cfg!(windows) { "easyremoted.exe" } else { "easyremoted" };
    let p = dir.join(name);
    if !p.exists() {
        return Err(format!("{} not found next to the easyremote command", p.display()));
    }
    Ok(p)
}

#[cfg(windows)]
pub fn enable() -> Result<String, String> {
    let daemon = daemon_path()?;
    use std::os::windows::process::CommandExt;
    // raw_arg: reg.exe does not use the usual C-runtime quote escaping, so the value must be passed
    // exactly as `"C:\path\easyremoted.exe"` (a plain quoted path).
    let status = Command::new("reg")
        .args(["add", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run", "/v", "EasyRemote", "/t", "REG_SZ", "/d"])
        .raw_arg(format!("\"{}\"", daemon.display()))
        .arg("/f")
        .output()
        .map_err(|e| e.to_string())?;
    if !status.status.success() {
        return Err("could not write the Run registry key".into());
    }
    Ok("EasyRemote will start automatically when you sign in to Windows.".into())
}

#[cfg(windows)]
pub fn disable() -> Result<String, String> {
    let _ = Command::new("reg")
        .args(["delete", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run", "/v", "EasyRemote", "/f"])
        .output();
    let _ = Command::new("taskkill").args(["/IM", "easyremoted.exe", "/F"]).output();
    Ok("Autostart removed and the agent stopped.".into())
}

#[cfg(target_os = "linux")]
fn desktop_path() -> PathBuf {
    // Inside a Flatpak, XDG_CONFIG_HOME points at the app's private directory. The real login
    // autostart folder is reached through the `xdg-config/autostart` permission in the manifest.
    let base = if crate::hostenv::in_flatpak() {
        dirs::home_dir().map(|h| h.join(".config")).unwrap_or_else(|| PathBuf::from("."))
    } else {
        dirs::config_dir().unwrap_or_else(|| PathBuf::from("."))
    };
    base.join("autostart/easyremote.desktop")
}

/// What the login entry runs. Flatpak: start the daemon through `flatpak run` (on the host).
#[cfg(target_os = "linux")]
fn exec_line(daemon: &std::path::Path) -> String {
    if crate::hostenv::in_flatpak() {
        format!("flatpak run --command=easyremoted {}", crate::hostenv::flatpak_id())
    } else {
        format!("\"{}\"", daemon.display())
    }
}

/// Linux uses an XDG autostart entry (`~/.config/autostart`). Unlike a systemd user service it is run
/// *inside* the desktop session, so the agent gets DISPLAY / WAYLAND_DISPLAY and the clipboard tools
/// work. Supported by Cinnamon (Linux Mint), GNOME, KDE, XFCE, MATE and others.
#[cfg(target_os = "linux")]
pub fn enable() -> Result<String, String> {
    let daemon = daemon_path()?;
    let desktop = desktop_path();
    std::fs::create_dir_all(desktop.parent().unwrap()).map_err(|e| e.to_string())?;
    let text = format!(
        "[Desktop Entry]\nType=Application\nName=EasyRemote\nComment=Control this PC from your phone\n\
         Exec={}\nTerminal=false\nNoDisplay=true\nX-GNOME-Autostart-enabled=true\n",
        exec_line(&daemon)
    );
    std::fs::write(&desktop, text).map_err(|e| e.to_string())?;

    // Start it right now, detached from this terminal. An agent that is already running is stopped
    // first, so that after an upgrade the new version really runs (otherwise the new one would exit
    // at once because the port is taken, and the old code would carry on). Inside a Flatpak the
    // running agent cannot be signalled from here; it restarts itself on the next phone connection.
    if !crate::hostenv::in_flatpak() {
        let _ = Command::new("pkill").args(["-x", "easyremoted"]).output();
        std::thread::sleep(std::time::Duration::from_millis(400));
    }
    let started = Command::new("setsid")
        .arg("-f")
        .arg(&daemon)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
        || Command::new(&daemon)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .is_ok();
    Ok(if started {
        "EasyRemote is running now and will start every time you log in.".to_string()
    } else {
        format!("Added {} (starts at your next login). Could not start it now; run: easyremoted &", desktop.display())
    })
}

#[cfg(target_os = "linux")]
pub fn disable() -> Result<String, String> {
    let _ = std::fs::remove_file(desktop_path());
    if crate::hostenv::in_flatpak() {
        // Each Flatpak run is its own process namespace, so the running agent cannot be signalled from here.
        return Ok(format!(
            "Autostart removed. To stop the running agent now, run: flatpak kill {}",
            crate::hostenv::flatpak_id()
        ));
    }
    let _ = Command::new("pkill").args(["-x", "easyremoted"]).output();
    Ok("Autostart removed and the agent stopped.".into())
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn enable() -> Result<String, String> {
    Err("autostart is only implemented for Windows and Linux".into())
}

#[cfg(not(any(windows, target_os = "linux")))]
pub fn disable() -> Result<String, String> {
    Err("autostart is only implemented for Windows and Linux".into())
}
