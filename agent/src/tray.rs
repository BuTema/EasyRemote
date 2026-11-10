//! Windows system-tray icon.
//!
//! The background agent (`easyremoted.exe`) shows a small icon in the notification area. Its menu
//! shows the status and offers: pair a phone (a window with the QR code), revoke phones, start with
//! Windows, open the log / config folder, and quit.
//!
//! Plain Win32 (no extra crates): one hidden window receives the tray messages, a second window
//! shows the pairing QR code. The icon is drawn in memory, so there are no resource files. The
//! agent itself runs on its own thread; this thread only runs the message loop, which sleeps until
//! the user clicks the icon, plus one 3-second timer that keeps the icon colour and tooltip current.

use crate::{autostart, config, daemon, discovery, pairing, server};
use ::windows::core::{w, PCWSTR};
use ::windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS, HINSTANCE, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM, COLORREF};
use ::windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateBitmap, CreateSolidBrush, DeleteObject, DrawTextW, EndPaint, FillRect, GetStockObject, SelectObject, SetBkMode,
    COLOR_WINDOW, DEFAULT_GUI_FONT, DT_CENTER, DT_NOPREFIX, DT_WORDBREAK, HBRUSH, HGDIOBJ, PAINTSTRUCT, TRANSPARENT,
};
use ::windows::Win32::System::LibraryLoader::GetModuleHandleW;
use ::windows::Win32::System::Threading::CreateMutexW;
use ::windows::Win32::UI::Shell::{
    ShellExecuteW, Shell_NotifyIconW, NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_TIP, NIIF_INFO, NIM_ADD, NIM_DELETE, NIM_MODIFY, NOTIFYICONDATAW,
};
use ::windows::Win32::UI::WindowsAndMessaging::{
    AdjustWindowRect, AppendMenuW, CreateIconIndirect, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyIcon, DestroyMenu,
    DestroyWindow, DispatchMessageW, GetClientRect, GetCursorPos, GetMessageW, KillTimer, LoadCursorW, MessageBoxW, PostMessageW,
    PostQuitMessage, RegisterClassW, RegisterWindowMessageW, SetForegroundWindow, SetTimer, ShowWindow, TrackPopupMenu, TranslateMessage,
    CW_USEDEFAULT, HICON, HMENU, ICONINFO, IDC_ARROW, IDYES, MB_ICONQUESTION, MB_SETFOREGROUND, MB_YESNO, MF_CHECKED, MF_GRAYED, MF_POPUP,
    MF_SEPARATOR, MF_STRING, MENU_ITEM_FLAGS, MSG, SW_SHOW, SW_SHOWNORMAL, TPM_BOTTOMALIGN, TPM_RETURNCMD, TPM_RIGHTBUTTON, WM_APP,
    WM_CONTEXTMENU, WM_CREATE, WM_DESTROY, WM_LBUTTONUP, WM_NULL, WM_PAINT, WM_RBUTTONUP, WM_TIMER, WNDCLASSW, WS_CAPTION, WS_OVERLAPPED,
    WS_SYSMENU,
};
use std::ffi::c_void;
use std::sync::atomic::{AtomicIsize, AtomicU32, AtomicUsize, Ordering};
use std::sync::Mutex;

const WM_TRAY: u32 = WM_APP + 1;
const WM_AGENT_DONE: u32 = WM_APP + 2;

const TIMER_REFRESH: usize = 1;
const TIMER_PAIR: usize = 2;

const ID_PAIR: usize = 1;
const ID_AUTOSTART: usize = 2;
const ID_LOG: usize = 3;
const ID_FOLDER: usize = 4;
const ID_REVOKE_ALL: usize = 5;
const ID_QUIT: usize = 6;
const ID_REVOKE_BASE: usize = 1000;
const MAX_LISTED: usize = 50;

static TRAY_HWND: AtomicIsize = AtomicIsize::new(0);
static PAIR_HWND: AtomicIsize = AtomicIsize::new(0);
static TASKBAR_CREATED: AtomicU32 = AtomicU32::new(0);
/// 0 = idle icon, 1 = connected icon
static ICON_IDLE: AtomicIsize = AtomicIsize::new(0);
static ICON_LIVE: AtomicIsize = AtomicIsize::new(0);
/// Phones connected when the icon was last updated, to redraw only on change.
static SHOWN: AtomicUsize = AtomicUsize::new(usize::MAX);

