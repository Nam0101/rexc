//! Plain URLs and words in terminal text: what a tap opens and what a double tap selects.
//!
//! Both read a logical line, soft-wrapped rows joined, as one char per cell
//! (a wide character once), so a char index maps straight back to a cell.

use std::ops::Range;

/// The longest plain URL read, in bytes.
const MAX_URL_BYTES: usize = 8192;

/// The schemes read from plain text. Any other is reachable only through OSC 8.
const SCHEMES: [&str; 3] = ["http://", "https://", "mailto:"];

// Adapted from sheprd (Apache-2.0): crates/sheprd-gpui/src/terminal/links.rs
/// The range of the plain URL in `chars` that covers index `hit`, trimmed of
/// the prose punctuation around it. The web's schemes count wherever they
/// stand; `mailto:` only at the start of a word.
pub fn url_in(chars: &[char], hit: usize) -> Option<Range<usize>> {
    for start in 0..=hit.min(chars.len()) {
        let Some(scheme) = SCHEMES.iter().find(|s| starts_with(chars, start, s)) else { continue };
        if !scheme.starts_with("http") && start > 0 && chars[start - 1].is_alphanumeric() {
            continue;
        }
        let mut end = chars[start..].iter().position(|&c| ends_url(c)).map_or(chars.len(), |n| start + n);
        while end > start && matches!(chars[end - 1], '.' | ',' | ';' | ':' | '!' | '?') {
            end -= 1;
        }
        // A closing bracket without its opening one closes the prose around
        // the URL, as in `(see https://x.y/z)`; a balanced one is part of it,
        // as in `https://en.wikipedia.org/wiki/Foo_(bar)`.
        for (open, close) in [('(', ')'), ('[', ']'), ('{', '}')] {
            let count = |c: char| chars[start..end].iter().filter(|&&t| t == c).count();
            let mut excess = count(close).saturating_sub(count(open));
            while excess > 0 && chars[end - 1] == close {
                end -= 1;
                excess -= 1;
            }
        }
        // A scheme with nothing after it names nothing (schemes are ASCII: chars are bytes).
        if end - start <= scheme.len() {
            continue;
        }
        if hit < end && chars[start..end].iter().map(|c| c.len_utf8()).sum::<usize>() <= MAX_URL_BYTES {
            return Some(start..end);
        }
    }
    None
}

fn starts_with(chars: &[char], at: usize, s: &str) -> bool {
    let mut i = at;
    s.chars().all(|c| {
        i += 1;
        chars.get(i - 1) == Some(&c)
    })
}

/// Characters a plain URL never holds.
fn ends_url(c: char) -> bool {
    c.is_whitespace() || c.is_control() || matches!(c, '<' | '>' | '"' | '\'' | '`')
}

// Adapted from sheprd (Apache-2.0): crates/sheprd-gpui/src/terminal/selection.rs
/// Characters that end a word even though they are printed: quotes, brackets,
/// and the separators and box-drawing borders between words in terminal output.
pub fn separates(c: char) -> bool {
    c.is_whitespace()
        || c.is_control()
        || matches!(c, '"' | '\'' | '`' | '(' | ')' | '[' | ']' | '{' | '}' | '<' | '>' | '|' | ',' | ';')
        || ('\u{2500}'..='\u{259f}').contains(&c)
}

