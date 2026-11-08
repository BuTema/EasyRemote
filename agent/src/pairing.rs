//! One-time pairing: secret generation, proof verification, QR code.
//!
//! `easyremote pair` writes `pairing.json`; the running agent reads it when a phone sends a
//! `pair` message. The file is removed after one success, after expiry, or after 5 wrong proofs.

use hmac::{Hmac, KeyInit, Mac};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::path::Path;
use std::{fs, io};

use crate::config::{ct_eq, now_secs, write_private};

pub const WINDOW_SECS: u64 = 300;
const MAX_FAILURES: u32 = 5;
const ALPHABET: &[u8; 32] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

#[derive(Serialize, Deserialize)]
struct PairingFile {
    secret: String,
    expires: u64,
    failures: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PairError {
    NoWindow,
    Failed,
}

/// RFC 4648 Base32, no padding.
pub fn base32(data: &[u8]) -> String {
    let mut out = String::new();
    let mut buffer: u32 = 0;
    let mut bits = 0;
    for &b in data {
        buffer = (buffer << 8) | b as u32;
        bits += 8;
        while bits >= 5 {
            out.push(ALPHABET[((buffer >> (bits - 5)) & 31) as usize] as char);
            bits -= 5;
        }
    }
    if bits > 0 {
        out.push(ALPHABET[((buffer << (5 - bits)) & 31) as usize] as char);
    }
    out
}

/// Upper-case, drop separators, and map look-alike digits so hand-typed codes work.
pub fn normalize(secret: &str) -> String {
    secret
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| match c.to_ascii_uppercase() {
            '0' => 'O',
            '1' => 'I',
            '8' => 'B',
            other => other,
        })
        .collect()
}

/// `ABCD-EFGH-IJKL-MNOP`
pub fn group(secret: &str) -> String {
    secret
        .as_bytes()
        .chunks(4)
        .map(|c| String::from_utf8_lossy(c).to_string())
        .collect::<Vec<_>>()
        .join("-")
}

pub fn proof(secret: &str, nonce: &str, fingerprint: &str) -> String {
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(normalize(secret).as_bytes())
        .expect("HMAC accepts any key length");
    mac.update(format!("pair|{}|{}", nonce, fingerprint).as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

fn path(dir: &Path) -> std::path::PathBuf {
    dir.join("pairing.json")
}

/// Open a pairing window. Returns the normalised secret.
pub fn start(dir: &Path) -> io::Result<String> {
    let mut raw = [0u8; 10];
    rand::thread_rng().fill_bytes(&mut raw);
    let secret = base32(&raw);
    let file = PairingFile {
        secret: secret.clone(),
        expires: now_secs() + WINDOW_SECS,
        failures: 0,
    };
    let text = serde_json::to_string(&file).map_err(|e| io::Error::new(io::ErrorKind::Other, e))?;
    write_private(&path(dir), text.as_bytes())?;
    Ok(secret)
}

pub fn is_open(dir: &Path) -> bool {
    load(dir).is_some()
}

fn load(dir: &Path) -> Option<PairingFile> {
    let text = fs::read_to_string(path(dir)).ok()?;
    let f: PairingFile = serde_json::from_str(&text).ok()?;
    if now_secs() > f.expires || f.failures >= MAX_FAILURES {
        let _ = fs::remove_file(path(dir));
        return None;
    }
    Some(f)
}

/// Check a phone's proof. On success the window is consumed.
pub fn verify(dir: &Path, nonce: &str, fingerprint: &str, client_proof: &str) -> Result<(), PairError> {
    let mut f = load(dir).ok_or(PairError::NoWindow)?;
    let expected = proof(&f.secret, nonce, fingerprint);
    if ct_eq(expected.as_bytes(), client_proof.to_ascii_lowercase().as_bytes()) {
        let _ = fs::remove_file(path(dir));
        Ok(())
    } else {
        f.failures += 1;
        if f.failures >= MAX_FAILURES {
            let _ = fs::remove_file(path(dir));
        } else if let Ok(text) = serde_json::to_string(&f) {
            let _ = write_private(&path(dir), text.as_bytes());
        }
        Err(PairError::Failed)
    }
}

fn pct(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.' || b == b'~' {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

/// Content of the QR code.
pub fn uri(host: &str, port: u16, fingerprint: &str, secret: &str, name: &str) -> String {
    format!(
        "easyremote://pair?h={}&p={}&fp={}&s={}&n={}",
        pct(host),
        port,
        fingerprint,
        secret,
        pct(name)
    )
}

/// QR code rendered with Unicode half-blocks for a terminal.
pub fn qr(text: &str) -> String {
    use qrcode::render::unicode;
    match qrcode::QrCode::new(text.as_bytes()) {
        Ok(code) => code
            .render::<unicode::Dense1x2>()
            .quiet_zone(true)
            .build(),
        Err(_) => String::from("(could not render QR code)"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base32_known_vectors() {
        // RFC 4648 test vectors (without padding)
        assert_eq!(base32(b"f"), "MY");
        assert_eq!(base32(b"fo"), "MZXQ");
        assert_eq!(base32(b"foobar"), "MZXW6YTBOI");
        assert_eq!(base32(&[0u8; 10]).len(), 16);
    }

    #[test]
    fn normalize_and_group() {
        assert_eq!(normalize("abcd-0123 8abc"), "ABCDOI23BABC");
        assert_eq!(group("ABCDEFGHIJKLMNOP"), "ABCD-EFGH-IJKL-MNOP");
    }

    #[test]
    fn proof_depends_on_everything() {
        let a = proof("ABCD-EFGH", "n1", "fp");
        assert_eq!(a.len(), 64);
        assert_eq!(a, proof("abcdefgh", "n1", "fp"));
        assert_ne!(a, proof("ABCDEFGH", "n2", "fp"));
        assert_ne!(a, proof("ABCDEFGH", "n1", "other-fp"));
        assert_ne!(a, proof("ABCDEFGI", "n1", "fp"));
    }

    #[test]
    fn uri_encoding() {
        let u = uri("192.168.1.5", 47821, "ab", "SECRET", "My PC é");
        assert_eq!(u, "easyremote://pair?h=192.168.1.5&p=47821&fp=ab&s=SECRET&n=My%20PC%20%C3%A9");
    }
}
