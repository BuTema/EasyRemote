//! Layout-independent typing on X11 (also XWayland sessions that expose the X server).
//!
//! The ordinary typing path presses physical keys of a US keyboard, so what appears depends on the layout
//! that is active on the PC, and letters outside ASCII cannot be typed at all. This module asks the X server
//! for unused keycodes, binds the wanted Unicode keysyms to them and sends fake key presses through the XTEST
//! extension. The application receives exactly that character whatever layout is active.
//!
//! Two things make it fast and reliable:
//! * a keysym stays bound to its keycode (least-recently-used eviction), so after a character was typed once
//!   the keyboard map is never touched for it again; changing the map makes every application reload its
//!   keymap, which is slow and loses keys pressed during the reload;
//! * the keys Backspace / Enter / Tab that come from the phone's keyboard go through the same channel, so
//!   they cannot overtake or fall behind the text typed just before them.

use std::collections::HashSet;
use std::thread::sleep;
use std::time::{Duration, Instant};
use x11rb::connection::Connection;
use x11rb::protocol::xproto::{self, ConnectionExt as _};
use x11rb::protocol::xtest::ConnectionExt as _;
use x11rb::rust_connection::RustConnection;

const KEY_PRESS: u8 = 2;
const KEY_RELEASE: u8 = 3;
/// How many unused keycodes are borrowed at most.
const MAX_SLOTS: usize = 64;
/// Time given to the applications to reload the changed keymap before keys that use it are pressed.
const MAP_SETTLE: Duration = Duration::from_millis(15);
/// Pause between two characters; lets slow applications keep up without dropping any.
const KEY_GAP: Duration = Duration::from_millis(2);
const RETRY_AFTER: Duration = Duration::from_secs(10);

pub const KS_BACKSPACE: u32 = 0xff08;
pub const KS_TAB: u32 = 0xff09;
pub const KS_RETURN: u32 = 0xff0d;

/// X keysym for a character: Latin-1 keeps its code, everything else uses the Unicode range.
pub fn keysym_for(c: char) -> u32 {
    let cp = c as u32;
    match cp {
        0x20..=0x7e | 0xa0..=0xff => cp,
        _ => 0x0100_0000 | cp,
    }
}

struct Slot {
    code: u8,
    keysym: u32, // 0 = free
    used: u64,
}

struct Conn {
    conn: RustConnection,
    root: xproto::Window,
    per_keycode: u8,
    min: u8,
    slots: Vec<Slot>,
    tick: u64,
}

impl Conn {
    fn open() -> Result<Conn, String> {
        let display = if std::env::var_os("DISPLAY").is_none() { Some(":0") } else { None };
        let (conn, screen) = RustConnection::connect(display).map_err(|e| format!("cannot connect to the X server: {e}"))?;
        let root = conn.setup().roots.get(screen).ok_or("no X screen")?.root;
        conn.xtest_get_version(2, 1)
            .map_err(|e| e.to_string())?
            .reply()
            .map_err(|e| format!("the X server has no XTEST extension: {e}"))?;
        let (min, max) = (conn.setup().min_keycode, conn.setup().max_keycode);
        let map = conn.get_keyboard_mapping(min, max - min + 1).map_err(|e| e.to_string())?.reply().map_err(|e| e.to_string())?;
        let n = map.keysyms_per_keycode as usize;
        if n == 0 {
            return Err("empty keyboard map".into());
        }
        let slots: Vec<Slot> = map
            .keysyms
            .chunks(n)
            .enumerate()
            .filter(|(_, syms)| syms.iter().all(|s| *s == 0))
            .map(|(i, _)| Slot { code: min + i as u8, keysym: 0, used: 0 })
            .take(MAX_SLOTS)
            .collect();
        if slots.is_empty() {
            return Err("no unused keycodes".into());
        }
        info!("typing through X11: {} spare keycodes", slots.len());
        Ok(Conn { conn, root, per_keycode: map.keysyms_per_keycode, min, slots, tick: 0 })
    }

    fn sync(&self) -> Option<()> {
        self.conn.get_input_focus().ok()?.reply().ok()?;
        Some(())
    }

