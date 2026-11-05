//! Terminal state for rexc, on libghostty-vt.
//!
//! A Rex terminal stream is a Ghostty snapshot followed by program output.
//! [`Terminal::restore`] rebuilds the exact terminal from the snapshot and
//! [`Terminal::write`] feeds the output through Ghostty's VT parser, so the
//! phone shows what the desktop shows, scrollback included. [`Terminal::frame`]
//! turns the viewport into style runs per row, with per-row dirty flags, so a
//! Canvas renderer only repaints what changed.

pub mod keys;
pub mod predict;
pub use keys::{KeyMods, key_from_name};
pub use predict::Prediction;

use libghostty_vt as vt;
use libghostty_vt::render::{CellIterator, CursorVisualStyle, Dirty, RenderState, RowIterator};
use libghostty_vt::screen::CellWide;
use libghostty_vt::search::{MatchBuffer, Search};
use libghostty_vt::selection::{FormatOptions, SelectLineOptions, SelectWordOptions, Selection};
use libghostty_vt::style::{Palette, RgbColor, StyleColor};
use libghostty_vt::terminal::{Point, PointCoordinate, PointSpace, ScrollViewport};
use std::time::Instant;

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MouseButton {
    Left,
    Right,
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
    /// Drop the plain blank cells at both ends of the row: they paint nothing, and a row is
    /// mostly them. Runs keep their `col`, so nothing moves.
    fn trim_blank_ends(&mut self) {
        let plain = |r: &Run| r.bg.is_none() && !r.selected && r.underline == Underline::None && !r.strikethrough && !r.overline;
        if let Some(last) = self.runs.last_mut().filter(|r| plain(r)) {
            let cut = last.text.len() - last.text.trim_end_matches(' ').len();
            last.text.truncate(last.text.len() - cut);
            last.widths.truncate(last.widths.len() - cut);
            last.cells -= cut as u16;
            if last.text.is_empty() {
                self.runs.pop();
            }
        }
        if let Some(first) = self.runs.first_mut().filter(|r| plain(r)) {
            let cut = first.text.len() - first.text.trim_start_matches(' ').len();
            first.text.drain(..cut);
            first.widths.drain(..cut);
            first.cells -= cut as u16;
            first.col += cut as u16;
        }
    }

    /// The row's text, blanks between runs restored; a row that ends in blanks comes back without them.
    pub fn text(&self) -> String {
        let mut out = String::new();
        let mut at = 0usize;
        for r in &self.runs {
            out.extend(std::iter::repeat_n(' ', (r.col as usize).saturating_sub(at)));
            out.push_str(&r.text);
            at = r.col as usize + r.cells as usize;
        }
        out
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
    /// Revision this frame is at. Pass it to [`Terminal::frame_since`] to get only newer rows.
    pub rev: u64,
    /// Typed text the server hasn't echoed yet, to draw dimmed (local echo).
    pub prediction: Option<Prediction>,
    /// Search matches in the visible rows.
    pub highlights: Vec<Highlight>,
}

/// A search match on a visible row, in viewport cells.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Highlight {
    pub col: u16,
    pub row: u16,
    pub len: u16,
    pub current: bool,
}

/// A search match. `row` counts from the top of the scrollback, as [`Terminal::scroll_to_row`] does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Match {
    pub row: u64,
    pub col: u16,
    /// Cells on the first row (to the row's end when the match wraps).
    pub len: u16,
}

/// A match with both ends, for highlighting wrapped ones.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Span {
    start: (u64, u16),
    end: (u64, u16),
}

struct SearchState {
    search: Search<'static, 'static>,
    query: String,
    case_sensitive: bool,
    spans: Vec<Span>,
    current: Option<usize>,
    /// Output arrived since `spans` were read.
    stale: bool,
}

