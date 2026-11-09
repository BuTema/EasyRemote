//! Input injection. One backend per platform, all driven from a single worker thread so that
//! slow operations (spawning `pactl`, COM calls) never block the network task and ordering is kept.

use crate::proto::{Action, Button, Cmd, Key, Media};
use std::collections::HashSet;
use std::sync::mpsc;
use std::sync::{Arc, Mutex};

#[cfg(target_os = "linux")]
mod linux;
#[cfg(windows)]
mod windows;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VolumeState {
    pub vol: u8,
    pub mute: bool,
}

/// What a volume button press should result in: the level moves by `steps` * 5 % (kept within
/// 0..=100) and audio is always unmuted, whichever button was pressed and also at the limits.
pub fn plan_volume(cur: VolumeState, steps: i8) -> VolumeState {
    VolumeState { vol: (cur.vol.min(100) as i32 + steps as i32 * 5).clamp(0, 100) as u8, mute: if steps != 0 { false } else { cur.mute } }
}

pub trait InputBackend: Send {
    fn mouse_move(&mut self, dx: i32, dy: i32);
    /// Hi-res wheel units, 120 = one notch. +dy up, +dx right.
    fn scroll(&mut self, dx: i32, dy: i32);
    fn button(&mut self, b: Button, down: bool);
    fn key(&mut self, k: Key, down: bool);
    fn text(&mut self, s: &str);
    fn media(&mut self, m: Media);
    /// Change master volume by `steps` * 5 %. Returns the new state if it can be determined.
    fn volume_step(&mut self, steps: i8) -> Option<VolumeState>;
    fn mute_toggle(&mut self) -> Option<VolumeState>;
    fn volume_state(&mut self) -> Option<VolumeState>;
}

pub fn create_real() -> Result<Box<dyn InputBackend>, String> {
    #[cfg(target_os = "linux")]
    {
        return linux::LinuxInput::new().map(|b| Box::new(b) as Box<dyn InputBackend>);
    }
    #[cfg(windows)]
    {
        return Ok(Box::new(windows::WindowsInput::new()));
    }
    #[allow(unreachable_code)]
    Err("this operating system is not supported yet (Windows and Linux only)".to_string())
}

// ------------------------------------------------------------------ mock backend

/// Records what it was asked to do. Used by tests and by `easyremoted --mock`.
pub struct MockBackend {
    events: Arc<Mutex<Vec<String>>>,
    echo: bool,
    vol: u8,
    mute: bool,
}

impl MockBackend {
    pub fn new() -> (Self, Arc<Mutex<Vec<String>>>) {
        let events = Arc::new(Mutex::new(Vec::new()));
        (
            MockBackend { events: events.clone(), echo: false, vol: 50, mute: false },
            events,
        )
    }

    /// Mock that prints every event to the log (for trying the phone app without touching the PC).
    pub fn logging() -> Self {
        let (mut m, _) = Self::new();
        m.echo = true;
        m
    }

    fn rec(&self, s: String) {
        if self.echo {
            info!("[mock] {}", s);
        }
        if let Ok(mut v) = self.events.lock() {
            v.push(s);
        }
    }

    fn state(&self) -> VolumeState {
        VolumeState { vol: self.vol, mute: self.mute }
    }
}

impl InputBackend for MockBackend {
    fn mouse_move(&mut self, dx: i32, dy: i32) {
        self.rec(format!("move {} {}", dx, dy));
    }
    fn scroll(&mut self, dx: i32, dy: i32) {
        self.rec(format!("scroll {} {}", dx, dy));
    }
    fn button(&mut self, b: Button, down: bool) {
        self.rec(format!("button {:?} {}", b, if down { "down" } else { "up" }));
    }
    fn key(&mut self, k: Key, down: bool) {
        self.rec(format!("key {:?} {}", k, if down { "down" } else { "up" }));
    }
    fn text(&mut self, s: &str) {
        self.rec(format!("text {}", s));
    }
    fn media(&mut self, m: Media) {
        self.rec(format!("media {:?}", m));
    }
    fn volume_step(&mut self, steps: i8) -> Option<VolumeState> {
        let next = plan_volume(self.state(), steps);
        self.vol = next.vol;
        self.mute = next.mute;
        self.rec(format!("volume {} {}", self.vol, self.mute));
        Some(self.state())
    }
    fn mute_toggle(&mut self) -> Option<VolumeState> {
        self.mute = !self.mute;
        self.rec(format!("mute {} {}", self.vol, self.mute));
        Some(self.state())
    }
    fn volume_state(&mut self) -> Option<VolumeState> {
        Some(self.state())
    }
}

// ------------------------------------------------------------------ worker

/// Where state updates (`{"t":"state",...}` JSON text) are sent.
pub type Reply = tokio::sync::mpsc::UnboundedSender<String>;

