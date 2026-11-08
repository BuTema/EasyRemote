//! Running inside a Flatpak sandbox.
//!
//! The Flatpak build keeps the same code but, where it needs the real desktop (volume through
//! `pactl`, clipboard through `xclip` / `xsel` / `wl-copy`), it runs those programs on the host with
//! `flatpak-spawn --host`. That needs the `org.freedesktop.Flatpak` talk permission, granted in the
//! manifest. Outside Flatpak everything runs directly.

use std::process::Command;

pub const DEFAULT_APP_ID: &str = "io.github.butema.EasyRemote";

/// Script printed by `easyremote host-setup`; run on the host as root (`| sudo sh`).
pub const HOST_SETUP_SCRIPT: &str = include_str!("host-setup.sh");

/// True when running inside a Flatpak sandbox (Flatpak creates `/.flatpak-info` in every sandbox).
pub fn in_flatpak() -> bool {
    std::path::Path::new("/.flatpak-info").exists()
}

pub fn flatpak_id() -> String {
    std::env::var("FLATPAK_ID").unwrap_or_else(|_| DEFAULT_APP_ID.to_string())
}

/// The words that go in front of a program to run it on the host.
pub fn spawn_prefix(flatpak: bool, has_display: bool) -> Vec<String> {
    if !flatpak {
        return Vec::new();
    }
    let mut v = vec!["flatpak-spawn".to_string(), "--host".to_string()];
    if !has_display {
        // The sandbox has no display sockets, so DISPLAY is usually unset here. The host session
        // normally knows its own; this is only the fallback for a plain single-user X11 desktop.
        v.push("--env=DISPLAY=:0".to_string());
    }
    v
}

/// A command that runs `program` on the host, also from inside a Flatpak sandbox.
pub fn host_command(program: &str) -> Command {
    let has_display = std::env::var_os("DISPLAY").is_some() || std::env::var_os("WAYLAND_DISPLAY").is_some();
    let mut words = spawn_prefix(in_flatpak(), has_display);
    words.push(program.to_string());
    let mut it = words.into_iter();
    let mut cmd = Command::new(it.next().expect("at least the program"));
    cmd.args(it);
    cmd
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_run_has_no_prefix() {
        assert!(spawn_prefix(false, true).is_empty());
        assert!(spawn_prefix(false, false).is_empty());
    }

    #[test]
    fn flatpak_run_goes_through_flatpak_spawn() {
        assert_eq!(spawn_prefix(true, true), vec!["flatpak-spawn", "--host"]);
        assert_eq!(spawn_prefix(true, false), vec!["flatpak-spawn", "--host", "--env=DISPLAY=:0"]);
    }

    #[test]
    fn host_command_outside_flatpak_is_the_program_itself() {
        // the test machine is not a Flatpak sandbox
        if !in_flatpak() {
            let c = host_command("pactl");
            assert_eq!(c.get_program(), "pactl");
            assert_eq!(c.get_args().count(), 0);
        }
    }

    #[test]
    fn host_setup_script_is_a_shell_script() {
        assert!(HOST_SETUP_SCRIPT.starts_with("#!/bin/sh"));
        assert!(HOST_SETUP_SCRIPT.contains("uinput"));
        assert!(HOST_SETUP_SCRIPT.contains("47821"));
    }
}
