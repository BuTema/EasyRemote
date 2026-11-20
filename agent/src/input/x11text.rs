//! Layout-independent text typing on X11 (also XWayland sessions that expose the X server).
//!
//! The agent's normal typing path presses physical keys of a US keyboard, so what appears depends on
//! the layout that is active on the PC, and letters outside ASCII cannot be typed at all. This module
//! asks the X server for a few unused keycodes, binds the wanted Unicode keysym to one of them, and
//! sends a fake key press/release through the XTEST extension. The application then receives exactly
//! that character whatever layout is active; no layout switching is needed.

use std::thread::sleep;
use std::time::{Duration, Instant};
use x11rb::connection::Connection;
use x11rb::protocol::xproto::{self, ConnectionExt as _};
use x11rb::protocol::xtest::ConnectionExt as _;
use x11rb::rust_connection::RustConnection;

const KEY_PRESS: u8 = 2;
const KEY_RELEASE: u8 = 3;
/// How many spare keycodes are rotated through (so a key is never rebound right after it was used).
const MAX_SLOTS: usize = 8;
/// Time given to the clients to pick up the changed mapping before the key is pressed.
const MAP_SETTLE: Duration = Duration::from_millis(10);
const KEY_HOLD: Duration = Duration::from_millis(3);
const RETRY_AFTER: Duration = Duration::from_secs(10);

/// X keysym for a character: Latin-1 keeps its code, everything else uses the Unicode range.
pub fn keysym_for(c: char) -> u32 {
    let cp = c as u32;
    match cp {
        0x20..=0x7e | 0xa0..=0xff => cp,
        _ => 0x0100_0000 | cp,
    }
}

struct Conn {
    conn: RustConnection,
    root: xproto::Window,
    per_keycode: u8,
    spare: Vec<u8>,
    next: usize,
}

impl Conn {
    fn open() -> Option<Conn> {
        let (conn, screen) = RustConnection::connect(None).ok()?;
        let root = conn.setup().roots.get(screen)?.root;
        conn.xtest_get_version(2, 1).ok()?.reply().ok()?;
        let (min, max) = (conn.setup().min_keycode, conn.setup().max_keycode);
        let map = conn.get_keyboard_mapping(min, max - min + 1).ok()?.reply().ok()?;
        let n = map.keysyms_per_keycode as usize;
        if n == 0 {
            return None;
        }
        let spare: Vec<u8> = map
            .keysyms
            .chunks(n)
            .enumerate()
            .filter(|(_, syms)| syms.iter().all(|s| *s == 0))
            .map(|(i, _)| min + i as u8)
            .take(MAX_SLOTS)
            .collect();
        if spare.is_empty() {
            return None;
        }
        Some(Conn { conn, root, per_keycode: map.keysyms_per_keycode, spare, next: 0 })
    }

    fn sync(&self) -> Option<()> {
        self.conn.get_input_focus().ok()?.reply().ok()?;
        Some(())
    }

    fn bind(&self, code: u8, sym: u32) -> Option<()> {
        let syms = vec![sym; self.per_keycode as usize];
        self.conn.change_keyboard_mapping(1, code, self.per_keycode, &syms).ok()?;
        Some(())
    }

    fn type_char(&mut self, c: char) -> Option<()> {
        let code = self.spare[self.next % self.spare.len()];
        self.next += 1;
        self.bind(code, keysym_for(c))?;
        self.sync()?;
        sleep(MAP_SETTLE);
        self.conn.xtest_fake_input(KEY_PRESS, code, 0, self.root, 0, 0, 0).ok()?;
        self.conn.flush().ok()?;
        sleep(KEY_HOLD);
        self.conn.xtest_fake_input(KEY_RELEASE, code, 0, self.root, 0, 0, 0).ok()?;
        self.sync()
    }

    /// Give the borrowed keycodes back to the server so that nothing stays bound.
    fn release_all(&self) {
        for &code in &self.spare {
            let _ = self.conn.change_keyboard_mapping(1, code, self.per_keycode, &vec![0; self.per_keycode as usize]);
        }
        let _ = self.sync();
    }
}

#[derive(Default)]
pub struct X11Typer {
    conn: Option<Conn>,
    failed_at: Option<Instant>,
}

impl X11Typer {
    pub fn new() -> Self {
        Self::default()
    }

    fn connect(&mut self) -> Option<&mut Conn> {
        if self.conn.is_none() {
            if self.failed_at.is_some_and(|t| t.elapsed() < RETRY_AFTER) {
                return None;
            }
            if std::env::var("XDG_SESSION_TYPE").is_ok_and(|v| v.eq_ignore_ascii_case("wayland")) && std::env::var_os("EASYREMOTE_FORCE_X11").is_none() {
                self.failed_at = Some(Instant::now());
                return None;
            }
            self.conn = Conn::open();
            if self.conn.is_none() {
                self.failed_at = Some(Instant::now());
            }
        }
        self.conn.as_mut()
    }

    /// Type `s` character by character. Returns the number of characters typed from the start;
    /// anything after that point was not typed and is for the caller to deal with.
    pub fn type_str(&mut self, s: &str) -> usize {
        let Some(conn) = self.connect() else { return 0 };
        let mut typed = 0;
        for c in s.chars() {
            if conn.type_char(c).is_none() {
                // the connection is gone or the server refused: drop it, retry later
                self.conn = None;
                self.failed_at = Some(Instant::now());
                return typed;
            }
            typed += 1;
        }
        conn.release_all();
        typed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keysyms() {
        assert_eq!(keysym_for('a'), 0x61);
        assert_eq!(keysym_for('é'), 0xe9);
        assert_eq!(keysym_for('ж'), 0x0100_0436);
        assert_eq!(keysym_for('Ї'), 0x0100_0407);
        assert_eq!(keysym_for('€'), 0x0100_20ac);
        assert_eq!(keysym_for('😀'), 0x0101_f600);
    }

    /// Needs an X server with XTEST: `Xvfb :99 &` and `DISPLAY=:99 cargo test -- --ignored`.
    #[test]
    #[ignore]
    fn types_into_a_live_server() {
        let mut t = X11Typer::new();
        assert_eq!(t.type_str("aж"), 2);
    }
}
