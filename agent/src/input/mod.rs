//! Input injection. One backend per platform, all driven from a single worker thread so that
//! slow operations (spawning `pactl`, COM calls) never block the network task and ordering is kept.

use crate::proto::{Action, Button, Cmd, Key, Media};
use std::collections::HashSet;
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

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

/// Text for `easyremote audio-debug`: what the audio system reports (Linux only).
pub fn audio_debug() -> String {
    #[cfg(target_os = "linux")]
    {
        linux::audio_debug()
    }
    #[cfg(not(target_os = "linux"))]
    {
        "audio-debug is only available on Linux".to_string()
    }
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
    /// Mute or unmute to a definite state (a second press on the phone must always undo the first,
    /// whatever the phone believed the state to be).
    fn mute_set(&mut self, on: bool) -> Option<VolumeState> {
        match self.volume_state() {
            Some(s) if s.mute == on => Some(s),
            _ => self.mute_toggle(),
        }
    }
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
    /// Applies every command exactly as it arrives (tests, mock mode).
    pub fn spawn(backend: Box<dyn InputBackend>) -> InputHandle {
        Self::start(backend, false)
    }

    /// Like [`spawn`], but pointer movement is played out evenly over a few milliseconds instead of
    /// in the bursts in which it arrives over Wi-Fi (see [`PointerSmoother`]).
    pub fn spawn_smoothed(backend: Box<dyn InputBackend>) -> InputHandle {
        Self::start(backend, true)
    }

    fn start(backend: Box<dyn InputBackend>, smooth: bool) -> InputHandle {
        let (tx, rx) = mpsc::channel::<Msg>();
        std::thread::Builder::new()
            .name("input".into())
            .spawn(move || worker(backend, rx, smooth))
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

/// Spreads pointer movement evenly over time.
///
/// Movement reaches the PC in bursts: the phone sends a frame per touch event (60 to 120 a second)
/// and Wi-Fi delivers them with uneven gaps. Applied as they come, each burst is one visible hop of
/// the cursor. A real mouse reports at a steady 125 to 1000 Hz, so its motion looks continuous. The
/// smoother holds what is still to be moved and emits a share of it every [`SMOOTH_TICK`], which
/// turns the bursts into an even stream (about one frame of delay, [`SMOOTH_TAU_MS`]).
/// Nothing is dropped: the whole displacement is always played out, whole pixels at a time with the
/// fractions carried over, and a click, key or scroll first plays out everything pending.
pub struct PointerSmoother {
    rem_x: f32,
    rem_y: f32,
    acc_x: f32,
    acc_y: f32,
    /// drain time constant (ms); follows how often movement arrives
    tau_ms: f32,
    /// smoothed gap between arriving movement frames (ms)
    gap_ms: f32,
}

pub const SMOOTH_TICK: Duration = Duration::from_millis(2);
/// Time constant of the drain: the pending movement shrinks by 1/e every this many milliseconds.
pub const SMOOTH_TAU_MS: f32 = 6.0;

impl PointerSmoother {
    pub fn new() -> Self {
        PointerSmoother { rem_x: 0.0, rem_y: 0.0, acc_x: 0.0, acc_y: 0.0, tau_ms: SMOOTH_TAU_MS, gap_ms: 8.0 }
    }

    /// Reports that a movement frame arrived `gap_ms` after the previous one. The faster frames
    /// arrive the less smoothing is needed, so the delay shrinks with the gap: about 0.8 of it,
    /// between 2.5 ms and [`SMOOTH_TAU_MS`]. (Gaps over 60 ms are a new gesture and are ignored.)
    pub fn observe_gap(&mut self, gap_ms: f32) {
        if gap_ms <= 60.0 {
            self.gap_ms += 0.3 * (gap_ms.max(0.5) - self.gap_ms);
            self.tau_ms = (0.8 * self.gap_ms).clamp(2.5, SMOOTH_TAU_MS);
        }
    }

    pub fn add(&mut self, dx: i32, dy: i32) {
        self.rem_x += dx as f32;
        self.rem_y += dy as f32;
    }

    pub fn pending(&self) -> bool {
        self.rem_x != 0.0 || self.rem_y != 0.0
    }

    /// Movement to apply now, `dt_ms` after the previous step.
    pub fn step(&mut self, dt_ms: f32) -> (i32, i32) {
        let a = 1.0 - (-dt_ms.clamp(0.5, 10.0) / self.tau_ms).exp();
        let tx = Self::share(self.rem_x, a);
        let ty = Self::share(self.rem_y, a);
        self.rem_x -= tx;
        self.rem_y -= ty;
        (Self::whole(&mut self.acc_x, tx), Self::whole(&mut self.acc_y, ty))
    }

    /// Everything that is still pending, right now.
    pub fn flush(&mut self) -> (i32, i32) {
        let (x, y) = (self.rem_x + self.acc_x, self.rem_y + self.acc_y);
        let (tau, gap) = (self.tau_ms, self.gap_ms);
        *self = PointerSmoother::new();
        self.tau_ms = tau;
        self.gap_ms = gap;
        (x.round() as i32, y.round() as i32)
    }

    fn share(rem: f32, a: f32) -> f32 {
        // the last pixel goes out in one go instead of trailing off forever
        if rem.abs() < 1.0 { rem } else { rem * a }
    }

    fn whole(acc: &mut f32, add: f32) -> i32 {
        *acc += add;
        let whole = acc.trunc();
        *acc -= whole;
        whole as i32
    }
}

/// Windows timers tick every 15.6 ms unless asked otherwise, which would defeat a 2 ms smoother.
fn raise_timer_resolution() {
    #[cfg(windows)]
    unsafe {
        let _ = ::windows::Win32::Media::timeBeginPeriod(1); // `::` = the crate, not our own input/windows.rs
    }
}

fn worker(mut backend: Box<dyn InputBackend>, rx: mpsc::Receiver<Msg>, smooth: bool) {
    let mut held_buttons: HashSet<Button> = HashSet::new();
    let mut held_keys: HashSet<Key> = HashSet::new();
    let mut smoother = PointerSmoother::new();
    let mut last_step = Instant::now();
    let mut last_move_at: Option<Instant> = None;

    if smooth {
        raise_timer_resolution();
    }

    loop {
        let msg = if smoother.pending() {
            match rx.recv_timeout(SMOOTH_TICK) {
                Ok(m) => Some(m),
                Err(mpsc::RecvTimeoutError::Timeout) => None,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            }
        } else {
            match rx.recv() {
                Ok(m) => {
                    last_step = Instant::now(); // idle -> moving: start the clock now
                    Some(m)
                }
                Err(_) => break,
            }
        };

        // play out a slice of the pending movement
        if smoother.pending() && last_step.elapsed() >= SMOOTH_TICK {
            let dt = last_step.elapsed().as_secs_f32() * 1000.0;
            last_step = Instant::now();
            let (dx, dy) = smoother.step(dt);
            if dx != 0 || dy != 0 {
                backend.mouse_move(dx, dy);
            }
        }

        let Some(msg) = msg else { continue };
        match msg {
            Msg::Cmd(cmd, reply) => {
                // keep the order of events: finish the pending movement before anything else
                if !matches!(cmd, Cmd::Move { .. }) && smoother.pending() {
                    let (dx, dy) = smoother.flush();
                    if dx != 0 || dy != 0 {
                        backend.mouse_move(dx, dy);
                    }
                }
                let state = match cmd {
                    Cmd::Move { dx, dy } => {
                        if smooth {
                            let now = Instant::now();
                            if let Some(prev) = last_move_at {
                                smoother.observe_gap(now.duration_since(prev).as_secs_f32() * 1000.0);
                            }
                            last_move_at = Some(now);
                            smoother.add(dx, dy);
                        } else {
                            backend.mouse_move(dx, dy);
                        }
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
                    Cmd::MuteSet(on) => backend.mute_set(on),
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
                smoother = PointerSmoother::new();
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

#[cfg(test)]
mod smoother_tests {
    use super::*;

    /// Plays out all pending movement in 2 ms steps and returns the per-step output.
    fn drain(s: &mut PointerSmoother) -> Vec<(i32, i32)> {
        let mut out = Vec::new();
        for _ in 0..1000 {
            if !s.pending() {
                break;
            }
            out.push(s.step(2.0));
        }
        assert!(!s.pending(), "smoother must finish");
        out
    }

    #[test]
    fn total_displacement_is_preserved() {
        let mut s = PointerSmoother::new();
        let (mut sx, mut sy) = (0, 0);
        let (mut ex, mut ey) = (0, 0);
        for (dx, dy) in [(7, -3), (1, 1), (-13, 5), (22, 0), (0, -9), (3, 3)] {
            s.add(dx, dy);
            sx += dx;
            sy += dy;
            for _ in 0..3 {
                let (x, y) = s.step(2.0);
                ex += x;
                ey += y;
            }
        }
        for (x, y) in drain(&mut s) {
            ex += x;
            ey += y;
        }
        let (fx, fy) = s.flush();
        assert_eq!((ex + fx, ey + fy), (sx, sy));
    }

    #[test]
    fn a_burst_is_spread_over_many_small_steps() {
        let mut s = PointerSmoother::new();
        s.add(40, 0);
        let steps = drain(&mut s);
        assert!(steps.len() >= 8, "40 px should take several ticks, got {}", steps.len());
        assert!(steps.iter().all(|(x, _)| *x <= 12), "no single hop of more than a third: {:?}", steps);
        assert_eq!(steps.iter().map(|(x, _)| x).sum::<i32>(), 40);
    }

    #[test]
    fn it_stops_promptly() {
        let mut s = PointerSmoother::new();
        s.add(100, 100);
        let steps = drain(&mut s);
        assert!(steps.len() * 2 <= 50, "100 px must be out within ~50 ms, took {} ms", steps.len() * 2);
    }

    #[test]
    fn flush_plays_out_everything_at_once() {
        let mut s = PointerSmoother::new();
        s.add(30, -20);
        assert_eq!(s.flush(), (30, -20));
        assert!(!s.pending());
    }

    #[test]
    fn worker_applies_smoothed_moves_in_order_with_clicks() {
        let (mock, log) = MockBackend::new();
        let h = InputHandle::spawn_smoothed(Box::new(mock));
        h.send(Cmd::Move { dx: 50, dy: 0 }, None);
        h.send(Cmd::Button(Button::Left, Action::Click), None);
        std::thread::sleep(Duration::from_millis(80));
        let log = log.lock().unwrap().clone();
        let moved: i32 = log.iter().filter_map(|l| l.strip_prefix("move ")).map(|r| r.split(' ').next().unwrap().parse::<i32>().unwrap()).sum();
        assert_eq!(moved, 50, "{:?}", log);
        let click = log.iter().position(|l| l.starts_with("button")).unwrap();
        assert!(log[..click].iter().all(|l| l.starts_with("move")), "all movement precedes the click: {:?}", log);
        assert!(log[click..].iter().all(|l| l.starts_with("button")), "{:?}", log);
    }
}
