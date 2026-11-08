//! Linux backend: a virtual keyboard+mouse through /dev/uinput (works on X11 and Wayland alike),
//! volume through `pactl` (PulseAudio / PipeWire) or `wpctl`, with media-key fallback.

use super::{InputBackend, VolumeState};
use crate::hostenv::{flatpak_id, host_command, in_flatpak};
use crate::proto::{Button, Key, Media};
use evdev::{uinput::VirtualDevice, AttributeSet, EventType, InputEvent, KeyCode, RelativeAxisCode};
use std::io::Write;
use std::process::{Command, Stdio};
use std::thread::sleep;
use std::time::Duration;

pub struct LinuxInput {
    dev: VirtualDevice,
    wheel_v: i32,
    wheel_h: i32,
}

impl LinuxInput {
    pub fn new() -> Result<Self, String> {
        let mut keys = AttributeSet::<KeyCode>::new();
        for c in 1u16..=248 {
            keys.insert(KeyCode::new(c));
        }
        for b in [KeyCode::BTN_LEFT, KeyCode::BTN_RIGHT, KeyCode::BTN_MIDDLE] {
            keys.insert(b);
        }
        let rel = AttributeSet::from_iter([
            RelativeAxisCode::REL_X,
            RelativeAxisCode::REL_Y,
            RelativeAxisCode::REL_WHEEL,
            RelativeAxisCode::REL_HWHEEL,
            RelativeAxisCode::REL_WHEEL_HI_RES,
            RelativeAxisCode::REL_HWHEEL_HI_RES,
        ]);
        let hint = if in_flatpak() {
            format!(
                "cannot open /dev/uinput. Run once on the computer (asks for your password), then log out and in: \
                 flatpak run {} host-setup | sudo sh",
                flatpak_id()
            )
        } else {
            "cannot open /dev/uinput. Install the .deb package (or run packaging/linux/install.sh), \
             which loads the uinput module and adds a udev rule for it, then log out and in once"
                .to_string()
        };
        let dev = VirtualDevice::builder()
            .map_err(|e| format!("{hint}: {e}"))?
            .name("EasyRemote virtual input")
            .with_keys(&keys)
            .map_err(|e| format!("{hint}: {e}"))?
            .with_relative_axes(&rel)
            .map_err(|e| format!("{hint}: {e}"))?
            .build()
            .map_err(|e| format!("{hint}: {e}"))?;
        // Give the compositor a moment to notice the new device before the first event.
        sleep(Duration::from_millis(300));
        Ok(LinuxInput { dev, wheel_v: 0, wheel_h: 0 })
    }

    fn emit(&mut self, events: &[InputEvent]) {
        let _ = self.dev.emit(events);
    }

    fn key_ev(code: KeyCode, down: bool) -> InputEvent {
        InputEvent::new(EventType::KEY.0, code.code(), if down { 1 } else { 0 })
    }

    fn rel_ev(axis: RelativeAxisCode, v: i32) -> InputEvent {
        InputEvent::new(EventType::RELATIVE.0, axis.0, v)
    }

    fn tap(&mut self, code: KeyCode, shift: bool) {
        if shift {
            self.emit(&[Self::key_ev(KeyCode::KEY_LEFTSHIFT, true), Self::key_ev(code, true)]);
            self.emit(&[Self::key_ev(code, false), Self::key_ev(KeyCode::KEY_LEFTSHIFT, false)]);
        } else {
            self.emit(&[Self::key_ev(code, true)]);
            self.emit(&[Self::key_ev(code, false)]);
        }
    }

    fn paste(&mut self, s: &str) {
        if s.is_empty() || !set_clipboard(s) {
            return;
        }
        sleep(Duration::from_millis(40));
        self.emit(&[Self::key_ev(KeyCode::KEY_LEFTCTRL, true), Self::key_ev(KeyCode::KEY_V, true)]);
        self.emit(&[Self::key_ev(KeyCode::KEY_V, false), Self::key_ev(KeyCode::KEY_LEFTCTRL, false)]);
        sleep(Duration::from_millis(30));
    }
}

