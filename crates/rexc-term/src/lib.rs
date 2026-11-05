//! Terminal state for rexc, on libghostty-vt.
//!
//! A Rex terminal stream is a Ghostty snapshot followed by program output.
//! [`Terminal::restore`] rebuilds the exact terminal from the snapshot and
//! [`Terminal::write`] feeds the output through Ghostty's VT parser, so the
//! phone shows what the desktop shows, scrollback included. [`Terminal::frame`]
//! turns the viewport into style runs per row, with per-row dirty flags, so a
//! Canvas renderer only repaints what changed.

pub mod keys;
pub use keys::{KeyMods, key_from_name};

use libghostty_vt as vt;
use libghostty_vt::render::{CellIterator, CursorVisualStyle, Dirty, RenderState, RowIterator};
use libghostty_vt::screen::CellWide;
use libghostty_vt::selection::{FormatOptions, SelectLineOptions, SelectWordOptions, Selection};
use libghostty_vt::style::{Palette, RgbColor, StyleColor};
use libghostty_vt::terminal::{Point, PointCoordinate, ScrollViewport};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Hash)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgb {
    pub const fn new(r: u8, g: u8, b: u8) -> Self {
        Self { r, g, b }
    }

    /// `RRGGBB` or `#RRGGBB`.
    pub fn parse_hex(s: &str) -> Option<Self> {
        let s = s.trim().trim_start_matches('#');
        if s.len() != 6 {
            return None;
        }
        let v = u32::from_str_radix(s, 16).ok()?;
        Some(Self::new((v >> 16) as u8, (v >> 8) as u8, v as u8))
    }

    /// Packed ARGB, as Android's `Color` wants it.
    pub const fn argb(self) -> u32 {
        0xff00_0000 | (self.r as u32) << 16 | (self.g as u32) << 8 | self.b as u32
    }

    /// Relative luminance, to pick light or dark chrome around the terminal.
    pub fn luminance(self) -> f32 {
        let f = |c: u8| {
            let c = c as f32 / 255.0;
            if c <= 0.03928 { c / 12.92 } else { ((c + 0.055) / 1.055).powf(2.4) }
        };
        0.2126 * f(self.r) + 0.7152 * f(self.g) + 0.0722 * f(self.b)
    }
}

impl From<RgbColor> for Rgb {
    fn from(c: RgbColor) -> Self {
        Self { r: c.r, g: c.g, b: c.b }
    }
}

