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
pub mod input;
pub mod pairing;
pub mod proto;
pub mod server;