fn key_code(k: Key) -> KeyCode {
    match k {
        Key::Left => KeyCode::KEY_LEFT,
        Key::Right => KeyCode::KEY_RIGHT,
        Key::Up => KeyCode::KEY_UP,
        Key::Down => KeyCode::KEY_DOWN,
        Key::Escape => KeyCode::KEY_ESC,
        Key::Enter => KeyCode::KEY_ENTER,
        Key::Backspace => KeyCode::KEY_BACKSPACE,
        Key::Space => KeyCode::KEY_SPACE,
        Key::Tab => KeyCode::KEY_TAB,
        Key::Delete => KeyCode::KEY_DELETE,
        Key::Home => KeyCode::KEY_HOME,
        Key::End => KeyCode::KEY_END,
        Key::PageUp => KeyCode::KEY_PAGEUP,
        Key::PageDown => KeyCode::KEY_PAGEDOWN,
        Key::F11 => KeyCode::KEY_F11,
    }
}

/// US-layout scancodes for printable ASCII. Returns (key, needs_shift).
/// Anything else is typed by pasting from the clipboard.
fn ascii_key(c: char) -> Option<(KeyCode, bool)> {
    use KeyCode as K;
    let lower = |k: KeyCode| Some((k, false));
    let upper = |k: KeyCode| Some((k, true));
    match c {
        'a' => lower(K::KEY_A), 'b' => lower(K::KEY_B), 'c' => lower(K::KEY_C), 'd' => lower(K::KEY_D),
        'e' => lower(K::KEY_E), 'f' => lower(K::KEY_F), 'g' => lower(K::KEY_G), 'h' => lower(K::KEY_H),
        'i' => lower(K::KEY_I), 'j' => lower(K::KEY_J), 'k' => lower(K::KEY_K), 'l' => lower(K::KEY_L),
        'm' => lower(K::KEY_M), 'n' => lower(K::KEY_N), 'o' => lower(K::KEY_O), 'p' => lower(K::KEY_P),
        'q' => lower(K::KEY_Q), 'r' => lower(K::KEY_R), 's' => lower(K::KEY_S), 't' => lower(K::KEY_T),
        'u' => lower(K::KEY_U), 'v' => lower(K::KEY_V), 'w' => lower(K::KEY_W), 'x' => lower(K::KEY_X),
        'y' => lower(K::KEY_Y), 'z' => lower(K::KEY_Z),
        'A' => upper(K::KEY_A), 'B' => upper(K::KEY_B), 'C' => upper(K::KEY_C), 'D' => upper(K::KEY_D),
        'E' => upper(K::KEY_E), 'F' => upper(K::KEY_F), 'G' => upper(K::KEY_G), 'H' => upper(K::KEY_H),
        'I' => upper(K::KEY_I), 'J' => upper(K::KEY_J), 'K' => upper(K::KEY_K), 'L' => upper(K::KEY_L),
        'M' => upper(K::KEY_M), 'N' => upper(K::KEY_N), 'O' => upper(K::KEY_O), 'P' => upper(K::KEY_P),
        'Q' => upper(K::KEY_Q), 'R' => upper(K::KEY_R), 'S' => upper(K::KEY_S), 'T' => upper(K::KEY_T),
        'U' => upper(K::KEY_U), 'V' => upper(K::KEY_V), 'W' => upper(K::KEY_W), 'X' => upper(K::KEY_X),
        'Y' => upper(K::KEY_Y), 'Z' => upper(K::KEY_Z),
        '1' => lower(K::KEY_1), '2' => lower(K::KEY_2), '3' => lower(K::KEY_3), '4' => lower(K::KEY_4),
        '5' => lower(K::KEY_5), '6' => lower(K::KEY_6), '7' => lower(K::KEY_7), '8' => lower(K::KEY_8),
        '9' => lower(K::KEY_9), '0' => lower(K::KEY_0),
        '!' => upper(K::KEY_1), '@' => upper(K::KEY_2), '#' => upper(K::KEY_3), '$' => upper(K::KEY_4),
        '%' => upper(K::KEY_5), '^' => upper(K::KEY_6), '&' => upper(K::KEY_7), '*' => upper(K::KEY_8),
        '(' => upper(K::KEY_9), ')' => upper(K::KEY_0),
        '-' => lower(K::KEY_MINUS), '_' => upper(K::KEY_MINUS),
        '=' => lower(K::KEY_EQUAL), '+' => upper(K::KEY_EQUAL),
        '[' => lower(K::KEY_LEFTBRACE), '{' => upper(K::KEY_LEFTBRACE),
        ']' => lower(K::KEY_RIGHTBRACE), '}' => upper(K::KEY_RIGHTBRACE),
        '\\' => lower(K::KEY_BACKSLASH), '|' => upper(K::KEY_BACKSLASH),
        ';' => lower(K::KEY_SEMICOLON), ':' => upper(K::KEY_SEMICOLON),
        '\'' => lower(K::KEY_APOSTROPHE), '"' => upper(K::KEY_APOSTROPHE),
        '`' => lower(K::KEY_GRAVE), '~' => upper(K::KEY_GRAVE),
        ',' => lower(K::KEY_COMMA), '<' => upper(K::KEY_COMMA),
        '.' => lower(K::KEY_DOT), '>' => upper(K::KEY_DOT),
        '/' => lower(K::KEY_SLASH), '?' => upper(K::KEY_SLASH),
        ' ' => lower(K::KEY_SPACE),
        '\n' => lower(K::KEY_ENTER),
        '\t' => lower(K::KEY_TAB),
        _ => None,
    }
}