/// What `changed` compares: everything in a frame but the rows.
#[derive(Debug, Clone, PartialEq)]
struct FrameMeta {
    cursor: Option<Cursor>,
    scrollbar: Scrollbar,
    prediction: Option<Prediction>,
    highlights: Vec<Highlight>,
    colors: (Rgb, Rgb, Option<Rgb>),
    grid: (u16, u16),
    modes: (bool, bool),
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
    /// The next frame must treat every row as changed (scrolled, selected, recolored...).
    force_full: bool,
    /// Revision counter and, per viewport row, the revision it last changed at.
    rev: u64,
    row_rev: Vec<u64>,
    last_meta: Option<FrameMeta>,
    predictor: predict::Predictor,
    search: Option<SearchState>,
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
            force_full: true,
            rev: 0,
            row_rev: Vec::new(),
            last_meta: None,
            predictor: predict::Predictor::default(),
            search: None,
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
        self.search = None;
        self.predictor.clear();
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
        if self.predictor.is_pending() {
            let cursor = (self.raw.cursor_x().unwrap_or(0), self.raw.cursor_y().unwrap_or(0));
            let raw = &self.raw;
            self.predictor.observed(cursor, |col, row| cell_char(raw, col, row));
        }
        if let Some(s) = &mut self.search {
            s.stale = true;
        }
    }

    pub fn resize(&mut self, cols: u16, rows: u16, cell_w_px: u32, cell_h_px: u32) {
        self.cell_px = (cell_w_px, cell_h_px);
        self.force_full = true;
        self.predictor.clear();
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
        self.force_full = true;
        self.raw.scroll_viewport(ScrollViewport::Delta(delta));
    }

    pub fn scroll_to_row(&mut self, row: usize) {
        self.force_full = true;
        self.raw.scroll_viewport(ScrollViewport::Row(row));
    }

    /// Jump by shell prompts (needs shell integration).
    pub fn scroll_prompts(&mut self, delta: isize) {
        self.force_full = true;
        self.raw.scroll_viewport(ScrollViewport::DeltaPrompt(delta));
    }

    pub fn scroll_to_bottom(&mut self) {
        self.force_full = true;
        self.raw.scroll_viewport(ScrollViewport::Bottom);
    }

    pub fn scroll_to_top(&mut self) {
        self.force_full = true;
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
        self.force_full = true;
        self.raw.set_selection(Some(&sel)).is_ok()
    }

    pub fn select_word(&mut self, col: u16, row: u16) -> bool {
        self.force_full = true;
        let Ok(r) = self.raw.grid_ref(Self::viewport(col, row)) else { return false };
        matches!(self.raw.select_word(SelectWordOptions::new(r)), Ok(Some(sel)) if self.raw.set_selection(Some(&sel)).is_ok())
    }

    pub fn select_line(&mut self, row: u16) -> bool {
        self.force_full = true;
        let Ok(r) = self.raw.grid_ref(Self::viewport(0, row)) else { return false };
        matches!(self.raw.select_line(SelectLineOptions::new(r)), Ok(Some(sel)) if self.raw.set_selection(Some(&sel)).is_ok())
    }

    pub fn clear_selection(&mut self) {
        self.force_full = true;
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
        self.mouse_report(col, row, if down { vt::mouse::Button::Five } else { vt::mouse::Button::Four }, &[vt::mouse::Action::Press])
    }

    /// Bytes for a click (press then release) at a cell; `None` when the program doesn't track the mouse.
    pub fn click(&self, col: u16, row: u16, button: MouseButton) -> Option<Vec<u8>> {
        let button = match button {
            MouseButton::Left => vt::mouse::Button::Left,
            MouseButton::Right => vt::mouse::Button::Right,
        };
        self.mouse_report(col, row, button, &[vt::mouse::Action::Press, vt::mouse::Action::Release])
    }

    fn mouse_report(&self, col: u16, row: u16, button: vt::mouse::Button, actions: &[vt::mouse::Action]) -> Option<Vec<u8>> {
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
        let mut out = Vec::with_capacity(32);
        for action in actions {
            let mut ev = vt::mouse::Event::new().ok()?;
            ev.set_action(*action);
            ev.set_button(Some(button));
            ev.set_position(vt::mouse::Position { x: (col.min(cols.saturating_sub(1)) as f32 + 0.5) * cw as f32, y: (row.min(rows.saturating_sub(1)) as f32 + 0.5) * ch as f32 });
            enc.encode_to_vec(&ev, &mut out).ok()?;
        }
        (!out.is_empty()).then_some(out)
    }

    /// Replace the 16 ANSI colors (a theme); `None` restores the built-in ones.
    pub fn set_ansi_palette(&mut self, ansi: Option<[Rgb; 16]>) {
        self.force_full = true;
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
        self.force_full = true;
        let _ = self.raw.set_default_fg_color(fg.map(Into::into));
        let _ = self.raw.set_default_bg_color(bg.map(Into::into));
        let _ = self.raw.set_default_cursor_color(cursor.map(Into::into));
    }

    /// Apply a Rex `theme` message: hex colors plus `index=RRGGBB` palette entries.
    pub fn apply_theme(&mut self, foreground: Option<&str>, background: Option<&str>, cursor: Option<&str>, palette: &[String]) {
        self.force_full = true;
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

    /// Build a drawable frame of the viewport with every row's runs. Rows carry `dirty`;
    /// `changed` is false when the terminal hasn't changed since the last call.
    pub fn frame(&mut self) -> Frame {
        self.build_frame(None)
    }

    /// Like [`Self::frame`], but rows that haven't changed since revision `since` (a
    /// previous frame's `rev`) come back empty with `dirty` false: the caller keeps the
    /// last runs it has for them. `since` 0 returns every row.
    pub fn frame_since(&mut self, since: u64) -> Frame {
        self.build_frame(Some(since))
    }

    fn build_frame(&mut self, since: Option<u64>) -> Frame {
        self.refresh_search();
        let scrollbar = self.scrollbar();
        let alternate_screen = self.alternate_screen();
        let mouse_tracking = self.mouse_tracking();
        let prediction = self.predictor.visible(Instant::now());
        let highlights = self.highlights(scrollbar);
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
            rev: self.rev,
            prediction: None,
            highlights: Vec::new(),
        };
        let Ok(snapshot) = self.render.update(&self.raw) else { return empty(false) };
        let dirty = snapshot.dirty().unwrap_or(Dirty::Full);
        let cols = snapshot.cols().unwrap_or(0);
        let rows = snapshot.rows().unwrap_or(0);
        let resized = self.row_rev.len() != rows as usize;
        let full = self.first_frame || self.force_full || resized || dirty == Dirty::Full;
        self.first_frame = false;
        self.force_full = false;
        let observed = full || dirty != Dirty::Clean;
        if observed {
            self.rev += 1;
        }
        if resized {
            self.row_rev = vec![0; rows as usize];
        }
        let rev = self.rev;
        let colors = snapshot.colors().ok();
        let fg_default: Rgb = colors.as_ref().map(|c| c.foreground.into()).unwrap_or(Rgb::new(255, 255, 255));
        let bg_default: Rgb = colors.as_ref().map(|c| c.background.into()).unwrap_or_default();
        let palette = |i: u8| -> Rgb { colors.as_ref().map(|c| c.palette[i as usize].into()).unwrap_or_default() };
        let cur = snapshot.cursor().ok();

        let mut lines = Vec::with_capacity(rows as usize);
        if let Ok(mut row_it) = self.rows_it.update(&snapshot) {
            let mut idx = 0usize;
            while let Some(row_iter) = row_it.next() {
                let changed_now = full || row_iter.dirty().unwrap_or(true);
                if let Some(r) = self.row_rev.get_mut(idx).filter(|_| changed_now) {
                    *r = rev;
                }
                let revision = self.row_rev.get(idx).copied().unwrap_or(rev);
                idx += 1;
                let include = since.is_none_or(|s| revision > s);
                let mut row = Row { runs: Vec::new(), dirty: if since.is_some() { include } else { changed_now } };
                if !include {
                    lines.push(row);
                    continue;
                }
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
                    // Extend the open run in place, or start one; the grapheme goes straight into its text.
                    let run = if same {
                        let r = row.runs.last_mut().unwrap();
                        r.widths.push(width);
                        r.cells += width as u16;
                        r
                    } else {
                        row.runs.push(Run {
                            col: this_col,
                            cells: width as u16,
                            text: String::new(),
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
                        row.runs.last_mut().unwrap()
                    };
                    match cell.graphemes() {
                        Ok(g) if !g.is_empty() => run.text.extend(g),
                        _ => run.text.push(' '),
                    }
                }
                row.trim_blank_ends();
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

        let mut frame = Frame {
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
            changed: false,
            rev,
            prediction,
            highlights,
        };
        let meta = FrameMeta::of(&frame);
        let meta_changed = self.last_meta.as_ref() != Some(&meta);
        self.last_meta = Some(meta);
        frame.changed = meta_changed || since.map_or(observed, |s| rev > s);
        frame
    }

    /// The viewport as trimmed text lines.
    pub fn screen_lines(&mut self) -> Vec<String> {
        self.frame().lines.iter().map(|r| r.text().trim_end().to_owned()).collect()
    }

    /// The text of a viewport row, one character per cell (a wide character counts once).
    pub fn row_text(&self, row: u16) -> String {
        let (cols, _) = self.size();
        let mut out = String::with_capacity(cols as usize);
        for col in 0..cols {
            let Ok(r) = self.raw.grid_ref(Self::viewport(col, row)) else { break };
            let Ok(cell) = r.cell() else { continue };
            if matches!(cell.wide(), Ok(CellWide::SpacerTail | CellWide::SpacerHead)) {
                continue;
            }
            out.push(cell_char(&self.raw, col, row).unwrap_or(' '));
        }
        out
    }

    // ----- local echo -----

    pub fn set_local_echo(&mut self, on: bool) {
        self.predictor.set_enabled(on);
    }

    /// The user is about to send `text` as typed characters. Returns true when a prediction
    /// is now pending: the caller should poke the UI at [`predict::SHOW_AFTER`] and
    /// [`predict::GIVE_UP_AFTER`] so the frame shows it and drops it in time.
    pub fn local_echo_typed(&mut self, text: &str) -> bool {
        if !self.predictor.enabled() {
            return false;
        }
        let Some(cursor) = self.echo_cursor() else {
            self.predictor.clear();
            return false;
        };
        self.predictor.typed(text, cursor, self.size().0, Instant::now())
    }

    /// Enter, Backspace, Escape and the like change what the line will look like: forget predictions.
    pub fn local_echo_reset(&mut self) {
        self.predictor.clear();
    }

    /// Where typed text would appear, when it's safe to guess: a plain shell line, at the end of
    /// the text, with the viewport at the bottom, echo on and no full-screen program.
    fn echo_cursor(&mut self) -> Option<(u16, u16)> {
        if self.alternate_screen() || self.mouse_tracking() || !self.scrollbar().at_bottom() {
            return None;
        }
        let password = self.render.update(&self.raw).ok().and_then(|s| s.cursor().ok()).is_some_and(|c| c.password_input || !c.visible);
        if password {
            return None;
        }
        let (cols, rows) = self.size();
        let (col, row) = (self.raw.cursor_x().ok()?, self.raw.cursor_y().ok()?);
        let blank = |c: u16, r: u16| cell_char(&self.raw, c, r).is_none_or(|ch| ch == ' ');
        let tail_blank = (col..cols).all(|c| blank(c, row));
        let below_blank = (row + 1..rows).all(|r| (0..cols).all(|c| blank(c, r)));
        (tail_blank && below_blank).then_some((col, row))
    }

    // ----- search -----

    /// Find `query` in the scrollback and screen, top to bottom; the match nearest the bottom
    /// becomes current. ASCII letters match in either case unless `case_sensitive`.
    /// An empty query clears the search.
    pub fn search(&mut self, query: &str, case_sensitive: bool) -> Vec<Match> {
        if query.is_empty() {
            self.search_clear();
            return Vec::new();
        }
        if self.search.is_none() {
            let Ok(search) = Search::new(&mut self.raw) else { return Vec::new() };
            self.search = Some(SearchState { search, query: String::new(), case_sensitive, spans: Vec::new(), current: None, stale: true });
        }
        let Some(st) = self.search.as_mut() else { return Vec::new() };
        if st.search.set_needle(&mut self.raw, query.as_bytes()).is_err() {
            return Vec::new();
        }
        st.query = query.to_owned();
        st.case_sensitive = case_sensitive;
        st.stale = true;
        self.refresh_search();
        if let Some(st) = self.search.as_mut() {
            st.current = st.spans.len().checked_sub(1);
        }
        self.reveal_current();
        self.matches()
    }

    pub fn matches(&self) -> Vec<Match> {
        let Some(st) = &self.search else { return Vec::new() };
        let cols = self.size().0;
        st.spans.iter().map(|s| Match { row: s.start.0, col: s.start.1, len: if s.end.0 == s.start.0 { s.end.1 - s.start.1 + 1 } else { cols - s.start.1 } }).collect()
    }

    /// Move the current match by `delta` (wrapping; positive goes toward the bottom) and scroll
    /// it into view. Returns its index, or `None` without matches. `delta` 0 only reports it.
    pub fn search_step(&mut self, delta: isize) -> Option<usize> {
        let st = self.search.as_mut()?;
        let n = st.spans.len();
        if n == 0 {
            return None;
        }
        let at = st.current.unwrap_or(n - 1) as isize;
        let next = (at + delta).rem_euclid(n as isize) as usize;
        st.current = Some(next);
        if delta != 0 {
            self.reveal_current();
        }
        Some(next)
    }

    pub fn search_clear(&mut self) {
        self.search = None;
    }

    /// Scroll just enough that the current match is on screen, centered when it wasn't.
    fn reveal_current(&mut self) {
        let Some(span) = self.search.as_ref().and_then(|s| s.current.and_then(|i| s.spans.get(i))).copied() else { return };
        let sb = self.scrollbar();
        let row = span.start.0;
        if row >= sb.offset && row < sb.offset + sb.len {
            return;
        }
        self.scroll_to_row(row.saturating_sub(sb.len / 2) as usize);
    }

    /// Re-read the matches when output arrived since the last read, keeping the current
    /// one where it was (by position) if it still exists.
    fn refresh_search(&mut self) {
        let Some(st) = self.search.as_mut().filter(|s| s.stale) else { return };
        st.stale = false;
        let keep = st.current.and_then(|i| st.spans.get(i)).copied();
        let Ok(snapshot) = st.search.snapshot(&mut self.raw) else { return };
        let mut storage = MatchBuffer::new();
        let Ok(found) = snapshot.matches(&mut storage) else { return };
        let term = snapshot.terminal();
        let mut spans: Vec<Span> = found
            .filter_map(|sel| {
                let at = |g: &libghostty_vt::screen::GridRef<'_>| term.point_from_grid_ref(g, PointSpace::Screen).ok().flatten();
                let (a, b) = (at(&sel.start())?, at(&sel.end())?);
                let span = Span { start: (a.y as u64, a.x), end: (b.y as u64, b.x) };
                (!st.case_sensitive || exact_match(term, &sel, &st.query)).then_some(span)
            })
            .collect();
        spans.sort_unstable_by_key(|s| s.start);
        st.current = keep.and_then(|k| spans.iter().position(|s| *s == k)).or(spans.len().checked_sub(1));
        st.spans = spans;
    }

    /// Visible-row pieces of the matches, the current one flagged.
    fn highlights(&self, sb: Scrollbar) -> Vec<Highlight> {
        let Some(st) = &self.search else { return Vec::new() };
        let cols = self.size().0;
        let (top, bottom) = (sb.offset, sb.offset + sb.len);
        let mut out = Vec::new();
        for (i, s) in st.spans.iter().enumerate() {
            if s.end.0 < top || s.start.0 >= bottom {
                continue;
            }
            for row in s.start.0.max(top)..=s.end.0.min(bottom - 1) {
                let from = if row == s.start.0 { s.start.1 } else { 0 };
                let to = if row == s.end.0 { s.end.1 } else { cols.saturating_sub(1) };
                out.push(Highlight { col: from, row: (row - top) as u16, len: to + 1 - from, current: st.current == Some(i) });
            }
        }
        out
    }
}

impl FrameMeta {
    fn of(f: &Frame) -> Self {
        Self {
            cursor: f.cursor,
            scrollbar: f.scrollbar,
            prediction: f.prediction.clone(),
            highlights: f.highlights.clone(),
            colors: (f.background, f.foreground, f.cursor_color),
            grid: (f.cols, f.rows),
            modes: (f.alternate_screen, f.mouse_tracking),
        }
    }
}

/// The first character of a viewport cell; `None` when it is empty.
fn cell_char(raw: &vt::Terminal<'_, '_>, col: u16, row: u16) -> Option<char> {
    let r = raw.grid_ref(Point::Viewport(PointCoordinate { x: col, y: row as u32 })).ok()?;
    let mut buf = ['\0'; 4];
    let n = r.graphemes(&mut buf).ok()?;
    buf.first().copied().filter(|_| n > 0)
}

/// Whether a match's text is `query` to the letter (Ghostty ignores ASCII case).
fn exact_match(term: &vt::Terminal<'_, '_>, sel: &Selection<'_>, query: &str) -> bool {
    let opts = FormatOptions::new().with_emit_format(vt::fmt::Format::Plain).with_unwrap(true).with_selection(sel);
    let mut buf = vec![0u8; query.len().max(16) * 2];
    matches!(term.format_selection_buf(opts, &mut buf), Ok(Some(n)) if &buf[..n] == query.as_bytes())
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

    #[test]
    fn click_is_press_then_release_when_tracked() {
        let mut t = Terminal::new(10, 3);
        t.resize(10, 3, 8, 16);
        assert!(t.click(0, 0, MouseButton::Left).is_none());
        t.write(b"\x1b[?1000h\x1b[?1006h");
        assert_eq!(t.click(2, 1, MouseButton::Left).unwrap(), b"\x1b[<0;3;2M\x1b[<0;3;2m");
        assert_eq!(t.click(0, 0, MouseButton::Right).unwrap(), b"\x1b[<2;1;1M\x1b[<2;1;1m");
    }

    #[test]
    fn frame_since_skips_rows_the_caller_already_has() {
        let mut t = Terminal::new(20, 6);
        t.write(b"one\r\ntwo\r\nthree");
        let first = t.frame_since(0);
        assert!(first.changed && first.rev > 0);
        assert!(first.lines.iter().all(|l| l.dirty), "since 0 returns every row");
        assert_eq!(first.lines[1].runs[0].text.trim_end(), "two");
        let same = t.frame_since(first.rev);
        assert!(!same.changed, "nothing happened");
        assert!(same.lines.iter().all(|l| !l.dirty && l.runs.is_empty()));
        t.write(b"!");
        let next = t.frame_since(first.rev);
        assert!(next.changed);
        let sent: Vec<bool> = next.lines.iter().map(|l| l.dirty).collect();
        assert_eq!(sent, [false, false, true, false, false, false], "only the typed row");
        // A caller that lost `next` and asks again from the old revision still gets that row.
        let again = t.frame_since(first.rev);
        assert!(again.lines[2].dirty && again.lines[2].runs[0].text.starts_with("three!"));
        assert_eq!(again.rev, next.rev);
        // Scrolling and selecting repaint everything.
        for i in 0..10 {
            t.write(format!("\r\nline{i}").as_bytes());
        }
        let at = t.frame_since(0).rev;
        t.scroll(-3);
        assert!(t.frame_since(at).lines.iter().all(|l| l.dirty));
        let at = t.frame_since(0).rev;
        t.select_line(1);
        assert!(t.frame_since(at).lines.iter().all(|l| l.dirty));
    }

    #[test]
    fn blank_tails_are_not_sent_but_styled_blanks_are() {
        let mut t = Terminal::new(12, 3);
        t.write(b"ab  \x1b[44m  \x1b[0m   \r\n\x1b[4m  ");
        let f = t.frame();
        let r0 = &f.lines[0].runs;
        assert_eq!(r0.iter().map(|r| (r.col, r.cells, r.text.as_str())).collect::<Vec<_>>(), [(0, 4, "ab  "), (4, 2, "  ")]);
        assert_eq!(f.lines[1].runs.len(), 1, "an underlined blank is visible");
        assert!(f.lines[2].runs.is_empty());
        t.write(b"\x1b[0m\x1b[3;1H    indented");
        let f = t.frame();
        let run = &f.lines[2].runs[0];
        assert_eq!((run.col, run.cells, run.text.as_str()), (4, 8, "indented"));
        assert_eq!(f.lines[2].text(), "    indented", "text() puts the indent back");
    }

    #[test]
    fn a_resize_sends_every_row() {
        let mut t = Terminal::new(10, 4);
        let at = t.frame_since(0).rev;
        t.resize(10, 6, 8, 16);
        let f = t.frame_since(at);
        assert_eq!(f.lines.len(), 6);
        assert!(f.lines.iter().all(|l| l.dirty));
    }

    #[test]
    fn row_text_reads_a_viewport_row() {
        let mut t = Terminal::new(12, 3);
        t.write("héllo 你好".as_bytes());
        assert_eq!(t.row_text(0).trim_end(), "héllo 你好");
        assert_eq!(t.row_text(1).trim(), "");
    }

    fn scrolled_terminal() -> Terminal {
        let mut t = Terminal::new(20, 4);
        for i in 0..30 {
            t.write(format!("row{i} Needle {}\r\n", if i % 10 == 0 { "foo" } else { "bar" }).as_bytes());
        }
        t
    }

    #[test]
    fn search_finds_matches_in_scrollback_top_to_bottom() {
        let mut t = scrolled_terminal();
        let found = t.search("needle", false);
        assert_eq!(found.len(), 30);
        assert!(found.windows(2).all(|w| w[0].row < w[1].row));
        assert_eq!((found[0].row, found[0].col, found[0].len), (0, 5, 6));
        assert_eq!(t.search("foo", false).iter().map(|m| m.row).collect::<Vec<_>>(), [0, 10, 20]);
        assert!(t.search("nothing here", false).is_empty());
        assert_eq!(t.search("needle", true).len(), 0, "case matters when asked");
        assert_eq!(t.search("Needle", true).len(), 30);
    }

    #[test]
    fn search_steps_scroll_the_match_into_view_and_draw_highlights() {
        let mut t = scrolled_terminal();
        t.search("foo", false);
        // The match nearest the bottom is current; it is row 20 of 34, on screen only if the viewport shows it.
        assert_eq!(t.search_step(0), Some(2));
        let sb = t.scrollbar();
        assert!(20 >= sb.offset && 20 < sb.offset + sb.len, "scrolled to row 20: {sb:?}");
        assert_eq!(t.search_step(-1), Some(1));
        let sb = t.scrollbar();
        assert!(10 >= sb.offset && 10 < sb.offset + sb.len);
        let f = t.frame();
        let cur: Vec<&Highlight> = f.highlights.iter().filter(|h| h.current).collect();
        assert_eq!(cur.len(), 1);
        assert_eq!((cur[0].col, cur[0].len), (13, 3));
        assert_eq!(f.lines[cur[0].row as usize].text().trim_end(), "row10 Needle foo");
        assert_eq!(t.search_step(-1), Some(0));
        assert_eq!(t.search_step(-1), Some(2), "wraps");
        t.search_clear();
        assert!(t.frame().highlights.is_empty());
        assert_eq!(t.search_step(1), None);
    }

    #[test]
    fn search_follows_new_output() {
        let mut t = Terminal::new(20, 3);
        t.write(b"a find\r\n");
        assert_eq!(t.search("find", false).len(), 1);
        t.write(b"another find\r\n");
        assert_eq!(t.frame().highlights.len(), 2, "the next frame sees the new match");
        assert_eq!(t.matches().len(), 2);
    }

    #[test]
    fn local_echo_shows_late_and_only_when_safe() {
        let mut t = Terminal::new(30, 5);
        t.write(b"$ ");
        t.set_local_echo(true);
        assert!(t.local_echo_typed("ls"));
        assert!(t.frame().prediction.is_none(), "not shown before the server has had its time");
        std::thread::sleep(predict::SHOW_AFTER + std::time::Duration::from_millis(20));
        let p = t.frame().prediction.unwrap();
        assert_eq!((p.col, p.row, p.text.as_str()), (2, 0, "ls"));
        t.write(b"ls");
        assert!(t.frame().prediction.is_none(), "the server's echo replaces it");
        t.local_echo_reset();
        // Mid-line (text to the right of the cursor), full-screen programs and secrets are not guessed.
        t.write(b"x\x1b[D");
        assert!(!t.local_echo_typed("a"));
        t.write(b"\x1b[?1049h");
        assert!(!t.local_echo_typed("a"));
        t.set_local_echo(false);
        assert!(!t.local_echo_typed("a"));
    }

    #[test]
    fn local_echo_drops_on_cursor_jumps() {
        let mut t = Terminal::new(30, 5);
        t.write(b"$ ");
        t.set_local_echo(true);
        assert!(t.local_echo_typed("a"));
        t.write(b"\x1b[3;1Hjump");
        std::thread::sleep(predict::SHOW_AFTER + std::time::Duration::from_millis(20));
        assert!(t.frame().prediction.is_none());
    }
}
