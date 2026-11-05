//! Key events as `client.key` wants them: W3C key names, a `+`-joined modifier
//! string, and the text the key typed.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Mods {
    pub shift: bool,
    pub ctrl: bool,
    pub alt: bool,
    pub meta: bool,
}

impl Mods {
    pub const NONE: Mods = Mods { shift: false, ctrl: false, alt: false, meta: false };
    pub const CTRL: Mods = Mods { ctrl: true, ..Mods::NONE };
    pub const ALT: Mods = Mods { alt: true, ..Mods::NONE };
    pub const SHIFT: Mods = Mods { shift: true, ..Mods::NONE };

    pub fn any(self) -> bool {
        self.shift || self.ctrl || self.alt || self.meta
    }

    pub fn union(self, other: Mods) -> Mods {
        Mods { shift: self.shift || other.shift, ctrl: self.ctrl || other.ctrl, alt: self.alt || other.alt, meta: self.meta || other.meta }
    }

    /// Rex's order: shift, ctrl, alt, super.
    pub fn wire(self) -> String {
        let mut parts = Vec::with_capacity(4);
        if self.shift {
            parts.push("shift");
        }
        if self.ctrl {
            parts.push("ctrl");
        }
        if self.alt {
            parts.push("alt");
        }
        if self.meta {
            parts.push("super");
        }
        parts.join("+")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeyAction {
    Press,
    Release,
    Repeat,
}

/// One key, ready to serialize as the `key` argument of `client.key`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KeyEvent {
    pub key: String,
    pub mods: String,
    pub consumed_mods: String,
    pub text: String,
    pub unshifted_codepoint: u32,
    pub composing: bool,
    pub action: KeyAction,
}

impl KeyEvent {
    /// A named key that types nothing (arrows, escape, function keys, ...).
    pub fn named(key: &str, mods: Mods) -> Self {
        Self {
            key: key.into(),
            mods: mods.wire(),
            consumed_mods: String::new(),
            text: String::new(),
            unshifted_codepoint: 0,
            composing: false,
            action: KeyAction::Press,
        }
    }

    pub fn with_action(mut self, action: KeyAction) -> Self {
        self.action = action;
        self
    }

    /// The key that types `c` on a US layout, with extra modifiers. `shift`
    /// is marked consumed when it produced the character, so `A` is
    /// `key_a` + shift with text `A`, and ctrl+c is `key_c` + ctrl with no text.
    pub fn for_char(c: char, mods: Mods) -> Option<Self> {
        let (name, unshifted, shifted) = us_key(c)?;
        let typed_with_shift = shifted;
        let mods = if typed_with_shift { mods.union(Mods::SHIFT) } else { mods };
        let text = if mods.ctrl || mods.alt || mods.meta { String::new() } else { c.to_string() };
        let consumed = if typed_with_shift && !text.is_empty() { "shift".to_string() } else { String::new() };
        Some(Self {
            key: name.into(),
            mods: mods.wire(),
            consumed_mods: consumed,
            text,
            unshifted_codepoint: unshifted as u32,
            composing: false,
            action: KeyAction::Press,
        })
    }
}

/// `(key name, unshifted char, needs shift)` for a character on a US keyboard.
pub fn us_key(c: char) -> Option<(&'static str, char, bool)> {
    const LETTERS: [&str; 26] = [
        "key_a", "key_b", "key_c", "key_d", "key_e", "key_f", "key_g", "key_h", "key_i", "key_j", "key_k", "key_l", "key_m",
        "key_n", "key_o", "key_p", "key_q", "key_r", "key_s", "key_t", "key_u", "key_v", "key_w", "key_x", "key_y", "key_z",
    ];
    const DIGITS: [&str; 10] = ["digit_0", "digit_1", "digit_2", "digit_3", "digit_4", "digit_5", "digit_6", "digit_7", "digit_8", "digit_9"];
    const SHIFTED_DIGITS: &str = ")!@#$%^&*(";
    match c {
        'a'..='z' => Some((LETTERS[(c as u8 - b'a') as usize], c, false)),
        'A'..='Z' => Some((LETTERS[(c as u8 - b'A') as usize], c.to_ascii_lowercase(), true)),
        '0'..='9' => Some((DIGITS[(c as u8 - b'0') as usize], c, false)),
        ' ' => Some(("space", ' ', false)),
        '\r' | '\n' => Some(("enter", '\r', false)),
        '\t' => Some(("tab", '\t', false)),
        '\u{7f}' | '\u{8}' => Some(("backspace", '\u{8}', false)),
        '\u{1b}' => Some(("escape", '\u{1b}', false)),
        '-' => Some(("minus", '-', false)),
        '_' => Some(("minus", '-', true)),
        '=' => Some(("equal", '=', false)),
        '+' => Some(("equal", '=', true)),
        '[' => Some(("bracket_left", '[', false)),
        '{' => Some(("bracket_left", '[', true)),
        ']' => Some(("bracket_right", ']', false)),
        '}' => Some(("bracket_right", ']', true)),
        '\\' => Some(("backslash", '\\', false)),
        '|' => Some(("backslash", '\\', true)),
        ';' => Some(("semicolon", ';', false)),
        ':' => Some(("semicolon", ';', true)),
        '\'' => Some(("quote", '\'', false)),
        '"' => Some(("quote", '\'', true)),
        ',' => Some(("comma", ',', false)),
        '<' => Some(("comma", ',', true)),
        '.' => Some(("period", '.', false)),
        '>' => Some(("period", '.', true)),
        '/' => Some(("slash", '/', false)),
        '?' => Some(("slash", '/', true)),
        '`' => Some(("backquote", '`', false)),
        '~' => Some(("backquote", '`', true)),
        _ => {
            if let Some(i) = SHIFTED_DIGITS.find(c) {
                Some((DIGITS[i], char::from(b'0' + i as u8), true))
            } else {
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mods_follow_rex_order() {
        assert_eq!(Mods { shift: true, ctrl: true, alt: true, meta: true }.wire(), "shift+ctrl+alt+super");
        assert_eq!(Mods::NONE.wire(), "");
    }

    #[test]
    fn chars_become_keys() {
        let a = KeyEvent::for_char('A', Mods::NONE).unwrap();
        assert_eq!((a.key.as_str(), a.mods.as_str(), a.consumed_mods.as_str(), a.text.as_str()), ("key_a", "shift", "shift", "A"));
        assert_eq!(a.unshifted_codepoint, 'a' as u32);
        let ctrl_c = KeyEvent::for_char('c', Mods::CTRL).unwrap();
        assert_eq!((ctrl_c.key.as_str(), ctrl_c.mods.as_str(), ctrl_c.text.as_str()), ("key_c", "ctrl", ""));
        let bang = KeyEvent::for_char('!', Mods::NONE).unwrap();
        assert_eq!((bang.key.as_str(), bang.mods.as_str(), bang.text.as_str()), ("digit_1", "shift", "!"));
        assert!(KeyEvent::for_char('é', Mods::NONE).is_none());
        let json = serde_json::to_value(KeyEvent::named("arrow_up", Mods::NONE)).unwrap();
        assert_eq!(json["action"], "press");
        assert_eq!(json["unshifted_codepoint"], 0);
    }
}