/// If the agent was started outside the desktop session (e.g. from a systemd user unit) DISPLAY may be
/// missing; fall back to the usual single-user X11 values so xclip / xsel still reach the clipboard.
fn desktop_env(cmd: &mut Command) {
    if std::env::var_os("DISPLAY").is_none() && std::env::var_os("WAYLAND_DISPLAY").is_none() {
        cmd.env("DISPLAY", ":0");
    }
    if std::env::var_os("XAUTHORITY").is_none() {
        if let Some(home) = dirs::home_dir() {
            let xauth = home.join(".Xauthority");
            if xauth.exists() {
                cmd.env("XAUTHORITY", xauth);
            }
        }
    }
}

fn set_clipboard(s: &str) -> bool {
    let wayland = std::env::var_os("WAYLAND_DISPLAY").is_some()
        || std::env::var("XDG_SESSION_TYPE").map(|t| t == "wayland").unwrap_or(false);
    let order: [(&str, &[&str]); 3] = if wayland {
        [("wl-copy", &[]), ("xclip", &["-selection", "clipboard"]), ("xsel", &["-ib"])]
    } else {
        [("xclip", &["-selection", "clipboard"]), ("xsel", &["-ib"]), ("wl-copy", &[])]
    };
    for (cmd, args) in order {
        let mut command = host_command(cmd);
        desktop_env(&mut command);
        let spawned = command
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
        if let Ok(mut child) = spawned {
            if let Some(mut stdin) = child.stdin.take() {
                let _ = stdin.write_all(s.as_bytes());
            } // stdin is closed here so the tool sees end of input
            // These tools put themselves in the background once they own the clipboard and then
            // exit with 0. A non-zero status (not installed, no display) means: try the next tool.
            if let Ok(status) = child.wait() {
                if status.success() {
                    return true;
                }
            }
        }
    }
    false
}

// ------------------------------------------------------------------ volume helpers

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let out = host_command(cmd).args(args).stderr(Stdio::null()).output().ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).to_string())
}

