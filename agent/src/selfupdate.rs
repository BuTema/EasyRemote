//! Picks up an upgrade without a manual restart.
//!
//! Installing a new package replaces the agent's file on disk, but the copy that is already running
//! keeps running the old code until it is restarted (and `autostart on` does not replace a running
//! agent). So when a phone connects, an agent that finds its own file has been replaced restarts
//! itself into the new version; the phone simply reconnects a moment later.

/// Linux: if the running executable has been replaced on disk, re-execute the new one.
#[cfg(target_os = "linux")]
pub fn restart_if_replaced() {
    use std::os::unix::process::CommandExt;
    let Ok(exe) = std::fs::read_link("/proc/self/exe") else { return };
    let exe = exe.to_string_lossy().to_string();
    if let Some(path) = exe.strip_suffix(" (deleted)") {
        info!("this agent was upgraded on disk: restarting into the new version ({})", crate::build_id());
        let err = std::process::Command::new(path).args(std::env::args_os().skip(1)).exec();
        info!("restart into the new version failed: {err}");
    }
}

#[cfg(not(target_os = "linux"))]
pub fn restart_if_replaced() {}