impl From<Rgb> for RgbColor {
    fn from(c: Rgb) -> Self {
        RgbColor { r: c.r, g: c.g, b: c.b }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Underline {
    #[default]
    None,
    Single,
    Double,
    Curly,
    Dotted,
    Dashed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CursorStyle {
    #[default]
    Block,
    Bar,
    Underline,
    HollowBlock,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cursor {
    pub col: u16,
    pub row: u16,
    pub style: CursorStyle,
    pub blinking: bool,
    /// Echo is off (a password prompt); draw a lock, not a caret.
    pub password: bool,
    pub wide: bool,
}

/// Cells on one row that share a style.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Run {
    pub col: u16,
    /// Cells the run covers (wide graphemes count 2).
    pub cells: u16,
    pub text: String,
    /// Width (1 or 2) of each grapheme in `text`, so a renderer that places
    /// glyphs one by one stays on the grid.
    pub widths: Vec<u8>,
    pub fg: Rgb,
    /// `None` is the terminal's default background: don't paint.
    pub bg: Option<Rgb>,
    pub bold: bool,
    pub italic: bool,
    pub faint: bool,
    pub underline: Underline,
    pub underline_color: Option<Rgb>,
    pub strikethrough: bool,
    pub overline: bool,
    pub selected: bool,
    pub hyperlink: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Row {
    pub runs: Vec<Run>,
    /// Changed since the last frame. Renderers with a cached bitmap repaint only these.
    pub dirty: bool,
}

impl Row {
    pub fn text(&self) -> String {
        self.runs.iter().map(|r| r.text.as_str()).collect()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Scrollbar {
    /// Rows in scrollback plus screen.
    pub total: u64,
    /// First visible row.
    pub offset: u64,
    /// Visible rows.
    pub len: u64,
}

impl Scrollbar {
    pub fn at_bottom(&self) -> bool {
        self.offset + self.len >= self.total
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Frame {
    pub cols: u16,
    pub rows: u16,
    pub background: Rgb,
    pub foreground: Rgb,
    pub cursor_color: Option<Rgb>,
    pub cursor: Option<Cursor>,
    pub lines: Vec<Row>,
    pub scrollbar: Scrollbar,
    pub alternate_screen: bool,
    pub mouse_tracking: bool,
    /// False when nothing changed since the previous frame.
    pub changed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TermError {
    Ghostty(String),
    Snapshot(String),
}


impl std::fmt::Display for TermError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Ghostty(e) => write!(f, "libghostty-vt: {e}"),
            Self::Snapshot(e) => write!(f, "snapshot could not be restored: {e}"),
        }
    }
}
impl std::error::Error for TermError {}

impl From<vt::Error> for TermError {
    fn from(e: vt::Error) -> Self {
        TermError::Ghostty(e.to_string())
    }
}

/// One emulated terminal.
pub struct Terminal {
    raw: vt::Terminal<'static, 'static>,
    render: RenderState<'static>,
    rows_it: RowIterator<'static>,
    cells_it: CellIterator<'static>,
    cell_px: (u32, u32),
    scrollback_bytes: usize,
    ansi: Option<[Rgb; 16]>,
    first_frame: bool,
}

// libghostty-vt isn't thread-safe for concurrent use, but every method takes
// `&self`/`&mut self`, so moving a whole terminal between threads behind the
// engine's lock is fine.
unsafe impl Send for Terminal {}

impl Terminal {
    pub fn new(cols: u16, rows: u16) -> Self {
        let raw = vt::Terminal::new(cols.max(1), rows.max(1)).expect("libghostty-vt terminal");
        let mut t = Self {
            raw,
            render: RenderState::new().expect("render state"),
            rows_it: RowIterator::new().expect("row iterator"),
            cells_it: CellIterator::new().expect("cell iterator"),
            cell_px: (0, 0),
            scrollback_bytes: 8 << 20,
            ansi: None,
            first_frame: true,
        };
        t.apply_options();
        t
    }

    fn apply_options(&mut self) {
        let _ = self.raw.set_scrollback_max_bytes(Some(self.scrollback_bytes));
        let ansi = self.ansi;
        self.apply_ansi(ansi.as_ref());
    }

    /// Cap scrollback memory; phones want this lower than desktops.
    pub fn set_scrollback_limit(&mut self, bytes: usize) {
        self.scrollback_bytes = bytes;
        let _ = self.raw.set_scrollback_max_bytes(Some(bytes));
    }

    /// Replace the whole state with a Ghostty snapshot.
    pub fn restore(&mut self, snapshot: &[u8]) -> Result<(), TermError> {
        let decoder = vt::snapshot::Decoder::new_buf(snapshot).map_err(|e| TermError::Snapshot(e.to_string()))?;
        self.raw = decoder.decode().map_err(|e| TermError::Snapshot(e.to_string()))?;
        self.render = RenderState::new()?;
        self.first_frame = true;
        self.apply_options();
        if self.cell_px != (0, 0) {
            let (c, r) = self.size();
            let (w, h) = self.cell_px;
            let _ = self.raw.resize(c, r, w, h);
        }
        Ok(())
    }

    /// Encode the state as a Ghostty snapshot (tests, handoff).
    pub fn snapshot(&mut self) -> Result<Vec<u8>, TermError> {
        let mut out = Vec::new();
        self.raw.encode_snapshot(&mut out)?;
        Ok(out)
    }

    pub fn write(&mut self, bytes: &[u8]) {
        self.raw.vt_write(bytes);
    }

    pub fn resize(&mut self, cols: u16, rows: u16, cell_w_px: u32, cell_h_px: u32) {
        self.cell_px = (cell_w_px, cell_h_px);
        let _ = self.raw.resize(cols.max(1), rows.max(1), cell_w_px, cell_h_px);
    }

    pub fn size(&self) -> (u16, u16) {
        (self.raw.cols().unwrap_or(80), self.raw.rows().unwrap_or(24))
    }

    pub fn title(&self) -> Option<String> {
        self.raw.title().ok().filter(|t| !t.is_empty()).map(|t| String::from_utf8_lossy(t).into_owned())
    }

    pub fn pwd(&self) -> Option<String> {
        self.raw.pwd().ok().filter(|t| !t.is_empty()).map(|t| String::from_utf8_lossy(t).into_owned())
    }

    pub fn mouse_tracking(&self) -> bool {
        self.raw.is_mouse_tracking().unwrap_or(false)
    }

    pub fn alternate_screen(&self) -> bool {
        matches!(self.raw.active_screen(), Ok(vt::screen::Screen::Alternate))
    }

    pub fn scrollbar(&self) -> Scrollbar {
        self.raw.scrollbar().map(|s| Scrollbar { total: s.total, offset: s.offset, len: s.len }).unwrap_or_default()
    }

    /// Scroll the viewport `delta` rows; negative goes up into history.
    pub fn scroll(&mut self, delta: isize) {
        self.raw.scroll_viewport(ScrollViewport::Delta(delta));
    }

    pub fn scroll_to_row(&mut self, row: usize) {
        self.raw.scroll_viewport(ScrollViewport::Row(row));
    }

    /// Jump by shell prompts (needs shell integration).
    pub fn scroll_prompts(&mut self, delta: isize) {
        self.raw.scroll_viewport(ScrollViewport::DeltaPrompt(delta));
    }

    pub fn scroll_to_bottom(&mut self) {
        self.raw.scroll_viewport(ScrollViewport::Bottom);
    }

    pub fn scroll_to_top(&mut self) {
        self.raw.scroll_viewport(ScrollViewport::Top);
    }

    fn viewport(col: u16, row: u16) -> Point {
        Point::Viewport(PointCoordinate { x: col, y: row as u32 })
    }

    /// Select from one viewport cell to another, inclusive.
    pub fn select(&mut self, from: (u16, u16), to: (u16, u16)) -> bool {
        let (Ok(a), Ok(b)) = (self.raw.grid_ref(Self::viewport(from.0, from.1)), self.raw.grid_ref(Self::viewport(to.0, to.1))) else {
            return false;
        };
        let sel = Selection::new(a, b, false);
        self.raw.set_selection(Some(&sel)).is_ok()
    }

    pub fn select_word(&mut self, col: u16, row: u16) -> bool {
        let Ok(r) = self.raw.grid_ref(Self::viewport(col, row)) else { return false };
        matches!(self.raw.select_word(SelectWordOptions::new(r)), Ok(Some(sel)) if self.raw.set_selection(Some(&sel)).is_ok())
    }

    pub fn select_line(&mut self, row: u16) -> bool {
        let Ok(r) = self.raw.grid_ref(Self::viewport(0, row)) else { return false };
        matches!(self.raw.select_line(SelectLineOptions::new(r)), Ok(Some(sel)) if self.raw.set_selection(Some(&sel)).is_ok())
    }

    pub fn clear_selection(&mut self) {
        let _ = self.raw.set_selection(None);
    }

    pub fn has_selection(&self) -> bool {
        matches!(self.raw.selection(), Ok(Some(_)))
    }

    /// The selected text, unwrapped and trimmed.
    pub fn selection_text(&self) -> Option<String> {
        let opts = || FormatOptions::new().with_emit_format(vt::fmt::Format::Plain).with_unwrap(true).with_trim(true);
        let mut buf = vec![0u8; 4096];
        let n = match self.raw.format_selection_buf(opts(), &mut buf) {
            Ok(Some(n)) => n,
            Ok(None) => return None,
            Err(vt::Error::OutOfSpace { required }) => {
                buf.resize(required, 0);
                self.raw.format_selection_buf(opts(), &mut buf).ok().flatten()?
            }
            Err(_) => return None,
        };
        let s = String::from_utf8_lossy(&buf[..n]).into_owned();
        (!s.is_empty()).then_some(s)
    }

    /// Scrollback and screen as plain text.
    pub fn all_text(&mut self) -> String {
        let ok = matches!(self.raw.select_all(), Ok(Some(sel)) if self.raw.set_selection(Some(&sel)).is_ok());
        if !ok {
            return String::new();
        }
        let text = self.selection_text().unwrap_or_default();
        self.clear_selection();
        text
    }

    /// The OSC 8 hyperlink under a viewport cell.
    pub fn hyperlink_at(&self, col: u16, row: u16) -> Option<String> {
        let r = self.raw.grid_ref(Self::viewport(col, row)).ok()?;
        let mut buf = vec![0u8; 2048];
        let n = match r.hyperlink_uri(&mut buf) {
            Ok(n) => n,
            Err(vt::Error::OutOfSpace { required }) => {
                buf.resize(required, 0);
                r.hyperlink_uri(&mut buf).ok()?
            }
            Err(_) => return None,
        };
        (n > 0).then(|| String::from_utf8_lossy(&buf[..n]).into_owned())
    }

    /// Bytes to send for a wheel step at a cell, when the program tracks the
    /// mouse (vim, less, htop). `None` when it doesn't: scroll history instead.
    pub fn wheel(&self, col: u16, row: u16, down: bool) -> Option<Vec<u8>> {
        if !self.mouse_tracking() {
            return None;
        }
        let (cols, rows) = self.size();
        let (cw, ch) = (self.cell_px.0.max(1), self.cell_px.1.max(1));
        let mut enc = vt::mouse::Encoder::new().ok()?;
        enc.set_options_from_terminal(&self.raw);
        enc.set_size(vt::mouse::EncoderSize {
            screen_width: cols as u32 * cw,
            screen_height: rows as u32 * ch,
            cell_width: cw,
            cell_height: ch,
            padding_top: 0,
            padding_bottom: 0,
            padding_right: 0,
            padding_left: 0,
        });
        let mut ev = vt::mouse::Event::new().ok()?;
        ev.set_action(vt::mouse::Action::Press);
        ev.set_button(Some(if down { vt::mouse::Button::Five } else { vt::mouse::Button::Four }));
        ev.set_position(vt::mouse::Position { x: (col.min(cols.saturating_sub(1)) as f32 + 0.5) * cw as f32, y: (row.min(rows.saturating_sub(1)) as f32 + 0.5) * ch as f32 });
        let mut out = Vec::with_capacity(16);
        enc.encode_to_vec(&ev, &mut out).ok()?;
        (!out.is_empty()).then_some(out)
    }

    /// Replace the 16 ANSI colors (a theme); `None` restores the built-in ones.
    pub fn set_ansi_palette(&mut self, ansi: Option<[Rgb; 16]>) {
        self.ansi = ansi;
        self.apply_ansi(ansi.as_ref());
    }

    fn apply_ansi(&mut self, ansi: Option<&[Rgb; 16]>) {
        let _ = self.raw.set_default_color_palette(None);
        let Some(ansi) = ansi else { return };
        let Ok(Palette(mut pal)) = self.raw.default_color_palette() else { return };
        for (i, c) in ansi.iter().enumerate() {
            pal[i] = (*c).into();
        }
        let _ = self.raw.set_default_color_palette(Some(Palette(pal)));
    }

    /// Default colors from the stream's `theme` message, or a theme of our own.
    pub fn set_default_colors(&mut self, fg: Option<Rgb>, bg: Option<Rgb>, cursor: Option<Rgb>) {
        let _ = self.raw.set_default_fg_color(fg.map(Into::into));
        let _ = self.raw.set_default_bg_color(bg.map(Into::into));
        let _ = self.raw.set_default_cursor_color(cursor.map(Into::into));
    }

    /// Apply a Rex `theme` message: hex colors plus `index=RRGGBB` palette entries.
    pub fn apply_theme(&mut self, foreground: Option<&str>, background: Option<&str>, cursor: Option<&str>, palette: &[String]) {
        self.set_default_colors(foreground.and_then(Rgb::parse_hex), background.and_then(Rgb::parse_hex), cursor.and_then(Rgb::parse_hex));
        let ansi = self.ansi;
        self.apply_ansi(ansi.as_ref());
        if palette.is_empty() {
            return;
        }
        let Ok(Palette(mut pal)) = self.raw.default_color_palette() else { return };
        for entry in palette {
            let Some((i, hex)) = entry.split_once('=') else { continue };
            let (Ok(i), Some(c)) = (i.trim().parse::<usize>(), Rgb::parse_hex(hex)) else { continue };
            if i < 256 {
                pal[i] = c.into();
            }
        }
        let _ = self.raw.set_default_color_palette(Some(Palette(pal)));
    }

    /// Build a drawable frame of the viewport. Rows carry `dirty`; `changed`
    /// is false when the terminal hasn't changed since the last call.
    pub fn frame(&mut self) -> Frame {
        let scrollbar = self.scrollbar();
        let alternate_screen = self.alternate_screen();
        let mouse_tracking = self.mouse_tracking();
        let empty = |changed| Frame {
            cols: 0,
            rows: 0,
            background: Rgb::default(),
            foreground: Rgb::new(255, 255, 255),
            cursor_color: None,
            cursor: None,
            lines: Vec::new(),
            scrollbar,
            alternate_screen,
            mouse_tracking,
            changed,
        };
        let Ok(snapshot) = self.render.update(&self.raw) else { return empty(false) };
        let dirty = snapshot.dirty().unwrap_or(Dirty::Full);
        let full = self.first_frame || dirty == Dirty::Full;
        self.first_frame = false;
        let cols = snapshot.cols().unwrap_or(0);
        let rows = snapshot.rows().unwrap_or(0);
        let colors = snapshot.colors().ok();
        let fg_default: Rgb = colors.as_ref().map(|c| c.foreground.into()).unwrap_or(Rgb::new(255, 255, 255));
        let bg_default: Rgb = colors.as_ref().map(|c| c.background.into()).unwrap_or_default();
        let palette = |i: u8| -> Rgb { colors.as_ref().map(|c| c.palette[i as usize].into()).unwrap_or_default() };
        let cur = snapshot.cursor().ok();

        let mut lines = Vec::with_capacity(rows as usize);
        if let Ok(mut row_it) = self.rows_it.update(&snapshot) {
            while let Some(row_iter) = row_it.next() {
                let row_dirty = full || row_iter.dirty().unwrap_or(true);
                let mut row = Row { runs: Vec::new(), dirty: row_dirty };
                let mut col: u16 = 0;
                let Ok(mut cell_it) = self.cells_it.update(row_iter) else {
                    lines.push(row);
                    continue;
                };
                while let Some(cell) = cell_it.next() {
                    let this_col = col;
                    col += 1;
                    let raw_cell = cell.raw_cell().ok();
                    let wide = raw_cell.and_then(|c| c.wide().ok()).unwrap_or(CellWide::Narrow);
                    if matches!(wide, CellWide::SpacerTail | CellWide::SpacerHead) {
                        continue;
                    }
                    let width: u8 = if matches!(wide, CellWide::Wide) { 2 } else { 1 };
                    let Ok(style) = cell.style() else { continue };
                    let selected = cell.is_selected().unwrap_or(false);
                    let hyperlink = raw_cell.and_then(|c| c.has_hyperlink().ok()).unwrap_or(false);
                    let mut fg: Rgb = cell.fg_color().ok().flatten().map(Into::into).unwrap_or(fg_default);
                    let mut bg: Option<Rgb> = cell.bg_color().ok().flatten().map(Into::into);
                    if style.inverse {
                        let new_fg = bg.unwrap_or(bg_default);
                        bg = Some(fg);
                        fg = new_fg;
                    }
                    if style.invisible {
                        fg = bg.unwrap_or(bg_default);
                    }
                    let mut text = String::new();
                    match cell.graphemes() {
                        Ok(g) if !g.is_empty() => text.extend(g),
                        _ => text.push(' '),
                    }
                    let underline = match style.underline {
                        vt::style::Underline::None => Underline::None,
                        vt::style::Underline::Single => Underline::Single,
                        vt::style::Underline::Double => Underline::Double,
                        vt::style::Underline::Curly => Underline::Curly,
                        vt::style::Underline::Dotted => Underline::Dotted,
                        vt::style::Underline::Dashed => Underline::Dashed,
                        _ => Underline::Single,
                    };
                    let underline_color = match style.underline_color {
                        StyleColor::Rgb(c) => Some(c.into()),
                        StyleColor::Palette(i) => Some(palette(i.0)),
                        _ => None,
                    };
                    let same = row.runs.last().is_some_and(|r: &Run| {
                        r.col + r.cells == this_col
                            && r.fg == fg
                            && r.bg == bg
                            && r.bold == style.bold
                            && r.italic == style.italic
                            && r.faint == style.faint
                            && r.underline == underline
                            && r.underline_color == underline_color
                            && r.strikethrough == style.strikethrough
                            && r.overline == style.overline
                            && r.selected == selected
                            && r.hyperlink == hyperlink
                    });
                    if same {
                        let r = row.runs.last_mut().unwrap();
                        r.text.push_str(&text);
                        r.widths.push(width);
                        r.cells += width as u16;
                    } else {
                        row.runs.push(Run {
                            col: this_col,
                            cells: width as u16,
                            text,
                            widths: vec![width],
                            fg,
                            bg,
                            bold: style.bold,
                            italic: style.italic,
                            faint: style.faint,
                            underline,
                            underline_color,
                            strikethrough: style.strikethrough,
                            overline: style.overline,
                            selected,
                            hyperlink,
                        });
                    }
                }
                lines.push(row);
            }
        }
        let _ = snapshot.clean();

        let cursor = cur.and_then(|c| {
            let vp = c.viewport?;
            c.visible.then_some(Cursor {
                col: vp.x,
                row: vp.y,
                style: match c.visual_style {
                    CursorVisualStyle::Bar => CursorStyle::Bar,
                    CursorVisualStyle::Underline => CursorStyle::Underline,
                    CursorVisualStyle::BlockHollow => CursorStyle::HollowBlock,
                    _ => CursorStyle::Block,
                },
                blinking: c.blinking,
                password: c.password_input,
                wide: vp.at_wide_tail,
            })
        });

        Frame {
            cols,
            rows,
            background: bg_default,
            foreground: fg_default,
            cursor_color: colors.as_ref().and_then(|c| c.cursor).map(Into::into),
            cursor,
            lines,
            scrollbar,
            alternate_screen,
            mouse_tracking,
            changed: full || dirty != Dirty::Clean,
        }
    }

    /// The viewport as trimmed text lines.
    pub fn screen_lines(&mut self) -> Vec<String> {
        self.frame().lines.iter().map(|r| r.text().trim_end().to_owned()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_and_frames() {
        let mut t = Terminal::new(20, 4);
        t.write(b"hi \x1b[1;31mred\x1b[0m\r\n\xe4\xbd\xa0ok");
        let f = t.frame();
        assert_eq!((f.cols, f.rows), (20, 4));
        assert!(f.changed);
        let r0 = &f.lines[0].runs;
        assert_eq!(r0[0].text, "hi ");
        let red = r0.iter().find(|r| r.text == "red").unwrap();
        assert!(red.bold);
        assert_ne!(red.fg, f.foreground);
        let r1 = &f.lines[1].runs[0];
        assert_eq!(r1.widths[0], 2, "CJK is wide");
        assert!(r1.text.starts_with("你ok"));
        let c = f.cursor.unwrap();
        assert_eq!((c.col, c.row), (4, 1));
        let again = t.frame();
        assert!(!again.changed, "nothing changed between frames");
        assert!(again.lines.iter().all(|l| !l.dirty));
        t.write(b"x");
        let third = t.frame();
        assert!(third.changed);
        assert!(third.lines[1].dirty);
        assert!(!third.lines[3].dirty);
    }

    #[test]
    fn snapshot_restores_exactly() {
        let mut src = Terminal::new(30, 5);
        src.write(b"$ echo restored\r\nrestored\r\n$ ");
        let snap = src.snapshot().unwrap();
        assert!(snap.starts_with(b"GHOSTSNP"));
        let mut dst = Terminal::new(80, 24);
        dst.restore(&snap).unwrap();
        dst.write(b"live!");
        let lines = dst.screen_lines();
        assert_eq!(lines[0], "$ echo restored");
        assert_eq!(lines[1], "restored");
        assert_eq!(lines[2], "$ live!");
        assert_eq!(dst.size(), (30, 5));
    }

    #[test]
    fn theme_and_palette() {
        let mut t = Terminal::new(10, 2);
        let mut ansi = [Rgb::new(1, 2, 3); 16];
        ansi[1] = Rgb::new(0xff, 0x00, 0x7f);
        t.set_ansi_palette(Some(ansi));
        t.write(b"\x1b[31mX");
        assert_eq!(t.frame().lines[0].runs[0].fg, Rgb::new(0xff, 0x00, 0x7f));
        t.apply_theme(Some("#112233"), Some("445566"), None, &["1=00ff00".into()]);
        let f = t.frame();
        assert_eq!(f.background, Rgb::new(0x44, 0x55, 0x66));
        assert_eq!(f.lines[0].runs[0].fg, Rgb::new(0, 0xff, 0));
        t.set_ansi_palette(None);
        t.apply_theme(None, None, None, &[]);
        assert_ne!(t.frame().lines[0].runs[0].fg, Rgb::new(0, 0xff, 0));
    }

    #[test]
    fn selection_scrollback_and_links() {
        let mut t = Terminal::new(12, 3);
        for i in 0..20 {
            t.write(format!("line{i}\r\n").as_bytes());
        }
        let sb = t.scrollbar();
        assert!(sb.total > sb.len && sb.at_bottom());
        t.scroll(-5);
        assert!(!t.scrollbar().at_bottom());
        t.scroll_to_bottom();
        assert!(t.select_word(1, 0));
        assert_eq!(t.selection_text().as_deref(), Some("line18"));
        assert!(t.select_line(1));
        assert_eq!(t.selection_text().as_deref(), Some("line19"));
        assert!(t.all_text().contains("line0\nline1\n"));
        t.write(b"\x1b]8;;https://rex.app\x1b\\doc\x1b]8;;\x1b\\");
        let f = t.frame();
        let link = f.lines[2].runs.iter().find(|r| r.text == "doc").unwrap();
        assert!(link.hyperlink);
        assert_eq!(t.hyperlink_at(1, 2).as_deref(), Some("https://rex.app"));
        assert!(t.hyperlink_at(10, 2).is_none());
    }

    #[test]
    fn wheel_only_when_tracked() {
        let mut t = Terminal::new(10, 3);
        t.resize(10, 3, 8, 16);
        assert!(t.wheel(0, 0, true).is_none());
        t.write(b"\x1b[?1000h\x1b[?1006h");
        let bytes = t.wheel(2, 1, true).unwrap();
        assert_eq!(bytes, b"\x1b[<65;3;2M");
        assert!(t.frame().mouse_tracking);
    }
}