    /// Forget the bindings somebody else removed (the layout was switched, the map was reset).
    fn revalidate(&mut self) -> Option<()> {
        let max = self.conn.setup().max_keycode;
        let map = self.conn.get_keyboard_mapping(self.min, max - self.min + 1).ok()?.reply().ok()?;
        let n = map.keysyms_per_keycode as usize;
        if n == 0 {
            return None;
        }
        self.per_keycode = map.keysyms_per_keycode;
        for s in &mut self.slots {
            if s.keysym != 0 {
                let i = (s.code - self.min) as usize * n;
                if map.keysyms.get(i).copied() != Some(s.keysym) {
                    s.keysym = 0;
                }
            }
        }
        Some(())
    }

    fn bind(&self, code: u8, sym: u32) -> Option<()> {
        let syms = vec![sym; self.per_keycode as usize];
        self.conn.change_keyboard_mapping(1, code, self.per_keycode, &syms).ok()?;
        Some(())
    }

    /// The keycode that produces `sym`, binding one if needed. `keep` are keysyms that must not be evicted.
    /// `changed` is set when the map was modified.
    fn code_for(&mut self, sym: u32, keep: &HashSet<u32>, changed: &mut bool) -> Option<u8> {
        self.tick += 1;
        let tick = self.tick;
        if let Some(s) = self.slots.iter_mut().find(|s| s.keysym == sym) {
            s.used = tick;
            return Some(s.code);
        }
        let idx = self
            .slots
            .iter()
            .enumerate()
            .filter(|(_, s)| s.keysym == 0 || !keep.contains(&s.keysym))
            .min_by_key(|(_, s)| (s.keysym != 0, s.used))
            .map(|(i, _)| i)?;
        let code = self.slots[idx].code;
        self.bind(code, sym)?;
        self.slots[idx].keysym = sym;
        self.slots[idx].used = tick;
        *changed = true;
        Some(code)
    }

    fn press(&self, code: u8, down: bool) -> Option<()> {
        let t = if down { KEY_PRESS } else { KEY_RELEASE };
        self.conn.xtest_fake_input(t, code, 0, self.root, 0, 0, 0).ok()?;
        Some(())
    }

    /// Types a run of keysyms that all fit in the slots at once.
    fn type_syms(&mut self, syms: &[u32]) -> Option<()> {
        let keep: HashSet<u32> = syms.iter().copied().collect();
        let mut changed = false;
        let mut codes = Vec::with_capacity(syms.len());
        for &s in syms {
            codes.push(self.code_for(s, &keep, &mut changed)?);
        }
        if changed {
            self.sync()?;
            sleep(MAP_SETTLE);
        }
        for code in codes {
            self.press(code, true)?;
            self.press(code, false)?;
            self.conn.flush().ok()?;
            sleep(KEY_GAP);
        }
        self.sync()
    }
}

impl Drop for Conn {
    fn drop(&mut self) {
        for s in &self.slots {
            if s.keysym != 0 {
                let _ = self.conn.change_keyboard_mapping(1, s.code, self.per_keycode, &vec![0; self.per_keycode as usize]);
            }
        }
        let _ = self.conn.flush();
    }
}

