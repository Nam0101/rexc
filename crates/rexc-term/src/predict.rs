//! Mosh-style local echo, conservative: characters the user typed at the end of a
//! line are remembered with the time they were sent, and shown over the terminal only
//! once the server has been silent about them for [`SHOW_AFTER`]. On a fast link
//! the echo arrives first and nothing is ever shown.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

/// How long a typed character may stay unechoed before it is drawn.
pub const SHOW_AFTER: Duration = Duration::from_millis(80);
/// A prediction the server never confirms is dropped after this long.
pub const GIVE_UP_AFTER: Duration = Duration::from_millis(1500);
/// Most characters predicted ahead of the server; more means the link is stuck.
const MAX_PENDING: usize = 64;

/// Typed text drawn ahead of the server's echo, starting at a viewport cell.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Prediction {
    pub col: u16,
    pub row: u16,
    pub text: String,
}

#[derive(Debug, Default)]
pub struct Predictor {
    enabled: bool,
    /// Cell where `pending[0]` is expected to appear.
    anchor: (u16, u16),
    pending: VecDeque<(char, Instant)>,
}

impl Predictor {
    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        if !enabled {
            self.clear();
        }
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    pub fn is_pending(&self) -> bool {
        !self.pending.is_empty()
    }

    pub fn clear(&mut self) {
        self.pending.clear();
    }

    /// Whether `text` is something the shell will echo as-is, one cell per character.
    pub fn predictable(text: &str) -> bool {
        !text.is_empty() && text.chars().all(|c| c == ' ' || c.is_ascii_graphic())
    }

    /// The user typed `text` with the cursor at `cursor`. `cols` bounds the line:
    /// text that would wrap is not predicted. Returns whether a prediction is pending.
    pub fn typed(&mut self, text: &str, cursor: (u16, u16), cols: u16, now: Instant) -> bool {
        if !self.enabled || !Self::predictable(text) {
            self.clear();
            return false;
        }
        if self.pending.is_empty() {
            self.anchor = cursor;
        }
        let end = self.anchor.0 as usize + self.pending.len() + text.chars().count();
        if self.anchor.1 != cursor.1 || end >= cols as usize || self.pending.len() + text.len() > MAX_PENDING {
            self.clear();
            return false;
        }
        self.pending.extend(text.chars().map(|c| (c, now)));
        true
    }

    /// Output arrived and left the cursor at `cursor`. `cell_at` reads a viewport cell.
    /// Characters the cursor moved past are the server's echo: confirmed ones are dropped,
    /// anything else (a jump, a different character) drops every prediction.
    pub fn observed(&mut self, cursor: (u16, u16), cell_at: impl Fn(u16, u16) -> Option<char>) {
        if self.pending.is_empty() {
            return;
        }
        let (col, row) = cursor;
        let moved = col.checked_sub(self.anchor.0).map(usize::from);
        match moved {
            Some(k) if row == self.anchor.1 && k <= self.pending.len() => {
                let echoed = self.pending.iter().take(k).enumerate().all(|(i, (c, _))| cell_at(self.anchor.0 + i as u16, row) == Some(*c));
                if !echoed {
                    self.clear();
                } else if k > 0 {
                    self.pending.drain(..k);
                    self.anchor.0 += k as u16;
                }
            }
            _ => self.clear(),
        }
    }

    /// What to draw now: the unechoed prefix that has waited long enough, or nothing.
    pub fn visible(&mut self, now: Instant) -> Option<Prediction> {
        let oldest = self.pending.front()?.1;
        if now.duration_since(oldest) >= GIVE_UP_AFTER {
            self.clear();
            return None;
        }
        let text: String = self.pending.iter().take_while(|(_, at)| now.duration_since(*at) >= SHOW_AFTER).map(|(c, _)| *c).collect();
        (!text.is_empty()).then_some(Prediction { col: self.anchor.0, row: self.anchor.1, text })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn predictor() -> (Predictor, Instant) {
        let mut p = Predictor::default();
        p.set_enabled(true);
        (p, Instant::now())
    }

    fn ms(t: Instant, n: u64) -> Instant {
        t + Duration::from_millis(n)
    }

    #[test]
    fn shows_only_after_the_server_stays_silent() {
        let (mut p, t) = predictor();
        assert!(p.typed("ls", (2, 5), 40, t));
        assert_eq!(p.visible(ms(t, 79)), None, "a fast link never sees a prediction");
        assert_eq!(p.visible(ms(t, 80)), Some(Prediction { col: 2, row: 5, text: "ls".into() }));
    }

    #[test]
    fn echo_confirms_and_advances_the_anchor() {
        let (mut p, t) = predictor();
        p.typed("abc", (0, 0), 40, t);
        let screen = |c: u16, _r: u16| "abc".chars().nth(c as usize);
        p.observed((1, 0), screen);
        assert_eq!(p.visible(ms(t, 100)), Some(Prediction { col: 1, row: 0, text: "bc".into() }));
        p.observed((3, 0), screen);
        assert!(!p.is_pending());
    }

    #[test]
    fn output_that_leaves_the_cursor_is_not_an_echo() {
        let (mut p, t) = predictor();
        p.typed("a", (4, 2), 40, t);
        p.observed((4, 2), |_, _| None);
        assert!(p.is_pending(), "unrelated output that doesn't move the cursor keeps it");
        p.observed((0, 3), |_, _| None);
        assert!(!p.is_pending(), "cursor jump");
        p.typed("a", (4, 2), 40, t);
        p.observed((5, 2), |_, _| Some('z'));
        assert!(!p.is_pending(), "the server echoed something else");
        p.typed("a", (4, 2), 40, t);
        p.observed((3, 2), |_, _| None);
        assert!(!p.is_pending(), "cursor moved left");
    }

    #[test]
    fn gives_up_after_a_while() {
        let (mut p, t) = predictor();
        p.typed("a", (0, 0), 40, t);
        assert!(p.visible(ms(t, 1000)).is_some());
        assert_eq!(p.visible(ms(t, 1500)), None);
        assert!(!p.is_pending());
    }

    #[test]
    fn declines_what_the_shell_will_not_echo_plainly() {
        let (mut p, t) = predictor();
        assert!(!p.typed("é", (0, 0), 40, t));
        assert!(!p.typed("a\n", (0, 0), 40, t));
        assert!(!p.typed("abc", (38, 0), 40, t), "would wrap");
        p.set_enabled(false);
        assert!(!p.typed("a", (0, 0), 40, t));
    }

    #[test]
    fn a_new_line_starts_over_and_the_queue_is_capped() {
        let (mut p, t) = predictor();
        p.typed("a", (0, 0), 200, t);
        assert!(!p.typed("b", (1, 1), 200, t), "typed on another row than the pending text");
        assert!(!p.is_pending());
        for i in 0..64 {
            assert!(p.typed("x", (0, 0), 200, ms(t, i)));
        }
        assert!(!p.typed("x", (0, 0), 200, t));
        assert!(!p.is_pending());
    }
}
