//! Kotlin bindings for the rexc engine.
//!
//! Records here mirror `rexc_core` in FFI-friendly shapes: colors are
//! `0xAARRGGBB`, text styles are bit flags, and a frame carries a `dirty`
//! flag per row so the renderer only re-lays-out rows that changed.

use std::path::PathBuf;
use std::sync::Arc;

use rexc_core as core;

uniffi::setup_scaffolding!();

// ---- errors ----

#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum RexcError {
    NoHost,
    NoTerminal,
    Offline,
    Client(String),
    Io(String),
}

impl std::fmt::Display for RexcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NoHost => f.write_str("no such host"),
            Self::NoTerminal => f.write_str("no such terminal"),
            Self::Offline => f.write_str("host is not connected"),
            Self::Client(d) | Self::Io(d) => f.write_str(d),
        }
    }
}

impl std::error::Error for RexcError {}

impl From<core::EngineError> for RexcError {
    fn from(e: core::EngineError) -> Self {
        match e {
            core::EngineError::NoHost => Self::NoHost,
            core::EngineError::NoTerminal => Self::NoTerminal,
            core::EngineError::Offline => Self::Offline,
            core::EngineError::Client(c) => Self::Client(c.to_string()),
            core::EngineError::Io(i) => Self::Io(i.to_string()),
            core::EngineError::Connect(m) => Self::Client(m),
        }
    }
}

type Result<T> = std::result::Result<T, RexcError>;

// ---- hosts and sessions ----

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HostStatus {
    Offline,
    Connecting,
    Connected,
    Reconnecting,
}