struct PairState {
    modules: Vec<bool>,
    width: usize,
    scale: i32,
    code: String,
    addr: String,
    devices_before: usize,
}

static PAIR: Mutex<Option<PairState>> = Mutex::new(None);

fn hwnd_of(v: isize) -> HWND {
    HWND(v as *mut c_void)
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn put(dst: &mut [u16], s: &str) {
    let mut n = 0;
    for u in s.encode_utf16().take(dst.len() - 1) {
        dst[n] = u;
        n += 1;
    }
    dst[n] = 0;
}

// ------------------------------------------------------------------ entry point

/// Shows the tray icon and runs the agent until the user chooses Quit.
pub fn run(mock: bool, stderr_log: bool) -> Result<(), String> {
    unsafe {
        // One agent (and so one icon) per Windows session.
        let _single = CreateMutexW(None, false, w!("Local\\EasyRemoteAgent"));
        if GetLastError() == ERROR_ALREADY_EXISTS {
            return Ok(());
        }

        let Some(hwnd) = create_tray() else {
            // no desktop to put an icon on: still run the agent
            return daemon::run_agent(mock, stderr_log);
        };

        let hwnd_raw = hwnd.0 as isize;
        let agent = std::thread::Builder::new()
            .name("agent".into())
            .spawn(move || {
                let r = daemon::run_agent(mock, stderr_log);
                // the agent stopped (Quit, or it could not start): close the tray too
                let _ = PostMessageW(Some(hwnd_of(hwnd_raw)), WM_AGENT_DONE, WPARAM(0), LPARAM(0));
                r
            })
            .map_err(|e| e.to_string())?;

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        daemon::request_shutdown();
        agent.join().map_err(|_| "the agent thread panicked".to_string())?
    }
}

unsafe fn create_tray() -> Option<HWND> {
    let hmod = GetModuleHandleW(PCWSTR::null()).ok()?;
    let hinst = HINSTANCE(hmod.0);

    let class = WNDCLASSW {
        lpfnWndProc: Some(tray_proc),
        hInstance: hinst,
        lpszClassName: w!("EasyRemoteTray"),
        ..Default::default()
    };
    if RegisterClassW(&class) == 0 {
        return None;
    }
    // a normal (never shown) top-level window: message-only windows do not get "TaskbarCreated"
    let hwnd = CreateWindowExW(Default::default(), w!("EasyRemoteTray"), w!("EasyRemote"), WS_OVERLAPPED, 0, 0, 0, 0, None, None, Some(hinst), None).ok()?;
    TRAY_HWND.store(hwnd.0 as isize, Ordering::SeqCst);
    TASKBAR_CREATED.store(RegisterWindowMessageW(w!("TaskbarCreated")), Ordering::SeqCst);

    if let Some(i) = make_icon((0x2B, 0x7C, 0xE0)) {
        ICON_IDLE.store(i.0 as isize, Ordering::SeqCst);
    }
    if let Some(i) = make_icon((0x2E, 0x9E, 0x57)) {
        ICON_LIVE.store(i.0 as isize, Ordering::SeqCst);
    }
    add_icon(hwnd);
    SetTimer(Some(hwnd), TIMER_REFRESH, 3000, None);
    Some(hwnd)
}

// ------------------------------------------------------------------ icon

/// A round icon with a white mouse pointer, drawn with 3x3 supersampling.
unsafe fn make_icon(rgb: (u8, u8, u8)) -> Option<HICON> {
    const N: usize = 32;
    let arrow: [(f32, f32); 7] = [(11.0, 6.5), (11.0, 24.5), (15.2, 20.5), (18.2, 26.5), (21.2, 25.2), (18.3, 19.4), (23.5, 19.4)];
    let inside = |x: f32, y: f32| -> bool {
        let mut c = false;
        let mut j = arrow.len() - 1;
        for i in 0..arrow.len() {
            let (xi, yi) = arrow[i];
            let (xj, yj) = arrow[j];
            if (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi {
                c = !c;
            }
            j = i;
        }
        c
    };
    let mut px = vec![0u8; N * N * 4];
    for y in 0..N {
        for x in 0..N {
            let (mut circ, mut arr) = (0u32, 0u32);
            for sy in 0..3 {
                for sx in 0..3 {
                    let fx = x as f32 + (sx as f32 + 0.5) / 3.0;
                    let fy = y as f32 + (sy as f32 + 0.5) / 3.0;
                    let (dx, dy) = (fx - 16.0, fy - 16.0);
                    if dx * dx + dy * dy <= 15.0 * 15.0 {
                        circ += 1;
                        if inside(fx, fy) {
                            arr += 1;
                        }
                    }
                }
            }
            if circ > 0 {
                let a = circ as f32 / 9.0;
                let white = arr as f32 / circ as f32;
                let mix = |c: u8| (c as f32 * (1.0 - white) + 255.0 * white) * a; // premultiplied
                let i = (y * N + x) * 4;
                px[i] = mix(rgb.2) as u8; // B
                px[i + 1] = mix(rgb.1) as u8; // G
                px[i + 2] = mix(rgb.0) as u8; // R
                px[i + 3] = (a * 255.0) as u8;
            }
        }
    }
    let color = CreateBitmap(N as i32, N as i32, 1, 32, Some(px.as_ptr() as *const c_void));
    let mask_bits = [0u8; N / 8 * N];
    let mask = CreateBitmap(N as i32, N as i32, 1, 1, Some(mask_bits.as_ptr() as *const c_void));
    let info = ICONINFO { fIcon: true.into(), xHotspot: 0, yHotspot: 0, hbmMask: mask, hbmColor: color };
    let icon = CreateIconIndirect(&info).ok();
    let _ = DeleteObject(HGDIOBJ(color.0));
    let _ = DeleteObject(HGDIOBJ(mask.0));
    icon
}

fn tip_text(connected: usize) -> String {
    let name = config::load_config(&config::dir()).map(|c| c.name).unwrap_or_default();
    let status = match connected {
        0 => "No phone connected".to_string(),
        1 => "Phone connected".to_string(),
        n => format!("{n} phones connected"),
    };
    format!("EasyRemote ({name})\n{status}")
}

unsafe fn nid(hwnd: HWND) -> NOTIFYICONDATAW {
    let mut n: NOTIFYICONDATAW = std::mem::zeroed();
    n.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
    n.hWnd = hwnd;
    n.uID = 1;
    n
}

unsafe fn current_icon(connected: usize) -> HICON {
    let v = if connected > 0 { ICON_LIVE.load(Ordering::SeqCst) } else { ICON_IDLE.load(Ordering::SeqCst) };
    HICON(v as *mut c_void)
}

unsafe fn add_icon(hwnd: HWND) {
    let connected = server::CONNECTED.load(Ordering::Relaxed);
    let mut n = nid(hwnd);
    n.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    n.uCallbackMessage = WM_TRAY;
    n.hIcon = current_icon(connected);
    put(&mut n.szTip, &tip_text(connected));
    let _ = Shell_NotifyIconW(NIM_ADD, &n);
    SHOWN.store(connected, Ordering::SeqCst);
}

/// Updates icon colour and tooltip when the number of connected phones changed.
unsafe fn refresh(hwnd: HWND) {
    let connected = server::CONNECTED.load(Ordering::Relaxed);
    if SHOWN.swap(connected, Ordering::SeqCst) == connected {
        return;
    }
    let mut n = nid(hwnd);
    n.uFlags = NIF_ICON | NIF_TIP;
    n.hIcon = current_icon(connected);
    put(&mut n.szTip, &tip_text(connected));
    let _ = Shell_NotifyIconW(NIM_MODIFY, &n);
}

unsafe fn balloon(title: &str, text: &str) {
    let h = TRAY_HWND.load(Ordering::SeqCst);
    if h == 0 {
        return;
    }
    let mut n = nid(hwnd_of(h));
    n.uFlags = NIF_INFO;
    n.dwInfoFlags = NIIF_INFO;
    put(&mut n.szInfoTitle, title);
    put(&mut n.szInfo, text);
    let _ = Shell_NotifyIconW(NIM_MODIFY, &n);
}

// ------------------------------------------------------------------ tray window

unsafe extern "system" fn tray_proc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    match msg {
        WM_TRAY => {
            let ev = (lp.0 as u32) & 0xFFFF;
            if ev == WM_RBUTTONUP || ev == WM_LBUTTONUP || ev == WM_CONTEXTMENU {
                show_menu(hwnd);
            }
            LRESULT(0)
        }
        WM_TIMER => {
            if wp.0 == TIMER_REFRESH {
                refresh(hwnd);
            }
            LRESULT(0)
        }
        WM_AGENT_DONE => {
            let _ = DestroyWindow(hwnd);
            LRESULT(0)
        }
        WM_DESTROY => {
            let _ = KillTimer(Some(hwnd), TIMER_REFRESH);
            let n = nid(hwnd);
            let _ = Shell_NotifyIconW(NIM_DELETE, &n);
            for icon in [&ICON_IDLE, &ICON_LIVE] {
                let v = icon.swap(0, Ordering::SeqCst);
                if v != 0 {
                    let _ = DestroyIcon(HICON(v as *mut c_void));
                }
            }
            TRAY_HWND.store(0, Ordering::SeqCst);
            PostQuitMessage(0);
            LRESULT(0)
        }
        m if m != 0 && m == TASKBAR_CREATED.load(Ordering::SeqCst) => {
            // Explorer restarted: its tray was emptied, put the icon back
            SHOWN.store(usize::MAX, Ordering::SeqCst);
            add_icon(hwnd);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}

// ------------------------------------------------------------------ menu

unsafe fn add(menu: HMENU, flags: MENU_ITEM_FLAGS, id: usize, text: &str) {
    let t = wide(text);
    let _ = AppendMenuW(menu, flags, id, PCWSTR(t.as_ptr()));
}

unsafe fn separator(menu: HMENU) {
    let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
}

unsafe fn show_menu(hwnd: HWND) {
    let dir = config::dir();
    let name = config::load_config(&dir).map(|c| c.name).unwrap_or_default();
    let devices = config::load_devices(&dir);
    let connected = server::CONNECTED.load(Ordering::Relaxed);
    let Ok(menu) = CreatePopupMenu() else { return };

    add(menu, MF_STRING | MF_GRAYED, 0, &format!("EasyRemote — {name}"));
    let status = match connected {
        0 => "No phone connected".to_string(),
        1 => "● Phone connected".to_string(),
        n => format!("● {n} phones connected"),
    };
    add(menu, MF_STRING | MF_GRAYED, 0, &status);
    separator(menu);

    add(menu, MF_STRING, ID_PAIR, "Pair a phone…");
    if let Ok(sub) = CreatePopupMenu() {
        if devices.is_empty() {
            add(sub, MF_STRING | MF_GRAYED, 0, "No paired phones");
        } else {
            for (i, d) in devices.iter().take(MAX_LISTED).enumerate() {
                add(sub, MF_STRING, ID_REVOKE_BASE + i, &format!("Revoke “{}”", d.name));
            }
            separator(sub);
            add(sub, MF_STRING, ID_REVOKE_ALL, "Revoke all phones");
        }
        let t = wide(&format!("Paired phones ({})", devices.len()));
        let _ = AppendMenuW(menu, MF_POPUP | MF_STRING, sub.0 as usize, PCWSTR(t.as_ptr()));
    }
    separator(menu);

    let auto = if autostart::login_entry_exists() { MF_STRING | MF_CHECKED } else { MF_STRING };
    add(menu, auto, ID_AUTOSTART, "Start with Windows");
    add(menu, MF_STRING, ID_LOG, "Open log file");
    add(menu, MF_STRING, ID_FOLDER, "Open settings folder");
    separator(menu);
    add(menu, MF_STRING, ID_QUIT, "Quit EasyRemote");

    let mut pt = POINT::default();
    let _ = GetCursorPos(&mut pt);
    // the menu closes when the user clicks elsewhere only if our window is in front
    let _ = SetForegroundWindow(hwnd);
    let cmd = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, pt.x, pt.y, None, hwnd, None).0 as usize;
    let _ = PostMessageW(Some(hwnd), WM_NULL, WPARAM(0), LPARAM(0));
    let _ = DestroyMenu(menu);

    if cmd != 0 {
        command(hwnd, cmd, &devices);
    }
}

unsafe fn confirm(hwnd: HWND, text: &str) -> bool {
    let t = wide(text);
    MessageBoxW(Some(hwnd), PCWSTR(t.as_ptr()), w!("EasyRemote"), MB_YESNO | MB_ICONQUESTION | MB_SETFOREGROUND) == IDYES
}

unsafe fn open(path: &std::path::Path) {
    let p = wide(&path.to_string_lossy());
    ShellExecuteW(None, w!("open"), PCWSTR(p.as_ptr()), PCWSTR::null(), PCWSTR::null(), SW_SHOWNORMAL);
}

unsafe fn command(hwnd: HWND, cmd: usize, devices: &[config::Device]) {
    let dir = config::dir();
    match cmd {
        ID_PAIR => open_pair_window(),
        ID_AUTOSTART => {
            let on = !autostart::login_entry_exists();
            match autostart::set_login_entry(on) {
                Ok(()) => balloon("EasyRemote", if on { "Will start when you sign in to Windows." } else { "Will no longer start with Windows." }),
                Err(e) => balloon("EasyRemote", &format!("Could not change the setting: {e}")),
            }
        }
        ID_LOG => open(&dir.join("agent.log")),
        ID_FOLDER => open(&dir),
        ID_REVOKE_ALL => {
            if confirm(hwnd, "Remove all paired phones?\nThey will have to be paired again.") {
                let _ = config::revoke(&dir, "all");
                balloon("EasyRemote", "All phones were removed.");
            }
        }
        ID_QUIT => {
            daemon::request_shutdown();
            let _ = DestroyWindow(hwnd);
        }
        c if c >= ID_REVOKE_BASE && c - ID_REVOKE_BASE < devices.len() => {
            let d = &devices[c - ID_REVOKE_BASE];
            if confirm(hwnd, &format!("Remove “{}”?\nThat phone will have to be paired again.", d.name)) {
                let _ = config::revoke(&dir, &d.id);
                balloon("EasyRemote", &format!("“{}” was removed.", d.name));
            }
        }
        _ => {}
    }
}

// ------------------------------------------------------------------ pairing window

const PAIR_CLIENT_W: i32 = 380;
const PAIR_TOP: i32 = 52;
const PAIR_FOOTER: i32 = 150;

unsafe fn open_pair_window() {
    let existing = PAIR_HWND.load(Ordering::SeqCst);
    if existing != 0 {
        let _ = SetForegroundWindow(hwnd_of(existing));
        return;
    }
    let dir = config::dir();
    let (Ok(cfg), Ok(id)) = (config::load_config(&dir), config::load_identity(&dir)) else {
        balloon("EasyRemote", "Could not read the agent settings.");
        return;
    };
    let Ok(secret) = pairing::start(&dir) else {
        balloon("EasyRemote", "Could not open a pairing window.");
        return;
    };
    let host = discovery::local_ip().map(|i| i.to_string()).unwrap_or_else(|| "127.0.0.1".into());
    let uri = pairing::uri(&host, cfg.port, &id.fingerprint, &secret, &cfg.name);
    let Ok(code) = qrcode::QrCode::new(uri.as_bytes()) else {
        balloon("EasyRemote", "Could not draw the QR code.");
        return;
    };
    let width = code.width();
    let modules: Vec<bool> = code.to_colors().into_iter().map(|c| matches!(c, qrcode::Color::Dark)).collect();
    let scale = ((PAIR_CLIENT_W - 40) / (width as i32 + 8)).max(3);
    let total = scale * (width as i32 + 8);
    let client_h = PAIR_TOP + total + PAIR_FOOTER;

    *PAIR.lock().unwrap() = Some(PairState {
        modules,
        width,
        scale,
        code: pairing::group(&secret),
        addr: format!("{host}:{}", cfg.port),
        devices_before: config::load_devices(&dir).len(),
    });

    let Ok(hmod) = GetModuleHandleW(PCWSTR::null()) else { return };
    let hinst = HINSTANCE(hmod.0);
    let class = WNDCLASSW {
        lpfnWndProc: Some(pair_proc),
        hInstance: hinst,
        hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
        hbrBackground: HBRUSH((COLOR_WINDOW.0 + 1) as usize as *mut c_void),
        lpszClassName: w!("EasyRemotePair"),
        ..Default::default()
    };
    RegisterClassW(&class); // fails harmlessly when it already exists

    let style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU;
    let mut rc = RECT { left: 0, top: 0, right: PAIR_CLIENT_W, bottom: client_h };
    let _ = AdjustWindowRect(&mut rc, style, false);
    let hwnd = CreateWindowExW(
        Default::default(),
        w!("EasyRemotePair"),
        w!("Pair a phone"),
        style,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        rc.right - rc.left,
        rc.bottom - rc.top,
        None,
        None,
        Some(hinst),
        None,
    );
    match hwnd {
        Ok(h) => {
            PAIR_HWND.store(h.0 as isize, Ordering::SeqCst);
            let _ = ShowWindow(h, SW_SHOW);
            let _ = SetForegroundWindow(h);
        }
        Err(_) => {
            *PAIR.lock().unwrap() = None;
            pairing::cancel(&dir);
        }
    }
}

unsafe fn text_line(hdc: ::windows::Win32::Graphics::Gdi::HDC, text: &str, top: i32, height: i32) {
    let mut buf: Vec<u16> = text.encode_utf16().collect();
    let mut rc = RECT { left: 16, top, right: PAIR_CLIENT_W - 16, bottom: top + height };
    DrawTextW(hdc, &mut buf, &mut rc, DT_CENTER | DT_WORDBREAK | DT_NOPREFIX);
}

unsafe fn paint(hwnd: HWND) {
    let mut ps: PAINTSTRUCT = std::mem::zeroed();
    let hdc = BeginPaint(hwnd, &mut ps);
    SetBkMode(hdc, TRANSPARENT);
    SelectObject(hdc, GetStockObject(DEFAULT_GUI_FONT));

    if let Some(p) = PAIR.lock().unwrap().as_ref() {
        text_line(hdc, "Open EasyRemote on your phone, tap + and scan this code", 14, 34);

        let n = p.width as i32;
        let total = p.scale * (n + 8);
        let x0 = (PAIR_CLIENT_W - total) / 2 + 4 * p.scale;
        let y0 = PAIR_TOP + 4 * p.scale;
        let black = CreateSolidBrush(COLORREF(0));
        for (i, dark) in p.modules.iter().enumerate() {
            if *dark {
                let (cx, cy) = ((i as i32) % n, (i as i32) / n);
                let r = RECT { left: x0 + cx * p.scale, top: y0 + cy * p.scale, right: x0 + (cx + 1) * p.scale, bottom: y0 + (cy + 1) * p.scale };
                FillRect(hdc, &r, black);
            }
        }
        let _ = DeleteObject(HGDIOBJ(black.0));

        let y = PAIR_TOP + total + 6;
        text_line(hdc, "Or type this code in the app:", y, 18);
        text_line(hdc, &p.code, y + 20, 20);
        text_line(hdc, &format!("PC address: {}", p.addr), y + 46, 18);
        text_line(hdc, "Valid for 5 minutes. This window closes by itself once the phone is paired.", y + 72, 34);
    }
    let _ = EndPaint(hwnd, &ps);
}

unsafe extern "system" fn pair_proc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    match msg {
        WM_CREATE => {
            SetTimer(Some(hwnd), TIMER_PAIR, 1000, None);
            LRESULT(0)
        }
        WM_PAINT => {
            paint(hwnd);
            LRESULT(0)
        }
        WM_TIMER => {
            if wp.0 == TIMER_PAIR {
                let dir = config::dir();
                if !pairing::is_open(&dir) {
                    // used up (a phone paired), expired, or cancelled
                    let before = PAIR.lock().unwrap().as_ref().map(|p| p.devices_before).unwrap_or(0);
                    let now = config::load_devices(&dir).len();
                    if now > before {
                        balloon("EasyRemote", "Phone paired.");
                    }
                    let _ = DestroyWindow(hwnd);
                }
            }
            LRESULT(0)
        }
        WM_DESTROY => {
            let _ = KillTimer(Some(hwnd), TIMER_PAIR);
            PAIR_HWND.store(0, Ordering::SeqCst);
            *PAIR.lock().unwrap() = None;
            pairing::cancel(&config::dir()); // closing the window closes the pairing window too
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wp, lp),
    }
}
