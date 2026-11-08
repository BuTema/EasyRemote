//! Windows backend: SendInput for mouse / keyboard / media keys, Core Audio for exact volume steps.

use super::{InputBackend, VolumeState};
use crate::proto::{Button, Key, Media};
use windows::core::{Interface, GUID};
use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
use windows::Win32::Media::Audio::{eMultimedia, eRender, IMMDeviceEnumerator, MMDeviceEnumerator};
use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CLSCTX_ALL, COINIT_MULTITHREADED};
use windows::Win32::UI::Input::KeyboardAndMouse::*;

pub struct WindowsInput {
    com_ready: bool,
}

impl WindowsInput {
    pub fn new() -> Self {
        // The input worker thread owns this object, so COM is initialised on that thread's first use.
        WindowsInput { com_ready: false }
    }

    fn ensure_com(&mut self) {
        if !self.com_ready {
            unsafe {
                let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            }
            self.com_ready = true;
        }
    }

    fn endpoint(&mut self) -> Option<IAudioEndpointVolume> {
        self.ensure_com();
        unsafe {
            let enumerator: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).ok()?;
            let device = enumerator.GetDefaultAudioEndpoint(eRender, eMultimedia).ok()?;
            device.Activate::<IAudioEndpointVolume>(CLSCTX_ALL, None).ok()
        }
    }
}

fn send(inputs: &[INPUT]) {
    if inputs.is_empty() {
        return;
    }
    unsafe {
        SendInput(inputs, std::mem::size_of::<INPUT>() as i32);
    }
}

fn mouse_input(dx: i32, dy: i32, data: u32, flags: MOUSE_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT { dx, dy, mouseData: data, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

fn key_input(vk: VIRTUAL_KEY, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT { wVk: vk, wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

fn vk_of(k: Key) -> (VIRTUAL_KEY, bool) {
    // second value: needs KEYEVENTF_EXTENDEDKEY (arrows, navigation block)
    match k {
        Key::Left => (VK_LEFT, true),
        Key::Right => (VK_RIGHT, true),
        Key::Up => (VK_UP, true),
        Key::Down => (VK_DOWN, true),
        Key::Escape => (VK_ESCAPE, false),
        Key::Enter => (VK_RETURN, false),
        Key::Backspace => (VK_BACK, false),
        Key::Space => (VK_SPACE, false),
        Key::Tab => (VK_TAB, false),
        Key::Delete => (VK_DELETE, true),
        Key::Home => (VK_HOME, true),
        Key::End => (VK_END, true),
        Key::PageUp => (VK_PRIOR, true),
        Key::PageDown => (VK_NEXT, true),
        Key::F11 => (VK_F11, false),
    }
}

impl InputBackend for WindowsInput {
    fn mouse_move(&mut self, dx: i32, dy: i32) {
        send(&[mouse_input(dx, dy, 0, MOUSEEVENTF_MOVE)]);
    }

    fn scroll(&mut self, dx: i32, dy: i32) {
        let mut v = Vec::with_capacity(2);
        if dy != 0 {
            // mouseData carries a signed delta in a DWORD; 120 = one notch, positive = away from the user (up)
            v.push(mouse_input(0, 0, dy as u32, MOUSEEVENTF_WHEEL));
        }
        if dx != 0 {
            v.push(mouse_input(0, 0, dx as u32, MOUSEEVENTF_HWHEEL));
        }
        send(&v);
    }

    fn button(&mut self, b: Button, down: bool) {
        let flag = match (b, down) {
            (Button::Left, true) => MOUSEEVENTF_LEFTDOWN,
            (Button::Left, false) => MOUSEEVENTF_LEFTUP,
            (Button::Right, true) => MOUSEEVENTF_RIGHTDOWN,
            (Button::Right, false) => MOUSEEVENTF_RIGHTUP,
            (Button::Middle, true) => MOUSEEVENTF_MIDDLEDOWN,
            (Button::Middle, false) => MOUSEEVENTF_MIDDLEUP,
        };
        send(&[mouse_input(0, 0, 0, flag)]);
    }

    fn key(&mut self, k: Key, down: bool) {
        let (vk, ext) = vk_of(k);
        let mut flags = if down { KEYBD_EVENT_FLAGS(0) } else { KEYEVENTF_KEYUP };
        if ext {
            flags |= KEYEVENTF_EXTENDEDKEY;
        }
        send(&[key_input(vk, 0, flags)]);
    }

    fn text(&mut self, s: &str) {
        let mut inputs = Vec::new();
        for c in s.chars() {
            if c == '\n' {
                inputs.push(key_input(VK_RETURN, 0, KEYBD_EVENT_FLAGS(0)));
                inputs.push(key_input(VK_RETURN, 0, KEYEVENTF_KEYUP));
                continue;
            }
            if c == '\t' {
                inputs.push(key_input(VK_TAB, 0, KEYBD_EVENT_FLAGS(0)));
                inputs.push(key_input(VK_TAB, 0, KEYEVENTF_KEYUP));
                continue;
            }
            // Unicode injection: one event per UTF-16 unit (surrogate pairs are sent unit by unit).
            let mut buf = [0u16; 2];
            for unit in c.encode_utf16(&mut buf).iter() {
                inputs.push(key_input(VIRTUAL_KEY(0), *unit, KEYEVENTF_UNICODE));
                inputs.push(key_input(VIRTUAL_KEY(0), *unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
            }
        }
        send(&inputs);
    }

    fn media(&mut self, m: Media) {
        let vk = match m {
            Media::PlayPause => VK_MEDIA_PLAY_PAUSE,
            Media::Next => VK_MEDIA_NEXT_TRACK,
            Media::Prev => VK_MEDIA_PREV_TRACK,
            Media::Stop => VK_MEDIA_STOP,
        };
        send(&[key_input(vk, 0, KEYBD_EVENT_FLAGS(0)), key_input(vk, 0, KEYEVENTF_KEYUP)]);
    }

    fn volume_step(&mut self, steps: i8) -> Option<VolumeState> {
        let ep = self.endpoint()?;
        unsafe {
            let cur = ep.GetMasterVolumeLevelScalar().ok()?;
            let pct = ((cur * 100.0).round() as i32 + steps as i32 * 5).clamp(0, 100);
            ep.SetMasterVolumeLevelScalar(pct as f32 / 100.0, &GUID::zeroed()).ok()?;
            let mut muted = ep.GetMute().map(|b| b.as_bool()).unwrap_or(false);
            if muted && steps > 0 {
                let _ = ep.SetMute(false, &GUID::zeroed());
                muted = false;
            }
            Some(VolumeState { vol: pct as u8, mute: muted })
        }
    }

    fn mute_toggle(&mut self) -> Option<VolumeState> {
        let ep = self.endpoint()?;
        unsafe {
            let muted = ep.GetMute().ok()?.as_bool();
            ep.SetMute(!muted, &GUID::zeroed()).ok()?;
            let vol = (ep.GetMasterVolumeLevelScalar().ok()? * 100.0).round() as u8;
            Some(VolumeState { vol, mute: !muted })
        }
    }

    fn volume_state(&mut self) -> Option<VolumeState> {
        let ep = self.endpoint()?;
        unsafe {
            let vol = (ep.GetMasterVolumeLevelScalar().ok()? * 100.0).round() as u8;
            let mute = ep.GetMute().ok()?.as_bool();
            Some(VolumeState { vol, mute })
        }
    }
}

#[allow(dead_code)]
fn _assert_interface<T: Interface>() {}
