//! On-disk state: config, TLS identity, paired devices.
//!
//! Everything lives in one directory (`%APPDATA%\EasyRemote` on Windows, `~/.config/EasyRemote`
//! on Linux, overridable with `EASYREMOTE_DIR`). Functions take the directory explicitly so tests
//! can use a temp dir.

use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use std::{fs, io};

pub const DEFAULT_PORT: u16 = 47821;

static DEVICES_LOCK: Mutex<()> = Mutex::new(());

pub fn dir() -> PathBuf {
    if let Ok(d) = std::env::var("EASYREMOTE_DIR") {
        if !d.is_empty() {
            return PathBuf::from(d);
        }
    }
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("EasyRemote")
}

pub fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn io_err<E: std::fmt::Display>(e: E) -> io::Error {
    io::Error::new(io::ErrorKind::Other, e.to_string())
}

/// Write a file readable only by the current user (on Unix; Windows profile dirs are already private).
pub fn write_private(path: &Path, data: &[u8]) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::io::Write;
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        let mut f = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(path)?;
        f.write_all(data)?;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
        Ok(())
    }
    #[cfg(not(unix))]
    {
        fs::write(path, data)
    }
}

// ---------------------------------------------------------------- config

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Config {
    /// Name shown on the phone.
    pub name: String,
    pub port: u16,
}

pub fn load_config(dir: &Path) -> io::Result<Config> {
    fs::create_dir_all(dir)?;
    let path = dir.join("config.json");
    if let Ok(text) = fs::read_to_string(&path) {
        if let Ok(cfg) = serde_json::from_str::<Config>(&text) {
            return Ok(cfg);
        }
    }
    let name = gethostname::gethostname().to_string_lossy().trim().to_string();
    let cfg = Config {
        name: if name.is_empty() { "My PC".to_string() } else { name },
        port: DEFAULT_PORT,
    };
    fs::write(&path, serde_json::to_string_pretty(&cfg).map_err(io_err)?)?;
    Ok(cfg)
}

// ---------------------------------------------------------------- TLS identity

pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    /// Lowercase hex SHA-256 of the certificate DER.
    pub fingerprint: String,
}

pub fn fingerprint(cert_der: &[u8]) -> String {
    hex::encode(Sha256::digest(cert_der))
}

pub fn load_identity(dir: &Path) -> io::Result<Identity> {
    fs::create_dir_all(dir)?;
    let cert_path = dir.join("cert.der");
    let key_path = dir.join("key.der");
    if let (Ok(cert_der), Ok(key_der)) = (fs::read(&cert_path), fs::read(&key_path)) {
        let fingerprint = fingerprint(&cert_der);
        return Ok(Identity { cert_der, key_der, fingerprint });
    }
    let ck = rcgen::generate_simple_self_signed(vec!["easyremote.local".to_string()]).map_err(io_err)?;
    let cert_der = ck.cert.der().to_vec();
    let key_der = ck.signing_key.serialize_der();
    fs::write(&cert_path, &cert_der)?;
    write_private(&key_path, &key_der)?;
    let fingerprint = fingerprint(&cert_der);
    Ok(Identity { cert_der, key_der, fingerprint })
}

// ---------------------------------------------------------------- paired devices

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Device {
    pub id: String,
    pub name: String,
    /// Hex SHA-256 of the device token. The token itself is only stored on the phone.
    pub token_hash: String,
    pub added: u64,
}

fn devices_path(dir: &Path) -> PathBuf {
    dir.join("devices.json")
}

pub fn load_devices(dir: &Path) -> Vec<Device> {
    fs::read_to_string(devices_path(dir))
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn save_devices(dir: &Path, devices: &[Device]) -> io::Result<()> {
    let text = serde_json::to_string_pretty(devices).map_err(io_err)?;
    write_private(&devices_path(dir), text.as_bytes())
}

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    hex::encode(buf)
}

pub fn random_nonce() -> String {
    random_hex(16)
}

fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// Constant-time comparison.
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

fn clean_name(name: &str) -> String {
    let n: String = name.chars().filter(|c| !c.is_control()).take(48).collect();
    let n = n.trim().to_string();
    if n.is_empty() {
        "Phone".to_string()
    } else {
        n
    }
}

/// Register a new phone. Returns `(id, token)`; the token is shown to the phone exactly once.
pub fn add_device(dir: &Path, name: &str) -> io::Result<(String, String)> {
    let _g = DEVICES_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut devices = load_devices(dir);
    let id = random_hex(8);
    let token = random_hex(32);
    devices.push(Device {
        id: id.clone(),
        name: clean_name(name),
        token_hash: hash_token(&token),
        added: now_secs(),
    });
    save_devices(dir, &devices)?;
    Ok((id, token))
}

pub fn verify_device(dir: &Path, id: &str, token: &str) -> Option<Device> {
    let _g = DEVICES_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let want = hash_token(token);
    load_devices(dir)
        .into_iter()
        .find(|d| d.id == id && ct_eq(d.token_hash.as_bytes(), want.as_bytes()))
}

/// Remove one device by id (or by name), or all with `"all"`. Returns how many were removed.
pub fn revoke(dir: &Path, which: &str) -> io::Result<usize> {
    let _g = DEVICES_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let devices = load_devices(dir);
    let before = devices.len();
    let kept: Vec<Device> = devices
        .into_iter()
        .filter(|d| !(which == "all" || d.id == which || d.name == which))
        .collect();
    save_devices(dir, &kept)?;
    Ok(before - kept.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("easyremote-test-{}-{}", name, random_hex(4)));
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn device_lifecycle() {
        let d = tmp("dev");
        let (id, token) = add_device(&d, "Pixel 8").unwrap();
        assert!(verify_device(&d, &id, &token).is_some());
        assert!(verify_device(&d, &id, "nope").is_none());
        assert!(verify_device(&d, "other", &token).is_none());
        // the token must not be stored in clear text
        assert!(!fs::read_to_string(devices_path(&d)).unwrap().contains(&token));
        assert_eq!(revoke(&d, &id).unwrap(), 1);
        assert!(verify_device(&d, &id, &token).is_none());
    }

    #[test]
    fn identity_is_stable() {
        let d = tmp("id");
        let a = load_identity(&d).unwrap();
        let b = load_identity(&d).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.fingerprint.len(), 64);
    }
}
