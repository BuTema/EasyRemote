//! Binary input frames. See PROTOCOL.md section 3 — keep both in sync with the Android app.

pub const PROTO_VERSION: u32 = 1;
pub const MAX_TEXT_BYTES: usize = 256;

pub const OP_MOVE: u8 = 0x01;
pub const OP_SCROLL: u8 = 0x02;
pub const OP_BUTTON: u8 = 0x03;
pub const OP_KEY: u8 = 0x04;
pub const OP_VOLUME: u8 = 0x05;
pub const OP_MUTE: u8 = 0x06;
pub const OP_MEDIA: u8 = 0x07;
pub const OP_TEXT: u8 = 0x08;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Button {
    Left,
    Right,
    Middle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Release,
    Press,
    Click,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Key {
    Left,
    Right,
    Up,
    Down,
    Escape,
    Enter,
    Backspace,
    Space,
    Tab,
    Delete,
    Home,
    End,
    PageUp,
    PageDown,
    F11,
    /// the ordinary letter F (video players: fullscreen)
    F,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Media {
    PlayPause,
    Next,
    Prev,
    Stop,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Cmd {
    Move { dx: i32, dy: i32 },
    Scroll { dx: i32, dy: i32 },
    Button(Button, Action),
    Key(Key, Action),
    Volume(i8),
    Mute,
    Media(Media),
    Text(String),
}

impl Button {
    fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            0 => Button::Left,
            1 => Button::Right,
            2 => Button::Middle,
            _ => return None,
        })
    }
}

impl Action {
    fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            0 => Action::Release,
            1 => Action::Press,
            2 => Action::Click,
            _ => return None,
        })
    }
}

impl Key {
    pub const ALL: [Key; 16] = [
        Key::Left,
        Key::Right,
        Key::Up,
        Key::Down,
        Key::Escape,
        Key::Enter,
        Key::Backspace,
        Key::Space,
        Key::Tab,
        Key::Delete,
        Key::Home,
        Key::End,
        Key::PageUp,
        Key::PageDown,
        Key::F11,
        Key::F,
    ];

    /// Wire code, 1-based. Must match the table in PROTOCOL.md.
    pub fn code(self) -> u8 {
        Key::ALL.iter().position(|k| *k == self).unwrap() as u8 + 1
    }

    fn from_u8(v: u8) -> Option<Self> {
        if v == 0 {
            return None;
        }
        Key::ALL.get(v as usize - 1).copied()
    }
}

impl Media {
    fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            1 => Media::PlayPause,
            2 => Media::Next,
            3 => Media::Prev,
            4 => Media::Stop,
            _ => return None,
        })
    }
}

fn i16_at(p: &[u8], i: usize) -> i32 {
    i16::from_be_bytes([p[i], p[i + 1]]) as i32
}

/// Remove control characters (except newline and tab) so a client cannot inject odd key sequences.
fn sanitize_text(s: &str) -> String {
    s.chars()
        .filter(|c| !c.is_control() || *c == '\n' || *c == '\t')
        .collect()
}

pub fn parse(frame: &[u8]) -> Result<Cmd, &'static str> {
    let (&op, p) = frame.split_first().ok_or("empty frame")?;
    match op {
        OP_MOVE => {
            if p.len() != 4 {
                return Err("bad MOVE length");
            }
            Ok(Cmd::Move { dx: i16_at(p, 0), dy: i16_at(p, 2) })
        }
        OP_SCROLL => {
            if p.len() != 4 {
                return Err("bad SCROLL length");
            }
            Ok(Cmd::Scroll { dx: i16_at(p, 0), dy: i16_at(p, 2) })
        }
        OP_BUTTON => {
            if p.len() != 2 {
                return Err("bad BUTTON length");
            }
            match (Button::from_u8(p[0]), Action::from_u8(p[1])) {
                (Some(b), Some(a)) => Ok(Cmd::Button(b, a)),
                _ => Err("bad BUTTON value"),
            }
        }
        OP_KEY => {
            if p.len() != 2 {
                return Err("bad KEY length");
            }
            match (Key::from_u8(p[0]), Action::from_u8(p[1])) {
                (Some(k), Some(a)) => Ok(Cmd::Key(k, a)),
                _ => Err("bad KEY value"),
            }
        }
        OP_VOLUME => {
            if p.len() != 1 {
                return Err("bad VOLUME length");
            }
            Ok(Cmd::Volume((p[0] as i8).clamp(-20, 20)))
        }
        OP_MUTE => Ok(Cmd::Mute),
        OP_MEDIA => {
            if p.len() != 1 {
                return Err("bad MEDIA length");
            }
            Media::from_u8(p[0]).map(Cmd::Media).ok_or("bad MEDIA value")
        }
        OP_TEXT => {
            if p.len() > MAX_TEXT_BYTES {
                return Err("TEXT too long");
            }
            let s = std::str::from_utf8(p).map_err(|_| "TEXT is not UTF-8")?;
            Ok(Cmd::Text(sanitize_text(s)))
        }
        _ => Err("unknown opcode"),
    }
}