/// First "NN%" in a string (pactl output: `Volume: front-left: 29491 /  45% / -20.87 dB, ...`).
fn first_percent(s: &str) -> Option<u8> {
    let idx = s.find('%')?;
    let digits: String = s[..idx]
        .chars()
        .rev()
        .take_while(|c| c.is_ascii_digit())
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    digits.parse::<u32>().ok().map(|v| v.min(255) as u8)
}

/// `Volume: 0.45` or `Volume: 0.45 [MUTED]` (wpctl)
fn wpctl_volume(s: &str) -> Option<VolumeState> {
    let rest = s.trim().strip_prefix("Volume:")?.trim();
    let num = rest.split_whitespace().next()?.parse::<f32>().ok()?;
    Some(VolumeState { vol: (num * 100.0).round().clamp(0.0, 255.0) as u8, mute: rest.contains("[MUTED]") })
}

fn read_volume() -> Option<VolumeState> {
    if let Some(v) = run("pactl", &["get-sink-volume", "@DEFAULT_SINK@"]) {
        if let Some(vol) = first_percent(&v) {
            let mute = run("pactl", &["get-sink-mute", "@DEFAULT_SINK@"])
                .map(|m| m.contains("yes"))
                .unwrap_or(false);
            return Some(VolumeState { vol, mute });
        }
    }
    run("wpctl", &["get-volume", "@DEFAULT_AUDIO_SINK@"]).and_then(|s| wpctl_volume(&s))
}

fn set_volume(pct: u8) -> bool {
    run("pactl", &["set-sink-volume", "@DEFAULT_SINK@", &format!("{}%", pct)]).is_some()
        || run("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", &format!("{:.2}", pct as f32 / 100.0)]).is_some()
}

fn set_mute(on: bool) -> bool {
    let v = if on { "1" } else { "0" };
    run("pactl", &["set-sink-mute", "@DEFAULT_SINK@", v]).is_some()
        || run("wpctl", &["set-mute", "@DEFAULT_AUDIO_SINK@", v]).is_some()
}

impl InputBackend for LinuxInput {
    fn mouse_move(&mut self, dx: i32, dy: i32) {
        let mut ev = Vec::with_capacity(2);
        if dx != 0 {
            ev.push(Self::rel_ev(RelativeAxisCode::REL_X, dx));
        }
        if dy != 0 {
            ev.push(Self::rel_ev(RelativeAxisCode::REL_Y, dy));
        }
        if !ev.is_empty() {
            self.emit(&ev);
        }
    }

    fn scroll(&mut self, dx: i32, dy: i32) {
        let mut ev = Vec::with_capacity(4);
        if dy != 0 {
            ev.push(Self::rel_ev(RelativeAxisCode::REL_WHEEL_HI_RES, dy));
            self.wheel_v += dy;
            let notches = self.wheel_v / 120;
            if notches != 0 {
                ev.push(Self::rel_ev(RelativeAxisCode::REL_WHEEL, notches));
                self.wheel_v -= notches * 120;
            }
        }
        if dx != 0 {
            ev.push(Self::rel_ev(RelativeAxisCode::REL_HWHEEL_HI_RES, dx));
            self.wheel_h += dx;
            let notches = self.wheel_h / 120;
            if notches != 0 {
                ev.push(Self::rel_ev(RelativeAxisCode::REL_HWHEEL, notches));
                self.wheel_h -= notches * 120;
            }
        }
        if !ev.is_empty() {
            self.emit(&ev);
        }
    }

    fn button(&mut self, b: Button, down: bool) {
        let code = match b {
            Button::Left => KeyCode::BTN_LEFT,
            Button::Right => KeyCode::BTN_RIGHT,
            Button::Middle => KeyCode::BTN_MIDDLE,
        };
        self.emit(&[Self::key_ev(code, down)]);
    }

    fn key(&mut self, k: Key, down: bool) {
        self.emit(&[Self::key_ev(key_code(k), down)]);
    }