#[derive(Default)]
pub struct X11Typer {
    conn: Option<Conn>,
    failed_at: Option<Instant>,
    /// last keysym that was pressed down through [`X11Typer::key`], to release the same keycode
    held: Option<(u32, u8)>,
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
            let wayland = std::env::var("XDG_SESSION_TYPE").is_ok_and(|v| v.eq_ignore_ascii_case("wayland"));
            if wayland && std::env::var_os("EASYREMOTE_FORCE_X11").is_none() {
                info!("typing: Wayland session, the X11 typer is not used");
                self.failed_at = Some(Instant::now());
                return None;
            }
            match Conn::open() {
                Ok(c) => self.conn = Some(c),
                Err(e) => {
                    info!("typing: X11 typer unavailable ({e}); falling back to key presses and the clipboard");
                    self.failed_at = Some(Instant::now());
                }
            }
        }
        self.conn.as_mut()
    }

    fn fail(&mut self, what: &str) {
        info!("typing: X11 request failed ({what}); will reconnect");
        self.conn = None;
        self.held = None;
        self.failed_at = Some(Instant::now());
    }

    /// Type `s` character by character. Returns the number of characters typed from the start;
    /// anything after that point was not typed and is for the caller to deal with.
    pub fn type_str(&mut self, s: &str) -> usize {
        let Some(conn) = self.connect() else { return 0 };
        if conn.revalidate().is_none() {
            self.fail("keyboard map");
            return 0;
        }
        let limit = conn.slots.len();
        let mut typed = 0;
        let mut run: Vec<u32> = Vec::new();
        let mut uniq: HashSet<u32> = HashSet::new();
        let mut it = s.chars().peekable();
        while let Some(&c) = it.peek() {
            let sym = keysym_for(c);
            if !uniq.contains(&sym) && uniq.len() == limit {
                // the run uses every slot: type it before binding more
                if conn.type_syms(&run).is_none() {
                    self.fail("typing");
                    return typed;
                }
                typed += run.len();
                run.clear();
                uniq.clear();
            }
            uniq.insert(sym);
            run.push(sym);
            it.next();
        }
        if !run.is_empty() {
            if conn.type_syms(&run).is_none() {
                self.fail("typing");
                return typed;
            }
            typed += run.len();
        }
        typed
    }

    /// A key that comes from the phone's keyboard (Backspace, Enter, Tab) and has to stay in order with the text.
    /// Returns false when the X11 route is not available and the caller should use its own.
    pub fn key(&mut self, keysym: u32, down: bool) -> bool {
        let held = self.held;
        let Some(conn) = self.connect() else { return false };
        let code = if down {
            let mut changed = false;
            let keep: HashSet<u32> = [keysym].into_iter().collect();
            let Some(code) = conn.code_for(keysym, &keep, &mut changed) else {
                self.fail("key");
                return false;
            };
            if changed {
                let _ = conn.sync();
                sleep(MAP_SETTLE);
            }
            code
        } else {
            match held {
                Some((k, code)) if k == keysym => code,
                _ => return false,
            }
        };
        if conn.press(code, down).is_none() || conn.conn.flush().is_err() {
            self.fail("key");
            return false;
        }
        self.held = if down { Some((keysym, code)) } else { None };
        true
    }

    /// Whether a key press is currently held through [`X11Typer::key`] (its release must use the same route).
    pub fn holds(&self, keysym: u32) -> bool {
        self.held.is_some_and(|(k, _)| k == keysym)
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
    /// Types into a real window and checks which keysyms it receives, in order.
    #[test]
    #[ignore]
    fn typed_keys_arrive_in_order() {
        use x11rb::protocol::xproto::*;
        use x11rb::protocol::Event;
        let (conn, screen) = RustConnection::connect(None).unwrap();
        let root = conn.setup().roots[screen].root;
        let win = conn.generate_id().unwrap();
        conn.create_window(0, win, root, 0, 0, 100, 100, 0, WindowClass::INPUT_OUTPUT, 0, &CreateWindowAux::new().event_mask(EventMask::KEY_PRESS)).unwrap();
        conn.map_window(win).unwrap();
        conn.set_input_focus(InputFocus::PARENT, win, 0u32).unwrap();
        conn.get_input_focus().unwrap().reply().unwrap();

        let mut t = X11Typer::new();
        assert_eq!(t.type_str("aжБ1"), 4);
        assert!(t.key(KS_BACKSPACE, true));
        assert!(t.key(KS_BACKSPACE, false));
        assert_eq!(t.type_str("жz"), 2);
        conn.get_input_focus().unwrap().reply().unwrap();

        let (min, max) = (conn.setup().min_keycode, conn.setup().max_keycode);
        let map = conn.get_keyboard_mapping(min, max - min + 1).unwrap().reply().unwrap();
        let n = map.keysyms_per_keycode as usize;
        let mut got = Vec::new();
        while let Some(ev) = conn.poll_for_event().unwrap() {
            if let Event::KeyPress(k) = ev {
                got.push(map.keysyms[(k.detail - min) as usize * n]);
            }
        }
        let want: Vec<u32> = "aжБ1".chars().map(keysym_for).chain([KS_BACKSPACE]).chain("жz".chars().map(keysym_for)).collect();
        assert_eq!(got, want);
    }
}