fn action_code(a: Action) -> u8 {
    match a {
        Action::Release => 0,
        Action::Press => 1,
        Action::Click => 2,
    }
}

/// Encoder, used by tests (the phone has its own implementation in Kotlin).
pub fn encode(cmd: &Cmd) -> Vec<u8> {
    let mut v = Vec::new();
    match cmd {
        Cmd::Move { dx, dy } => {
            v.push(OP_MOVE);
            v.extend_from_slice(&(*dx as i16).to_be_bytes());
            v.extend_from_slice(&(*dy as i16).to_be_bytes());
        }
        Cmd::Scroll { dx, dy } => {
            v.push(OP_SCROLL);
            v.extend_from_slice(&(*dx as i16).to_be_bytes());
            v.extend_from_slice(&(*dy as i16).to_be_bytes());
        }
        Cmd::Button(b, a) => {
            v.push(OP_BUTTON);
            v.push(match b {
                Button::Left => 0,
                Button::Right => 1,
                Button::Middle => 2,
            });
            v.push(action_code(*a));
        }
        Cmd::Key(k, a) => {
            v.push(OP_KEY);
            v.push(k.code());
            v.push(action_code(*a));
        }
        Cmd::Volume(s) => {
            v.push(OP_VOLUME);
            v.push(*s as u8);
        }
        Cmd::Mute => v.push(OP_MUTE),
        Cmd::Media(m) => {
            v.push(OP_MEDIA);
            v.push(match m {
                Media::PlayPause => 1,
                Media::Next => 2,
                Media::Prev => 3,
                Media::Stop => 4,
            });
        }
        Cmd::Text(s) => {
            v.push(OP_TEXT);
            v.extend_from_slice(s.as_bytes());
        }
    }
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let cmds = vec![
            Cmd::Move { dx: -5, dy: 300 },
            Cmd::Scroll { dx: 0, dy: -120 },
            Cmd::Button(Button::Right, Action::Click),
            Cmd::Button(Button::Left, Action::Press),
            Cmd::Key(Key::Escape, Action::Click),
            Cmd::Key(Key::F11, Action::Click),
            Cmd::Key(Key::F, Action::Click),
            Cmd::Volume(-1),
            Cmd::Mute,
            Cmd::Media(Media::PlayPause),
            Cmd::Text("héllo\n".to_string()),
        ];
        for c in cmds {
            assert_eq!(parse(&encode(&c)).unwrap(), c);
        }
    }

    #[test]
    fn key_codes_match_spec() {
        assert_eq!(Key::Left.code(), 1);
        assert_eq!(Key::Escape.code(), 5);
        assert_eq!(Key::Enter.code(), 6);
        assert_eq!(Key::Backspace.code(), 7);
        assert_eq!(Key::PageDown.code(), 14);
        assert_eq!(Key::F11.code(), 15);
        assert_eq!(Key::F.code(), 16);
    }

    #[test]
    fn rejects_garbage() {
        assert!(parse(&[]).is_err());
        assert!(parse(&[0x7f]).is_err());
        assert!(parse(&[OP_MOVE, 0, 1]).is_err());
        assert!(parse(&[OP_KEY, 0, 1]).is_err());
        assert!(parse(&[OP_KEY, 99, 1]).is_err());
        assert!(parse(&[OP_BUTTON, 3, 1]).is_err());
        assert!(parse(&[OP_MEDIA, 9]).is_err());
        assert!(parse(&[OP_TEXT, 0xff, 0xfe]).is_err());
        assert!(parse(&vec![OP_TEXT; MAX_TEXT_BYTES + 2]).is_err());
    }

    #[test]
    fn text_is_sanitized_and_volume_clamped() {
        assert_eq!(parse(&[OP_TEXT, b'a', 0x1b, b'b']).unwrap(), Cmd::Text("ab".into()));
        assert_eq!(parse(&[OP_VOLUME, 100]).unwrap(), Cmd::Volume(20));
        assert_eq!(parse(&[OP_VOLUME, 0x80]).unwrap(), Cmd::Volume(-20));
    }
}