    fn text(&mut self, s: &str) {
        let mut pending = String::new();
        for c in s.chars() {
            match ascii_key(c) {
                Some((code, shift)) => {
                    if !pending.is_empty() {
                        let p = std::mem::take(&mut pending);
                        self.paste(&p);
                    }
                    self.tap(code, shift);
                }
                None => pending.push(c),
            }
        }
        if !pending.is_empty() {
            self.paste(&pending);
        }
    }

    fn media(&mut self, m: Media) {
        let code = match m {
            Media::PlayPause => KeyCode::KEY_PLAYPAUSE,
            Media::Next => KeyCode::KEY_NEXTSONG,
            Media::Prev => KeyCode::KEY_PREVIOUSSONG,
            Media::Stop => KeyCode::KEY_STOPCD,
        };
        self.tap(code, false);
    }

    fn volume_step(&mut self, steps: i8) -> Option<VolumeState> {
        match read_volume() {
            Some(cur) => {
                let new = (cur.vol as i32 + steps as i32 * 5).clamp(0, 100) as u8;
                set_volume(new);
                let mut mute = cur.mute;
                if mute && steps > 0 {
                    set_mute(false);
                    mute = false;
                }
                Some(VolumeState { vol: new, mute })
            }
            None => {
                // No pactl / wpctl: fall back to the desktop's own volume keys (step size is up to the desktop).
                let code = if steps >= 0 { KeyCode::KEY_VOLUMEUP } else { KeyCode::KEY_VOLUMEDOWN };
                for _ in 0..steps.unsigned_abs() {
                    self.tap(code, false);
                }
                None
            }
        }
    }

    fn mute_toggle(&mut self) -> Option<VolumeState> {
        match read_volume() {
            Some(cur) => {
                set_mute(!cur.mute);
                Some(VolumeState { vol: cur.vol, mute: !cur.mute })
            }
            None => {
                self.tap(KeyCode::KEY_MUTE, false);
                None
            }
        }
    }

    fn volume_state(&mut self) -> Option<VolumeState> {
        read_volume()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pactl_and_wpctl() {
        let s = "Volume: front-left: 29491 /  45% / -20.87 dB,   front-right: 29491 /  45% / -20.87 dB";
        assert_eq!(first_percent(s), Some(45));
        assert_eq!(first_percent("Volume: 0 / 100% / 0.00 dB"), Some(100));
        assert_eq!(first_percent("no percent"), None);
        assert_eq!(wpctl_volume("Volume: 0.45\n"), Some(VolumeState { vol: 45, mute: false }));
        assert_eq!(wpctl_volume("Volume: 0.30 [MUTED]"), Some(VolumeState { vol: 30, mute: true }));
    }

    #[test]
    fn ascii_table_covers_printable_ascii() {
        for c in (0x20u8..0x7f).map(|b| b as char) {
            assert!(ascii_key(c).is_some(), "missing {:?}", c);
        }
        assert!(ascii_key('й').is_none());
    }

    #[test]
    fn well_known_scancodes() {
        // spot-check against linux/input-event-codes.h
        assert_eq!(ascii_key('a').unwrap().0.code(), 30);
        assert_eq!(ascii_key('q').unwrap().0.code(), 16);
        assert_eq!(ascii_key('z').unwrap().0.code(), 44);
        assert_eq!(ascii_key('1').unwrap().0.code(), 2);
        assert_eq!(ascii_key('0').unwrap().0.code(), 11);
        assert_eq!(ascii_key('/').unwrap().0.code(), 53);
        assert_eq!(ascii_key(' ').unwrap().0.code(), 57);
        assert_eq!(ascii_key('\n').unwrap().0.code(), 28);
        assert_eq!(ascii_key('A'), Some((KeyCode::KEY_A, true)));
        assert_eq!(ascii_key('?'), Some((KeyCode::KEY_SLASH, true)));
    }
}