impl From<core::HostStatus> for HostStatus {
    fn from(s: core::HostStatus) -> Self {
        match s {
            core::HostStatus::Offline => Self::Offline,
            core::HostStatus::Connecting => Self::Connecting,
            core::HostStatus::Connected => Self::Connected,
            core::HostStatus::Reconnecting => Self::Reconnecting,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ProgramState {
    Idle,
    Working,
    Done,
    Blocked,
    Error,
}

impl From<core::ProgramState> for ProgramState {
    fn from(s: core::ProgramState) -> Self {
        match s {
            core::ProgramState::Idle => Self::Idle,
            core::ProgramState::Working => Self::Working,
            core::ProgramState::Done => Self::Done,
            core::ProgramState::Blocked => Self::Blocked,
            core::ProgramState::Error => Self::Error,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Program {
    pub id: String,
    pub state: ProgramState,
    /// permission, question, or auth when blocked.
    pub kind: Option<String>,
    pub app: String,
    pub title: Option<String>,
    pub message: Option<String>,
    pub progress: Option<u8>,
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Block {
    pub block_id: String,
    pub flavor: String,
    pub owner: Option<String>,
    /// Who holds the pane, for people: "this phone", a client label, or null.
    pub owner_label: Option<String>,
    pub owned_by_me: bool,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
    pub title: Option<String>,
    /// A title worth showing (not the shell's `user@host:dir`).
    pub display_title: Option<String>,
    pub pwd: Option<String>,
    pub pwd_short: Option<String>,
    pub process: Option<String>,
    pub app: Option<String>,
    pub state: Option<ProgramState>,
    pub program: Option<Program>,
    pub exit_code: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Window {
    pub window_id: String,
    pub label: String,
    pub display_name: String,
    pub focused_block_id: Option<String>,
    pub zoomed_block_id: Option<String>,
    pub busy: bool,
    pub blocks: Vec<Block>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Session {
    pub session_id: String,
    pub label: String,
    pub active_window_id: Option<String>,
    pub loaded: bool,
    pub busy: bool,
    pub windows: Vec<Window>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Host {
    pub host_id: String,
    pub name: String,
    pub address: String,
    pub status: HostStatus,
    pub client_id: Option<String>,
    pub error: Option<String>,
    pub sessions: Vec<Session>,
    /// Other clients on the server: id to display name.
    pub clients: Vec<ClientLabel>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ClientLabel {
    pub client_id: String,
    pub display: String,
}

fn program(r: &core::ProgramStatus) -> Program {
    Program {
        id: r.id.clone(),
        state: r.state.into(),
        kind: r.kind.clone(),
        app: r.effective_app.clone().or(r.app.clone()).unwrap_or_default(),
        title: r.title.clone(),
        message: r.msg.clone(),
        progress: r.progress,
        updated_at: r.updated_at.clone(),
    }
}

fn block(h: &core::HostState, b: &core::BlockState) -> Block {
    let me = h.client_id.as_deref();
    Block {
        block_id: b.block_id.clone(),
        flavor: b.flavor.clone(),
        owner: b.owner.clone(),
        owner_label: h.owner_label(b.owner.as_deref()),
        owned_by_me: b.owner.is_some() && b.owner.as_deref() == me,
        x: b.rect[0],
        y: b.rect[1],
        w: b.rect[2],
        h: b.rect[3],
        title: b.title.clone(),
        display_title: b.display_title(),
        pwd: b.pwd.clone(),
        pwd_short: b.pwd_short(),
        process: b.process.clone(),
        app: b.app_name(),
        state: b.program_state().map(Into::into),
        program: b.program().map(program),
        exit_code: b.exit_code,
    }
}

fn host(h: &core::HostState) -> Host {
    Host {
        host_id: h.host_id.clone(),
        name: h.name.clone(),
        address: h.address.clone(),
        status: h.status.into(),
        client_id: h.client_id.clone(),
        error: h.error.clone(),
        sessions: h
            .sessions
            .iter()
            .map(|s| Session {
                session_id: s.session_id.clone(),
                label: s.label.clone(),
                active_window_id: s.active_window_id.clone(),
                loaded: s.loaded,
                busy: s.busy(),
                windows: s
                    .windows
                    .iter()
                    .map(|w| Window {
                        window_id: w.window_id.clone(),
                        label: w.label.clone(),
                        display_name: w.display_name(),
                        focused_block_id: w.focused_block_id.clone(),
                        zoomed_block_id: w.zoomed_block_id.clone(),
                        busy: w.busy(),
                        blocks: w.blocks.iter().map(|b| block(h, b)).collect(),
                    })
                    .collect(),
            })
            .collect(),
        clients: h.clients.iter().map(|(k, v)| ClientLabel { client_id: k.clone(), display: v.clone() }).collect(),
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HostRecord {
    pub host_id: String,
    pub name: String,
    pub address: String,
    pub background_alerts: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct LastPosition {
    pub session_id: Option<String>,
    pub window_id: Option<String>,
    pub block_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct InboxItem {
    pub host_id: String,
    pub host_name: String,
    pub session_id: String,
    pub session_label: String,
    pub window_id: String,
    pub window_label: String,
    pub block_id: String,
    pub app: String,
    pub state: ProgramState,
    pub kind: Option<String>,
    pub title: Option<String>,
    pub message: Option<String>,
    pub updated_at: Option<String>,
}

impl From<core::InboxItem> for InboxItem {
    fn from(i: core::InboxItem) -> Self {
        Self {
            host_id: i.host_id,
            host_name: i.host_name,
            session_id: i.session_id,
            session_label: i.session_label,
            window_id: i.window_id,
            window_label: i.window_label,
            block_id: i.block_id,
            app: i.app,
            state: i.state.into(),
            kind: i.kind,
            title: i.title,
            message: i.message,
            updated_at: i.updated_at,
        }
    }
}

// ---- events ----

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum AlertKind {
    Blocked { kind: Option<String> },
    Done,
    Error,
    Notification,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Alert {
    pub host_id: String,
    pub host_name: String,
    pub session_id: String,
    pub session_label: String,
    pub block_id: String,
    pub window_label: String,
    pub kind: AlertKind,
    pub title: String,
    pub body: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Event {
    Hosts,
    Host { host_id: String },
    Terminal { terminal_id: u64 },
    TerminalState { terminal_id: u64 },
    Alert { alert: Alert },
    Clipboard { host_id: String, text: String },
    OpenUrl { url: String },
}

impl From<core::Event> for Event {
    fn from(e: core::Event) -> Self {
        match e {
            core::Event::Hosts => Self::Hosts,
            core::Event::Host { host_id } => Self::Host { host_id },
            core::Event::Terminal { terminal_id } => Self::Terminal { terminal_id },
            core::Event::TerminalState { terminal_id } => Self::TerminalState { terminal_id },
            core::Event::Alert(a) => Self::Alert {
                alert: Alert {
                    host_id: a.host_id,
                    host_name: a.host_name,
                    session_id: a.session_id,
                    session_label: a.session_label,
                    block_id: a.block_id,
                    window_label: a.window_label,
                    kind: match a.kind {
                        core::AlertKind::Blocked { kind } => AlertKind::Blocked { kind },
                        core::AlertKind::Done => AlertKind::Done,
                        core::AlertKind::Error => AlertKind::Error,
                        core::AlertKind::Notification => AlertKind::Notification,
                    },
                    title: a.title,
                    body: a.body,
                },
            },
            core::Event::Clipboard { host_id, text } => Self::Clipboard { host_id, text },
            core::Event::OpenUrl { url } => Self::OpenUrl { url },
        }
    }
}

#[uniffi::export(with_foreign)]
pub trait RexcListener: Send + Sync {
    /// Called from engine threads; hop to the main thread before touching UI.
    fn on_event(&self, event: Event);
}

struct Bridge(Arc<dyn RexcListener>);

impl core::Listener for Bridge {
    fn on_event(&self, event: core::Event) {
        self.0.on_event(event.into());
    }
}

// ---- terminals ----

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum StreamState {
    Connecting,
    Live,
    Reconnecting,
    Ended { reason: String },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SizeMode {
    Fit,
    Mirror,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TerminalInfo {
    pub terminal_id: u64,
    pub host_id: String,
    pub session_id: String,
    pub block_id: String,
    pub stream: StreamState,
    pub owner: Option<String>,
    pub owned_by_me: bool,
    pub mode: SizeMode,
    pub cols: u16,
    pub rows: u16,
    pub exit_code: Option<i32>,
    pub has_snapshot: bool,
}

impl From<core::TerminalInfo> for TerminalInfo {
    fn from(i: core::TerminalInfo) -> Self {
        Self {
            terminal_id: i.terminal_id,
            host_id: i.host_id,
            session_id: i.session_id,
            block_id: i.block_id,
            stream: match i.stream {
                core::StreamState::Connecting => StreamState::Connecting,
                core::StreamState::Live => StreamState::Live,
                core::StreamState::Reconnecting => StreamState::Reconnecting,
                core::StreamState::Ended { reason } => StreamState::Ended { reason },
            },
            owner: i.owner,
            owned_by_me: i.owned_by_me,
            mode: match i.mode {
                core::SizeMode::Fit => SizeMode::Fit,
                core::SizeMode::Mirror => SizeMode::Mirror,
            },
            cols: i.cols,
            rows: i.rows,
            exit_code: i.exit_code,
            has_snapshot: i.has_snapshot,
        }
    }
}

pub const STYLE_BOLD: u16 = 1;
pub const STYLE_ITALIC: u16 = 1 << 1;
pub const STYLE_FAINT: u16 = 1 << 2;
pub const STYLE_STRIKE: u16 = 1 << 3;
pub const STYLE_OVERLINE: u16 = 1 << 4;
pub const STYLE_SELECTED: u16 = 1 << 5;
pub const STYLE_HYPERLINK: u16 = 1 << 6;
/// Bits 8..11 hold the underline kind: 0 none, 1 single, 2 double, 3 curly, 4 dotted, 5 dashed.
pub const STYLE_UNDERLINE_SHIFT: u16 = 8;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Run {
    pub col: u16,
    pub cells: u16,
    pub text: String,
    /// Cell width of each grapheme in `text` (1 or 2).
    pub widths: Vec<u8>,
    /// 0xAARRGGBB
    pub fg: u32,
    pub bg: Option<u32>,
    pub underline_color: Option<u32>,
    pub style: u16,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Line {
    pub runs: Vec<Run>,
    /// False when this row is pixel-identical to the previous frame's row.
    pub dirty: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CursorShape {
    Block,
    Bar,
    Underline,
    HollowBlock,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Cursor {
    pub col: u16,
    pub row: u16,
    pub shape: CursorShape,
    pub blinking: bool,
    pub password: bool,
    pub wide: bool,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Frame {
    pub cols: u16,
    pub rows: u16,
    pub background: u32,
    pub foreground: u32,
    pub cursor_color: Option<u32>,
    pub cursor: Option<Cursor>,
    pub lines: Vec<Line>,
    pub scroll_total: u64,
    pub scroll_offset: u64,
    pub scroll_len: u64,
    pub alternate_screen: bool,
    pub mouse_tracking: bool,
    pub dark: bool,
    /// False when nothing changed since the last frame (skip the draw).
    pub changed: bool,
}

impl From<core::Frame> for Frame {
    fn from(f: core::Frame) -> Self {
        let dark = f.background.luminance() < 0.5;
        Self {
            cols: f.cols,
            rows: f.rows,
            background: f.background.argb(),
            foreground: f.foreground.argb(),
            cursor_color: f.cursor_color.map(|c| c.argb()),
            cursor: f.cursor.map(|c| Cursor {
                col: c.col,
                row: c.row,
                shape: match c.style {
                    core::CursorStyle::Block => CursorShape::Block,
                    core::CursorStyle::Bar => CursorShape::Bar,
                    core::CursorStyle::Underline => CursorShape::Underline,
                    core::CursorStyle::HollowBlock => CursorShape::HollowBlock,
                },
                blinking: c.blinking,
                password: c.password,
                wide: c.wide,
            }),
            lines: f
                .lines
                .into_iter()
                .map(|row| Line {
                    dirty: row.dirty,
                    runs: row
                        .runs
                        .into_iter()
                        .map(|r| {
                            let mut style = 0u16;
                            if r.bold {
                                style |= STYLE_BOLD;
                            }
                            if r.italic {
                                style |= STYLE_ITALIC;
                            }
                            if r.faint {
                                style |= STYLE_FAINT;
                            }
                            if r.strikethrough {
                                style |= STYLE_STRIKE;
                            }
                            if r.overline {
                                style |= STYLE_OVERLINE;
                            }
                            if r.selected {
                                style |= STYLE_SELECTED;
                            }
                            if r.hyperlink {
                                style |= STYLE_HYPERLINK;
                            }
                            let ul = match r.underline {
                                core::Underline::None => 0,
                                core::Underline::Single => 1,
                                core::Underline::Double => 2,
                                core::Underline::Curly => 3,
                                core::Underline::Dotted => 4,
                                core::Underline::Dashed => 5,
                            };
                            style |= ul << STYLE_UNDERLINE_SHIFT;
                            Run { col: r.col, cells: r.cells, text: r.text, widths: r.widths, fg: r.fg.argb(), bg: r.bg.map(|c| c.argb()), underline_color: r.underline_color.map(|c| c.argb()), style }
                        })
                        .collect(),
                })
                .collect(),
            scroll_total: f.scrollbar.total,
            scroll_offset: f.scrollbar.offset,
            scroll_len: f.scrollbar.len,
            alternate_screen: f.alternate_screen,
            mouse_tracking: f.mouse_tracking,
            dark,
            changed: f.changed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct KeyMods {
    pub shift: bool,
    pub ctrl: bool,
    pub alt: bool,
    pub meta: bool,
}

impl From<KeyMods> for core::KeyMods {
    fn from(m: KeyMods) -> Self {
        Self { shift: m.shift, ctrl: m.ctrl, alt: m.alt, meta: m.meta }
    }
}

// ---- the engine ----

#[derive(uniffi::Object)]
pub struct Rexc {
    engine: Arc<core::Engine>,
}

#[uniffi::export]
impl Rexc {
    /// `data_dir`: where hosts.json lives. `device_label`: shown to other clients.
    #[uniffi::constructor]
    pub fn new(data_dir: String, app_version: String, device_label: Option<String>, listener: Arc<dyn RexcListener>) -> Arc<Self> {
        init_logging();
        let cfg = core::EngineConfig { data_dir: PathBuf::from(data_dir), app_name: "rexc".into(), app_version, device_label };
        Arc::new(Self { engine: core::Engine::new(cfg, Arc::new(Bridge(listener))) })
    }

    // hosts
    pub fn hosts(&self) -> Vec<HostRecord> {
        self.engine.hosts().into_iter().map(|h| HostRecord { host_id: h.id, name: h.name, address: h.address, background_alerts: h.background_alerts }).collect()
    }
    pub fn add_host(&self, name: String, address: String) -> Result<String> {
        Ok(self.engine.add_host(&name, &address)?)
    }
    pub fn update_host(&self, host_id: String, name: String, address: String, background_alerts: bool) -> Result<()> {
        Ok(self.engine.update_host(&host_id, &name, &address, background_alerts)?)
    }
    pub fn remove_host(&self, host_id: String) -> Result<()> {
        Ok(self.engine.remove_host(&host_id)?)
    }
    pub fn connect(&self, host_id: String) -> Result<()> {
        Ok(self.engine.connect(&host_id)?)
    }
    pub fn disconnect(&self, host_id: String) -> Result<()> {
        Ok(self.engine.disconnect(&host_id)?)
    }
    pub fn connect_all(&self) {
        self.engine.connect_all();
    }
    pub fn set_background(&self, background: bool) {
        self.engine.set_background(background);
    }
    pub fn host_state(&self, host_id: String) -> Result<Host> {
        Ok(host(&self.engine.host_state(&host_id)?))
    }
    pub fn all_hosts(&self) -> Vec<Host> {
        self.engine.all_host_states().iter().map(host).collect()
    }
    pub fn inbox(&self) -> Vec<InboxItem> {
        self.engine.inbox().into_iter().map(Into::into).collect()
    }
    pub fn last_position(&self, host_id: String) -> LastPosition {
        let p = self.engine.last_position(&host_id);
        LastPosition { session_id: p.session_id, window_id: p.window_id, block_id: p.block_id }
    }
    pub fn set_last_position(&self, host_id: String, session_id: Option<String>, window_id: Option<String>, block_id: Option<String>) {
        self.engine.set_last_position(&host_id, session_id, window_id, block_id);
    }

    // sessions and layout (each blocks until the server answers; call off the main thread)
    /// Type `text` and Enter into a pane without opening it; connects first if needed.
    pub fn quick_reply(&self, host_id: String, session_id: String, block_id: String, text: String) -> Result<()> {
        Ok(self.engine.quick_reply(&host_id, &session_id, &block_id, &text)?)
    }

    /// The last `lines` non-blank lines of a pane's screen, as plain text.
    pub fn block_preview(&self, host_id: String, session_id: String, block_id: String, lines: u32) -> Result<String> {
        Ok(self.engine.block_preview(&host_id, &session_id, &block_id, lines as usize)?)
    }

    pub fn new_session(&self, host_id: String, label: Option<String>) -> Result<String> {
        Ok(self.engine.new_session(&host_id, label.as_deref())?)
    }
    pub fn destroy_session(&self, host_id: String, session_id: String) -> Result<()> {
        Ok(self.engine.destroy_session(&host_id, &session_id)?)
    }
    pub fn rename_session(&self, host_id: String, session_id: String, label: String) -> Result<()> {
        Ok(self.engine.rename_session(&host_id, &session_id, &label)?)
    }
    /// Returns the new window's first block id.
    pub fn new_window(&self, host_id: String, session_id: String, cwd: Option<String>) -> Result<String> {
        Ok(self.engine.new_window(&host_id, &session_id, cwd.as_deref())?.1)
    }
    pub fn close_window(&self, host_id: String, session_id: String, window_id: String) -> Result<()> {
        Ok(self.engine.close_window(&host_id, &session_id, &window_id)?)
    }
    pub fn rename_window(&self, host_id: String, session_id: String, window_id: String, label: String) -> Result<()> {
        Ok(self.engine.rename_window(&host_id, &session_id, &window_id, &label)?)
    }

    /// Place a tab just before `before_window_id`, or last when that is `None`.
    pub fn move_window(&self, host_id: String, session_id: String, window_id: String, before_window_id: Option<String>) -> Result<()> {
        Ok(self.engine.move_window(&host_id, &session_id, &window_id, before_window_id.as_deref())?)
    }
    pub fn split(&self, host_id: String, session_id: String, block_id: String, vertical: bool, cwd: Option<String>) -> Result<String> {
        Ok(self.engine.split(&host_id, &session_id, &block_id, vertical, cwd.as_deref())?)
    }
    pub fn close_block(&self, host_id: String, session_id: String, block_id: String) -> Result<()> {
        Ok(self.engine.close_block(&host_id, &session_id, &block_id)?)
    }
    pub fn focus_block(&self, host_id: String, session_id: String, block_id: String) -> Result<()> {
        Ok(self.engine.focus_block(&host_id, &session_id, &block_id)?)
    }
    pub fn clear_terminal(&self, host_id: String, session_id: String, block_id: String) -> Result<()> {
        Ok(self.engine.clear_terminal(&host_id, &session_id, &block_id)?)
    }

    // terminals
    pub fn open_terminal(&self, host_id: String, session_id: String, block_id: String, take: bool) -> Result<u64> {
        Ok(self.engine.open_terminal(&host_id, &session_id, &block_id, take)?)
    }
    pub fn close_terminal(&self, terminal_id: u64) {
        self.engine.close_terminal(terminal_id);
    }
    pub fn terminal_info(&self, terminal_id: u64) -> Result<TerminalInfo> {
        Ok(self.engine.terminal_info(terminal_id)?.into())
    }
    /// Call once per display refresh after a `Terminal` event.
    pub fn terminal_frame(&self, terminal_id: u64) -> Result<Frame> {
        Ok(self.engine.terminal_frame(terminal_id)?.into())
    }
    pub fn terminal_set_viewport(&self, terminal_id: u64, cols: u16, rows: u16, cell_w: u32, cell_h: u32) -> Result<()> {
        Ok(self.engine.terminal_set_viewport(terminal_id, cols, rows, cell_w, cell_h)?)
    }
    pub fn terminal_take_over(&self, terminal_id: u64) -> Result<()> {
        Ok(self.engine.terminal_take_over(terminal_id)?)
    }
    pub fn terminal_release(&self, terminal_id: u64) -> Result<()> {
        Ok(self.engine.terminal_release(terminal_id)?)
    }
    pub fn terminal_send_text(&self, terminal_id: u64, text: String) -> Result<()> {
        Ok(self.engine.terminal_send_text(terminal_id, &text)?)
    }
    /// `key` is a W3C code in snake_case: `enter`, `arrow_up`, `key_c`, `digit_1`, `f5`...
    pub fn terminal_send_key(&self, terminal_id: u64, key: String, mods: KeyMods) -> Result<bool> {
        Ok(self.engine.terminal_send_key(terminal_id, &key, mods.into())?)
    }
    pub fn terminal_paste(&self, terminal_id: u64, text: String) -> Result<()> {
        Ok(self.engine.terminal_paste(terminal_id, &text)?)
    }
    pub fn terminal_scroll(&self, terminal_id: u64, delta_rows: i32, col: u16, row: u16) -> Result<()> {
        Ok(self.engine.terminal_scroll(terminal_id, delta_rows, col, row)?)
    }
    pub fn terminal_scroll_to_bottom(&self, terminal_id: u64) -> Result<()> {
        Ok(self.engine.terminal_scroll_to_bottom(terminal_id)?)
    }
    pub fn terminal_scroll_to_row(&self, terminal_id: u64, row: u64) -> Result<()> {
        Ok(self.engine.terminal_scroll_to_row(terminal_id, row)?)
    }
    pub fn terminal_select(&self, terminal_id: u64, from_col: u16, from_row: u16, to_col: u16, to_row: u16) -> Result<bool> {
        Ok(self.engine.terminal_select(terminal_id, from_col, from_row, to_col, to_row)?)
    }
    pub fn terminal_select_word(&self, terminal_id: u64, col: u16, row: u16) -> Result<bool> {
        Ok(self.engine.terminal_select_word(terminal_id, col, row)?)
    }
    pub fn terminal_select_line(&self, terminal_id: u64, row: u16) -> Result<bool> {
        Ok(self.engine.terminal_select_line(terminal_id, row)?)
    }
    pub fn terminal_clear_selection(&self, terminal_id: u64) -> Result<()> {
        Ok(self.engine.terminal_clear_selection(terminal_id)?)
    }
    pub fn terminal_selection_text(&self, terminal_id: u64) -> Result<Option<String>> {
        Ok(self.engine.terminal_selection_text(terminal_id)?)
    }
    pub fn terminal_all_text(&self, terminal_id: u64) -> Result<String> {
        Ok(self.engine.terminal_all_text(terminal_id)?)
    }
    pub fn terminal_link_at(&self, terminal_id: u64, col: u16, row: u16) -> Result<Option<String>> {
        Ok(self.engine.terminal_link_at(terminal_id, col, row)?)
    }
}

fn init_logging() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::prelude::*;
            let filter = tracing_subscriber::EnvFilter::new("info,rexc_core=debug,rexc_client=debug");
            if let Ok(layer) = tracing_android::layer("rexc") {
                let _ = tracing_subscriber::registry().with(filter).with(layer).try_init();
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::from_default_env()).try_init();
        }
    });
}