// Adapted from sheprd (Apache-2.0): crates/sheprd-gpui/src/terminal/links/path.rs
/// The range of the word around index `hit`: it runs to the nearest separator,
/// so `src/main.rs:12:5`, `~/work/rexc` and `--flag=value` come out whole, and
/// a backslash escapes the character after it (`/tmp/a\ b` is one word).
/// Punctuation closing a sentence is dropped unless it is what was tapped.
/// `None` on a separator.
pub fn word_in(chars: &[char], hit: usize) -> Option<Range<usize>> {
    let (mut start, mut end) = (0, chars.len());
    let mut i = 0;
    while i < chars.len() {
        if chars[i] == '\\' {
            i += 2;
            continue;
        }
        if separates(chars[i]) {
            if i >= hit {
                end = i;
                break;
            }
            start = i + 1;
        }
        i += 1;
    }
    while end > hit + 1 && matches!(chars[end - 1], '.' | ':' | '!' | '?') && chars[end - 2] != '\\' {
        end -= 1;
    }
    (start..end).contains(&hit).then_some(start..end)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn url(text: &str, hit: usize) -> Option<String> {
        let chars: Vec<char> = text.chars().collect();
        url_in(&chars, hit).map(|r| chars[r].iter().collect())
    }

    fn word(text: &str, hit: usize) -> Option<String> {
        let chars: Vec<char> = text.chars().collect();
        word_in(&chars, hit).map(|r| chars[r].iter().collect())
    }

    #[test]
    fn finds_urls_under_a_column() {
        let line = "see https://rex.app/docs, and (http://x.io/a?b=1).";
        assert_eq!(url(line, 4).as_deref(), Some("https://rex.app/docs"));
        assert_eq!(url(line, 23).as_deref(), Some("https://rex.app/docs"));
        assert_eq!(url(line, 24), None);
        assert_eq!(url(line, 35).as_deref(), Some("http://x.io/a?b=1"));
        assert_eq!(url("no links", 2), None);
    }

    #[test]
    fn trims_prose_punctuation_and_unbalanced_brackets() {
        let cases = [
            ("https://en.wikipedia.org/wiki/Foo_(bar)", Some("https://en.wikipedia.org/wiki/Foo_(bar)")),
            ("(see https://x.y/z)", Some("https://x.y/z")),
            ("(see https://x.y/z).", Some("https://x.y/z")),
            ("[https://x.y/a[1]]", Some("https://x.y/a[1]")),
            ("{https://x.y/z}", Some("https://x.y/z")),
            ("https://x.y/z?!", Some("https://x.y/z")),
            ("https://x.y/z;", Some("https://x.y/z")),
            ("<https://x.y/z>", Some("https://x.y/z")),
            ("\"https://x.y/z\"", Some("https://x.y/z")),
            ("`https://x.y/z`", Some("https://x.y/z")),
            ("'https://x.y/z'", Some("https://x.y/z")),
            ("https://x.y/a|b", Some("https://x.y/a|b")),
            ("xhttps://x.y/z", Some("https://x.y/z")),
            ("https://", None),
            ("https://.", None),
        ];
        for (text, want) in cases {
            let hit = text.find("http").unwrap_or(0);
            assert_eq!(url(text, hit).as_deref(), want, "{text}");
        }
    }

    #[test]
    fn mailto_starts_a_word() {
        assert_eq!(url("mail mailto:me@x.io now", 6).as_deref(), Some("mailto:me@x.io"));
        assert_eq!(url("(mailto:me@x.io)", 1).as_deref(), Some("mailto:me@x.io"));
        assert_eq!(url("xmailto:me@x.io", 2), None);
        assert_eq!(url("mailto: me", 0), None);
    }

    #[test]
    fn caps_a_url_at_8_kib() {
        let long = format!("https://x.y/{}", "a".repeat(MAX_URL_BYTES));
        assert_eq!(url(&long, 0), None);
        let fits = format!("https://x.y/{}", "a".repeat(MAX_URL_BYTES - 12));
        assert_eq!(url(&fits, 0).map(|u| u.len()), Some(MAX_URL_BYTES));
    }

    #[test]
    fn words_run_to_the_nearest_separator() {
        let cases = [
            ("error at src/main.rs:12:5 here", 12, Some("src/main.rs:12:5")),
            ("cd ~/work/rexc", 5, Some("~/work/rexc")),
            ("run --flag=value now", 6, Some("--flag=value")),
            ("rm /tmp/a\\ b c", 5, Some("/tmp/a\\ b")),
            ("rm /tmp/a\\ b c", 10, Some("/tmp/a\\ b")),
            ("see foo.rs.", 5, Some("foo.rs")),
            ("see foo.rs.", 10, Some("foo.rs.")),
            ("done!?", 0, Some("done")),
            ("a\\. b", 0, Some("a\\.")),
            ("(x.rs)", 2, Some("x.rs")),
            ("│ cell │", 2, Some("cell")),
            ("a,b;c|d", 2, Some("b")),
            ("\"quoted\"", 3, Some("quoted")),
            ("two words", 3, None),
            ("x", 5, None),
        ];
        for (text, hit, want) in cases {
            assert_eq!(word(text, hit).as_deref(), want, "{text} @ {hit}");
        }
    }
}