enum Msg {
    Cmd(Cmd, Option<Reply>),
    Query(Reply),
    ReleaseAll,
}

#[derive(Clone)]
pub struct InputHandle {
    tx: mpsc::Sender<Msg>,
}

fn state_json(v: VolumeState) -> String {
    format!(r#"{{"t":"state","vol":{},"mute":{}}}"#, v.vol, v.mute)
}

impl InputHandle {
    pub fn spawn(backend: Box<dyn InputBackend>) -> InputHandle {
        let (tx, rx) = mpsc::channel::<Msg>();
        std::thread::Builder::new()
            .name("input".into())
            .spawn(move || worker(backend, rx))
            .expect("failed to start input thread");
        InputHandle { tx }
    }

    pub fn send(&self, cmd: Cmd, reply: Option<Reply>) {
        let _ = self.tx.send(Msg::Cmd(cmd, reply));
    }

    /// Ask for the current volume state; the answer arrives on `reply`.
    pub fn query(&self, reply: Reply) {
        let _ = self.tx.send(Msg::Query(reply));
    }

    /// Release every button / key that a vanished phone might have left pressed.
    pub fn release_all(&self) {
        let _ = self.tx.send(Msg::ReleaseAll);
    }
}

fn worker(mut backend: Box<dyn InputBackend>, rx: mpsc::Receiver<Msg>) {
    let mut held_buttons: HashSet<Button> = HashSet::new();
    let mut held_keys: HashSet<Key> = HashSet::new();

    for msg in rx {
        match msg {
            Msg::Cmd(cmd, reply) => {
                let state = match cmd {
                    Cmd::Move { dx, dy } => {
                        backend.mouse_move(dx, dy);
                        None
                    }
                    Cmd::Scroll { dx, dy } => {
                        backend.scroll(dx, dy);
                        None
                    }
                    Cmd::Button(b, Action::Press) => {
                        held_buttons.insert(b);
                        backend.button(b, true);
                        None
                    }
                    Cmd::Button(b, Action::Release) => {
                        held_buttons.remove(&b);
                        backend.button(b, false);
                        None
                    }
                    Cmd::Button(b, Action::Click) => {
                        backend.button(b, true);
                        backend.button(b, false);
                        None
                    }
                    Cmd::Key(k, Action::Press) => {
                        held_keys.insert(k);
                        backend.key(k, true);
                        None
                    }
                    Cmd::Key(k, Action::Release) => {
                        held_keys.remove(&k);
                        backend.key(k, false);
                        None
                    }
                    Cmd::Key(k, Action::Click) => {
                        backend.key(k, true);
                        backend.key(k, false);
                        None
                    }
                    Cmd::Media(m) => {
                        backend.media(m);
                        None
                    }
                    Cmd::Text(s) => {
                        backend.text(&s);
                        None
                    }
                    Cmd::Volume(steps) => backend.volume_step(steps),
                    Cmd::Mute => backend.mute_toggle(),
                };
                if let (Some(state), Some(reply)) = (state, reply) {
                    let _ = reply.send(state_json(state));
                }
            }
            Msg::Query(reply) => {
                if let Some(state) = backend.volume_state() {
                    let _ = reply.send(state_json(state));
                }
            }
            Msg::ReleaseAll => {
                for b in held_buttons.drain() {
                    backend.button(b, false);
                }
                for k in held_keys.drain() {
                    backend.key(k, false);
                }
            }
        }
    }
}

#[cfg(test)]
mod volume_tests {
    use super::*;

    fn st(vol: u8, mute: bool) -> VolumeState {
        VolumeState { vol, mute }
    }

    #[test]
    fn steps_are_five_percent_and_clamped() {
        assert_eq!(plan_volume(st(50, false), 1), st(55, false));
        assert_eq!(plan_volume(st(50, false), -2), st(40, false));
        assert_eq!(plan_volume(st(98, false), 1), st(100, false));
        assert_eq!(plan_volume(st(100, false), 1), st(100, false));
        assert_eq!(plan_volume(st(3, false), -1), st(0, false));
        assert_eq!(plan_volume(st(0, false), -1), st(0, false));
    }

    #[test]
    fn either_button_unmutes_even_at_the_limits() {
        assert_eq!(plan_volume(st(50, true), 1), st(55, false));
        assert_eq!(plan_volume(st(50, true), -1), st(45, false));
        assert_eq!(plan_volume(st(100, true), 1), st(100, false));
        assert_eq!(plan_volume(st(0, true), -1), st(0, false));
    }

    #[test]
    fn mock_backend_follows_the_plan() {
        let (mut m, _) = MockBackend::new();
        assert_eq!(m.mute_toggle(), Some(st(50, true)));
        assert_eq!(m.volume_step(-1), Some(st(45, false)));
        assert_eq!(m.mute_toggle(), Some(st(45, true)));
        assert_eq!(m.mute_toggle(), Some(st(45, false)));
    }
}
