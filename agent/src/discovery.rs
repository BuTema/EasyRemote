//! LAN discovery: advertise `_easyremote._tcp` over mDNS and work out the PC's LAN address.

use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::net::{IpAddr, UdpSocket};

pub const SERVICE_TYPE: &str = "_easyremote._tcp.local.";

/// Primary LAN address. Connecting a UDP socket sends no packets; it only asks the OS which
/// interface would be used to reach the internet / default gateway.
pub fn local_ip() -> Option<IpAddr> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    let ip = sock.local_addr().ok()?.ip();
    if ip.is_unspecified() || ip.is_loopback() {
        None
    } else {
        Some(ip)
    }
}

fn host_label(name: &str) -> String {
    let s: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    let s = s.trim_matches('-').to_string();
    if s.is_empty() {
        "easyremote-pc".to_string()
    } else {
        s
    }
}

/// Start advertising. The returned daemon must be kept alive; dropping it stops the advertisement.
pub fn advertise(name: &str, port: u16, fingerprint: &str) -> Result<ServiceDaemon, String> {
    let daemon = ServiceDaemon::new().map_err(|e| e.to_string())?;
    let id: String = fingerprint.chars().take(16).collect();
    let props = [("v", "1"), ("id", id.as_str())];
    let host = format!("{}.local.", host_label(name));
    let info = ServiceInfo::new(SERVICE_TYPE, name, &host, (), port, &props[..])
        .map_err(|e| e.to_string())?
        .enable_addr_auto();
    daemon.register(info).map_err(|e| e.to_string())?;
    Ok(daemon)
}
