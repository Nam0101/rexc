//! Keys encoded the way Ghostty itself encodes them, from the terminal's own
//! modes (application cursor keys, kitty keyboard protocol, modifyOtherKeys),
//! so a phone key bar sends exactly what a desktop keyboard would.

use libghostty_vt as vt;
use libghostty_vt::key::{Action, Event, Key, Mods};

use crate::Terminal;

/// Modifier set for a key, in app terms.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct KeyMods {
    pub shift: bool,
    pub ctrl: bool,
    pub alt: bool,
    pub meta: bool,
}

impl KeyMods {
    fn to_vt(self) -> Mods {
        let mut m = Mods::empty();
        m.set(Mods::SHIFT, self.shift);
        m.set(Mods::CTRL, self.ctrl);
        m.set(Mods::ALT, self.alt);
        m.set(Mods::SUPER, self.meta);
        m
    }
}

/// A W3C key name in snake_case (`key_a`, `arrow_up`, `enter`) to Ghostty's key.
pub fn key_from_name(name: &str) -> Option<Key> {
    use Key::*;
    Some(match name {
        "backquote" => Backquote,
        "backslash" => Backslash,
        "bracket_left" => BracketLeft,
        "bracket_right" => BracketRight,
        "comma" => Comma,
        "digit_0" => Digit0,
        "digit_1" => Digit1,
        "digit_2" => Digit2,
        "digit_3" => Digit3,
        "digit_4" => Digit4,
        "digit_5" => Digit5,
        "digit_6" => Digit6,
        "digit_7" => Digit7,
        "digit_8" => Digit8,
        "digit_9" => Digit9,
        "equal" => Equal,
        "key_a" => A,
        "key_b" => B,
        "key_c" => C,
        "key_d" => D,
        "key_e" => E,
        "key_f" => F,
        "key_g" => G,
        "key_h" => H,
        "key_i" => I,
        "key_j" => J,
        "key_k" => K,
        "key_l" => L,
        "key_m" => M,
        "key_n" => N,
        "key_o" => O,
        "key_p" => P,
        "key_q" => Q,
        "key_r" => R,
        "key_s" => S,
        "key_t" => T,
        "key_u" => U,
        "key_v" => V,
        "key_w" => W,
        "key_x" => X,
        "key_y" => Y,
        "key_z" => Z,
        "minus" => Minus,
        "period" => Period,
        "quote" => Quote,
        "semicolon" => Semicolon,
        "slash" => Slash,
        "backspace" => Backspace,
        "enter" => Enter,
        "space" => Space,
        "tab" => Tab,
        "delete" => Delete,
        "end" => End,
        "home" => Home,
        "insert" => Insert,
        "page_down" => PageDown,
        "page_up" => PageUp,
        "arrow_down" => ArrowDown,
        "arrow_left" => ArrowLeft,
        "arrow_right" => ArrowRight,
        "arrow_up" => ArrowUp,
        "escape" => Escape,
        "f1" => F1,
        "f2" => F2,
        "f3" => F3,
        "f4" => F4,
        "f5" => F5,
        "f6" => F6,
        "f7" => F7,
        "f8" => F8,
        "f9" => F9,
        "f10" => F10,
        "f11" => F11,
        "f12" => F12,
        _ => return None,
    })
}

impl Terminal {
    /// Encode a key press for this terminal. `text` is what the key typed
    /// without modifiers (`"a"`, `"A"`, `""` for arrows); `codepoint` is the
    /// unshifted code point, which ctrl/alt chords are built from.
    pub fn encode_key(&self, name: &str, mods: KeyMods, text: &str, codepoint: Option<char>) -> Option<Vec<u8>> {
        let key = key_from_name(name)?;
        let mut ev = Event::new().ok()?;
        ev.set_action(Action::Press).set_key(key).set_mods(mods.to_vt());
        // Ctrl chords are built from the code point; Alt keeps the text so the encoder can prefix ESC.
        let typed = !(mods.ctrl || mods.meta) && !text.is_empty();
        if typed {
            ev.set_utf8(Some(text));
            if mods.shift {
                ev.set_consumed_mods(Mods::SHIFT);
            }
        }
        if let Some(c) = codepoint.or_else(|| text.chars().next()) {
            ev.set_unshifted_codepoint(c.to_ascii_lowercase());
        }
        let mut enc = vt::key::Encoder::new().ok()?;
        enc.set_options_from_terminal(&self.raw);
        // A phone has no Option-as-Alt dilemma: Alt always means Meta, so it always sends ESC first.
        enc.set_alt_esc_prefix(true);
        enc.set_macos_option_as_alt(vt::key::OptionAsAlt::True); // the desktop CLI and host tests run on macOS
        let mut out = Vec::with_capacity(16);
        enc.encode_to_vec(&ev, &mut out).ok()?;
        Some(out)
    }

    /// Encode pasted text, bracketed when the program asked for it.
    pub fn encode_paste(&self, text: &str) -> Vec<u8> {
        let bracketed = self.raw.mode(vt::terminal::Mode::BRACKETED_PASTE).unwrap_or(false);
        let mut data = text.as_bytes().to_vec();
        let mut buf = vec![0u8; data.len() + 16];
        match vt::paste::encode(&mut data, bracketed, &mut buf) {
            Ok(n) => {
                buf.truncate(n);
                buf
            }
            Err(vt::Error::OutOfSpace { required }) => {
                let mut buf = vec![0u8; required];
                let mut data = text.as_bytes().to_vec();
                match vt::paste::encode(&mut data, bracketed, &mut buf) {
                    Ok(n) => {
                        buf.truncate(n);
                        buf
                    }
                    Err(_) => text.as_bytes().to_vec(),
                }
            }
            Err(_) => text.as_bytes().to_vec(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_follow_terminal_modes() {
        let t = Terminal::new(10, 2);
        assert_eq!(t.encode_key("arrow_up", KeyMods::default(), "", None).unwrap(), b"\x1b[A");
        assert_eq!(t.encode_key("key_c", KeyMods { ctrl: true, ..Default::default() }, "c", Some('c')).unwrap(), b"\x03");
        assert_eq!(t.encode_key("key_a", KeyMods::default(), "a", Some('a')).unwrap(), b"a");
        assert_eq!(t.encode_key("key_a", KeyMods { shift: true, ..Default::default() }, "A", Some('a')).unwrap(), b"A");
        assert_eq!(t.encode_key("enter", KeyMods::default(), "", None).unwrap(), b"\r");
        assert_eq!(t.encode_key("escape", KeyMods::default(), "", None).unwrap(), b"\x1b");
        assert_eq!(t.encode_key("key_b", KeyMods { alt: true, ..Default::default() }, "b", Some('b')).unwrap(), b"\x1bb");
        assert!(t.encode_key("nope", KeyMods::default(), "", None).is_none());
        let mut t = t;
        t.write(b"\x1b[?1h");
        assert_eq!(t.encode_key("arrow_up", KeyMods::default(), "", None).unwrap(), b"\x1bOA");
    }

    #[test]
    fn paste_is_bracketed_on_request() {
        let mut t = Terminal::new(10, 2);
        // Unbracketed, a newline is turned into Enter so the shell runs the line instead of inserting a line feed.
        assert_eq!(t.encode_paste("hi\n"), b"hi\r");
        t.write(b"\x1b[?2004h");
        assert_eq!(t.encode_paste("hi"), b"\x1b[200~hi\x1b[201~");
    }
}
