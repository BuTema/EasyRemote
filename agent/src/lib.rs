//! EasyRemote PC agent library.
//!
//! A tiny background service: TLS + WebSocket server on the LAN, mDNS advertisement,
//! one-time pairing, and an input backend that injects mouse / keyboard / media events.

#[macro_use]
pub mod log;
pub mod autostart;
pub mod config;
pub mod daemon;
pub mod discovery;
pub mod hostenv;
pub mod input;
pub mod pairing;
pub mod proto;
pub mod selfupdate;
pub mod server;
#[cfg(windows)]
pub mod tray;

/// Which build this is: the short git commit CI built it from, or "dev" for a local build.
pub fn build_id() -> &'static str {
    match option_env!("EASYREMOTE_BUILD") {
        Some(b) if !b.is_empty() => &b[..b.len().min(7)],
        _ => "dev",
    }
}
