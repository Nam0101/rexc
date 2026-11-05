//! Terminal color themes in Ghostty's format, and the ones built in (see `themes/NOTICE.md`).

use crate::Rgb;

/// A terminal color theme. `None` leaves the emulator's own default.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Theme {
    pub foreground: Option<Rgb>,
    pub background: Option<Rgb>,
    pub cursor: Option<Rgb>,
    /// Parsed but not drawn: the renderer has no cursor-text color.
    pub cursor_text: Option<Rgb>,
    /// Parsed but not drawn: the renderer draws selections in its own color.
    pub selection_background: Option<Rgb>,
    pub selection_foreground: Option<Rgb>,
    /// `(index, color)` in file order; a later entry for the same index wins.
    pub palette: Vec<(u8, Rgb)>,
}

impl Theme {
    /// Parse a Ghostty theme: `key = value` lines, `#` comments. Unknown keys and
    /// malformed lines are skipped.
    // Adapted from sheprd (Apache-2.0): crates/sheprd-gpui/src/config/theme.rs
    pub fn parse(text: &str) -> Self {
        let mut theme = Self::default();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((key, value)) = line.split_once('=') else { continue };
            let value = value.trim();
            let color = || Rgb::parse_hex(value);
            match key.trim() {
                "foreground" => theme.foreground = color().or(theme.foreground),
                "background" => theme.background = color().or(theme.background),
                "cursor-color" => theme.cursor = color().or(theme.cursor),
                "cursor-text" => theme.cursor_text = color().or(theme.cursor_text),
                "selection-background" => theme.selection_background = color().or(theme.selection_background),
                "selection-foreground" => theme.selection_foreground = color().or(theme.selection_foreground),
                "palette" => {
                    let Some((i, hex)) = value.split_once('=') else { continue };
                    let (Ok(i), Some(c)) = (i.trim().parse::<u8>(), Rgb::parse_hex(hex)) else { continue };
                    theme.palette.push((i, c));
                }
                _ => {}
            }
        }
        theme
    }

    /// The color this theme gives palette entry `i`, if any.
    pub fn palette_color(&self, i: u8) -> Option<Rgb> {
        self.palette.iter().rev().find(|(j, _)| *j == i).map(|(_, c)| *c)
    }
}

macro_rules! builtin {
    ($($name:literal),* $(,)?) => {
        &[$(($name, include_str!(concat!("../themes/", $name)))),*]
    };
}

/// The built-in themes, by name, as Ghostty theme text.
const BUILTIN: &[(&str, &str)] = builtin![
    "Atom One Dark",
    "Atom One Light",
    "Ayu Mirage",
    "Catppuccin Frappe",
    "Catppuccin Macchiato",
    "Everforest Dark Med",
    "GitHub Dark Default",
    "GitHub Light Default",
    "Gruvbox Dark",
    "Gruvbox Light",
    "Gruvbox Material Dark",
    "Kanagawa Wave",
    "Material",
    "Monokai Classic",
    "Monokai Pro",
    "Monokai Pro Light",
    "Night Owl",
    "Nightfox",
    "One Half Dark",
    "One Half Light",
    "Rose Pine",
    "Rose Pine Dawn",
    "Rose Pine Moon",
    "Snazzy",
    "TokyoNight",
    "TokyoNight Moon",
    "TokyoNight Storm",
    "Tomorrow Night",
];

/// Names of the built-in themes, in picker order.
pub fn names() -> Vec<&'static str> {
    BUILTIN.iter().map(|(n, _)| *n).collect()
}

/// A built-in theme by its exact name.
pub fn builtin(name: &str) -> Option<Theme> {
    BUILTIN.iter().find(|(n, _)| *n == name).map(|(_, text)| Theme::parse(text))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_bundled_file() {
        let t = builtin("Atom One Dark").unwrap();
        assert_eq!(t.background, Some(Rgb::new(0x21, 0x25, 0x2b)));
        assert_eq!(t.foreground, Some(Rgb::new(0xab, 0xb2, 0xbf)));
        assert_eq!(t.cursor, Some(Rgb::new(0xab, 0xb2, 0xbf)));
        assert_eq!(t.cursor_text, Some(Rgb::new(0x21, 0x25, 0x2b)));
        assert_eq!(t.selection_background, Some(Rgb::new(0x32, 0x38, 0x44)));
        assert_eq!(t.palette.len(), 16);
        assert_eq!(t.palette_color(1), Some(Rgb::new(0xe0, 0x6c, 0x75)));
    }

    #[test]
    fn every_bundled_theme_has_colors_and_16_palette_entries() {
        assert_eq!(names().len(), 28);
        for name in names() {
            let t = builtin(name).unwrap();
            assert!(t.background.is_some() && t.foreground.is_some(), "{name}");
            assert!((0..16).all(|i| t.palette_color(i).is_some()), "{name}");
        }
        assert!(builtin("Nope").is_none());
    }

    #[test]
    fn skips_comments_unknown_keys_and_malformed_lines() {
        let text = "# comment\n\n\
            background = #010203\n\
            background = nothex\n\
            foreground = 1122\n\
            font-size = 12\n\
            just garbage\n\
            palette = 300=#ffffff\n\
            palette = 2=#zzzzzz\n\
            palette = 3\n\
            palette = 4=#0000ff\n\
            palette = 4 = #00ff00\n";
        let t = Theme::parse(text);
        assert_eq!(t.background, Some(Rgb::new(1, 2, 3)));
        assert_eq!(t.foreground, None);
        assert_eq!(t.palette_color(4), Some(Rgb::new(0, 0xff, 0)));
        assert_eq!(t.palette.len(), 2);
    }
}
