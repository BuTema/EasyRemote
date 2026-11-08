//! Minimal logger: stderr (foreground only) + a size-capped file in the config directory.
//! The daemon has no console on Windows, so the file is the only place errors can go.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

struct Sink {
    file: Option<File>,
    stderr: bool,
}

static SINK: Mutex<Sink> = Mutex::new(Sink { file: None, stderr: true });

pub fn init(dir: &Path, stderr: bool) {
    let path = dir.join("agent.log");
    if let Ok(meta) = std::fs::metadata(&path) {
        if meta.len() > 512 * 1024 {
            let _ = std::fs::rename(&path, dir.join("agent.log.old"));
        }
    }
    let file = OpenOptions::new().create(true).append(true).open(&path).ok();
    if let Ok(mut s) = SINK.lock() {
        s.file = file;
        s.stderr = stderr;
    }
}

pub fn write(msg: &str) {
    let line = format!("{} {}\n", timestamp(), msg);
    if let Ok(mut s) = SINK.lock() {
        if s.stderr {
            let _ = std::io::stderr().write_all(line.as_bytes());
        }
        if let Some(f) = s.file.as_mut() {
            let _ = f.write_all(line.as_bytes());
        }
    }
}

#[macro_export]
macro_rules! info {
    ($($arg:tt)*) => { $crate::log::write(&format!($($arg)*)) };
}

fn timestamp() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0) as i64;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}Z",
        y,
        m,
        d,
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}

/// Days since 1970-01-01 -> (year, month, day). Howard Hinnant's algorithm.
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = (if mp < 10 { mp + 3 } else { mp - 9 }) as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn civil_dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1));
        assert_eq!(civil_from_days(20_399), (2025, 11, 7));
    }
}
