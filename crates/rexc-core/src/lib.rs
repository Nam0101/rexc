//! The rexc engine: everything the app does that isn't drawing.
//!
//! One [`Engine`] per process owns a tokio runtime, the saved hosts, a live
//! connection per host, and a terminal per pane on screen. The UI calls plain
//! methods and gets coarse [`Event`]s back through its [`Listener`]; it then
//! pulls the state it needs ([`Engine::host_state`], [`Engine::terminal_frame`]).

mod cache;
pub mod host;
pub mod model;
mod notify;
pub mod store;
pub mod terminal;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use parking_lot::Mutex;
use rexc_proto::control::ClientInfo;
use serde_json::{Value, json};

pub use rexc_proto::model::{ProgramState, ProgramStatus};
pub use rexc_term::{Cursor, CursorStyle, Frame, Highlight, KeyMods, Match, MouseButton, Prediction, Rgb, Row, Run, Scrollbar, Terminal, Theme, Underline, themes};

use host::{HostCmd, HostHandle};
pub use model::{BlockState, HostState, HostStatus, InboxItem, SessionState, WindowState};
use notify::Notifier;
pub use store::{HostRecord, LastPosition};
use terminal::{TermCmd, TerminalRegistry};
pub use terminal::{SizeMode, StreamState, TerminalInfo, Viewport};

/// One thing the server can do on request, for a command palette.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Action {
    pub name: String,
    pub title: String,
    pub description: String,
    pub category: String,
    pub keywords: String,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AlertKind {
    /// A program waits for you; `kind` is permission, question, or auth. `msg` is the status's
    /// own message, unsanitised: a reply hands it back as the [`Prompt`] it answers.
    Blocked { kind: Option<String>, msg: Option<String> },
    Done,
    Error,
    /// OSC 9 / OSC 777 from a program.
    Notification,
}

#[derive(Debug, Clone, PartialEq)]
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

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// The host list changed (added, removed, renamed).
    Hosts,
    /// A host's status or session tree changed; read `host_state`.
    Host { host_id: String },
    /// A terminal's screen changed; read `terminal_frame`.
    Terminal { terminal_id: u64 },
    /// A terminal's stream state or ownership changed; read `terminal_info`.
    TerminalState { terminal_id: u64 },
    Alert(Alert),
    /// A program copied text (OSC 52); put it on the clipboard.
    Clipboard { host_id: String, text: String },
    OpenUrl { url: String },
    /// A program rang the bell (BEL) in a block this terminal shows. At most one per
    /// terminal every half second; extra bells are dropped.
    Bell { terminal_id: u64 },
}

/// What a reply answers: a Blocked status's kind and message as the alert or inbox item had them.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Prompt {
    pub kind: Option<String>,
    pub msg: Option<String>,
}

pub trait Listener: Send + Sync + 'static {
    fn on_event(&self, event: Event);
}

#[derive(Debug, thiserror::Error)]
pub enum EngineError {
    #[error("no such host")]
    NoHost,
    #[error("no such terminal")]
    NoTerminal,
    #[error("host is not connected")]
    Offline,
    /// A reply's prompt is gone (answered, changed, or the program moved on); nothing was typed.
    #[error("No longer waiting — reply not sent")]
    NotWaiting,
    #[error("{0}")]
    Connect(String),
    #[error("{0}")]
    Client(#[from] rexc_client::ClientError),
    #[error("{0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, EngineError>;

pub struct EngineConfig {
    pub data_dir: PathBuf,
    pub app_name: String,
    pub app_version: String,
    /// Shown to other clients, such as the phone's model.
    pub device_label: Option<String>,
}

pub struct Engine {
    runtime: tokio::runtime::Runtime,
    data_dir: PathBuf,
    store: Mutex<store::Store>,
    hosts: Mutex<HashMap<String, Arc<HostHandle>>>,
    terminals: Arc<TerminalRegistry>,
    notifier: Notifier,
    client_info: ClientInfo,
    next_terminal: AtomicU64,
    /// The app's built-in terminal theme; `None` draws the server's colors.
    terminal_theme: Mutex<Option<String>>,
}

impl Engine {
    pub fn new(config: EngineConfig, listener: Arc<dyn Listener>) -> Arc<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().thread_name("rexc").build().expect("tokio runtime");
        let notifier = Notifier::new(listener, runtime.handle().clone());
        let store = store::Store::load(&config.data_dir);
        let client_info = ClientInfo::app(&config.app_name, &config.app_version, config.device_label);
        let engine = Arc::new(Self {
            runtime,
            data_dir: config.data_dir,
            store: Mutex::new(store),
            hosts: Mutex::default(),
            terminals: Arc::default(),
            notifier,
            client_info,
            next_terminal: AtomicU64::new(1),
            terminal_theme: Mutex::default(),
        });
        let records: Vec<HostRecord> = engine.store.lock().hosts.clone();
        for r in records {
            engine.spawn_host(r);
        }
        engine
    }

    fn spawn_host(&self, record: HostRecord) -> Arc<HostHandle> {
        let cache = cache::path(&self.data_dir, &record.id);
        let h = HostHandle::spawn(record.clone(), self.notifier.clone(), self.terminals.clone(), self.client_info.clone(), Some(cache), self.runtime.handle());
        self.hosts.lock().insert(record.id, h.clone());
        h
    }

    fn host(&self, host_id: &str) -> Result<Arc<HostHandle>> {
        self.hosts.lock().get(host_id).cloned().ok_or(EngineError::NoHost)
    }

    fn save(&self) {
        if let Err(e) = self.store.lock().save(&self.data_dir) {
            tracing::warn!("saving hosts: {e}");
        }
    }

    // ----- hosts -----

    pub fn hosts(&self) -> Vec<HostRecord> {
        self.store.lock().hosts.clone()
    }

    /// Add a host; the address is checked but not connected to yet.
    pub fn add_host(&self, name: &str, address: &str) -> Result<String> {
        rexc_client::Endpoint::parse(address)?;
        let record = HostRecord { id: store::new_id("host"), name: name.trim().to_string(), address: address.trim().to_string(), last: LastPosition::default(), background_alerts: false, theme: None };
        self.store.lock().hosts.push(record.clone());
        self.save();
        self.spawn_host(record.clone());
        self.notifier.hosts();
        Ok(record.id)
    }

    pub fn update_host(&self, host_id: &str, name: &str, address: &str, background_alerts: bool) -> Result<()> {
        rexc_client::Endpoint::parse(address)?;
        let h = self.host(host_id)?;
        {
            let mut st = self.store.lock();
            let r = st.host_mut(host_id).ok_or(EngineError::NoHost)?;
            r.name = name.trim().to_string();
            r.address = address.trim().to_string();
            r.background_alerts = background_alerts;
            *h.record.lock() = r.clone();
            let mut s = h.state.lock();
            s.name = r.name.clone();
            s.address = r.address.clone();
        }
        self.save();
        self.notifier.hosts();
        self.notifier.host(host_id);
        Ok(())
    }

    pub fn remove_host(&self, host_id: &str) -> Result<()> {
        let h = self.hosts.lock().remove(host_id).ok_or(EngineError::NoHost)?;
        h.send(HostCmd::Disconnect);
        h.forget_cache();
        for t in self.terminals.all().into_iter().filter(|t| t.host.id == host_id) {
            self.close_terminal(t.id);
        }
        self.store.lock().hosts.retain(|r| r.id != host_id);
        self.save();
        self.notifier.hosts();
        Ok(())
    }

    /// Reorder the host list: `host_id` goes just before `before_host_id`, or last when `None`.
    pub fn move_host(&self, host_id: &str, before_host_id: Option<&str>) -> Result<()> {
        if !self.store.lock().move_host(host_id, before_host_id) {
            return Err(EngineError::NoHost);
        }
        self.save();
        self.notifier.hosts();
        Ok(())
    }

    /// A host's own terminal theme (a built-in name); `None` follows the app's. Its open terminals follow.
    pub fn set_host_theme(&self, host_id: &str, name: Option<&str>) -> Result<()> {
        let h = self.host(host_id)?;
        {
            let mut st = self.store.lock();
            let r = st.host_mut(host_id).ok_or(EngineError::NoHost)?;
            r.theme = name.map(str::to_owned);
            h.record.lock().theme = r.theme.clone();
        }
        self.save();
        self.notifier.hosts();
        self.retheme(Some(host_id));
        Ok(())
    }

    /// The app's terminal theme (a built-in name); `None` draws each server's colors. Open terminals follow.
    pub fn set_terminal_theme(&self, name: Option<&str>) {
        *self.terminal_theme.lock() = name.map(str::to_owned);
        self.retheme(None);
    }

    fn theme_for(&self, host: &HostHandle) -> Option<Theme> {
        let own = host.record.lock().theme.clone();
        let app = self.terminal_theme.lock().clone();
        effective_theme(own.as_deref(), app.as_deref())
    }

    /// Recolor the open terminals of `host_id`, or of every host.
    fn retheme(&self, host_id: Option<&str>) {
        for t in self.terminals.all().into_iter().filter(|t| host_id.is_none_or(|id| t.host.id == id)) {
            let theme = self.theme_for(&t.host);
            t.term.lock().set_theme(theme);
            self.notifier.terminal(t.id);
        }
    }

    pub fn connect(&self, host_id: &str) -> Result<()> {
        self.host(host_id)?.send(HostCmd::Connect);
        Ok(())
    }

    pub fn disconnect(&self, host_id: &str) -> Result<()> {
        self.host(host_id)?.send(HostCmd::Disconnect);
        Ok(())
    }

    /// The device's network changed: hosts waiting out a backoff retry now, and connected
    /// ones check their socket is alive (wifi can drop without a FIN).
    pub fn network_changed(&self) {
        for h in self.hosts.lock().values() {
            h.send(HostCmd::Probe);
        }
    }

    pub fn connect_all(&self) {
        for h in self.hosts.lock().values() {
            h.send(HostCmd::Connect);
        }
    }

    /// Going to the background: keep hosts that want alerts, drop the rest.
    pub fn set_background(&self, background: bool) {
        self.terminals.set_background(background);
        for h in self.hosts.lock().values() {
            let keep = h.record.lock().background_alerts;
            if background && !keep {
                h.send(HostCmd::Disconnect);
            } else if !background {
                h.send(HostCmd::Connect);
            }
        }
    }

    pub fn host_state(&self, host_id: &str) -> Result<HostState> {
        Ok(self.host(host_id)?.state.lock().clone())
    }

    pub fn all_host_states(&self) -> Vec<HostState> {
        let order: Vec<String> = self.store.lock().hosts.iter().map(|h| h.id.clone()).collect();
        let hosts = self.hosts.lock();
        order.iter().filter_map(|id| hosts.get(id)).map(|h| h.state.lock().clone()).collect()
    }

    pub fn inbox(&self) -> Vec<InboxItem> {
        model::inbox(&self.all_host_states())
    }

    pub fn last_position(&self, host_id: &str) -> LastPosition {
        self.store.lock().host(host_id).map(|h| h.last.clone()).unwrap_or_default()
    }

    pub fn set_last_position(&self, host_id: &str, session_id: Option<String>, window_id: Option<String>, block_id: Option<String>) {
        if let Some(h) = self.store.lock().host_mut(host_id) {
            h.last = LastPosition { session_id, window_id, block_id };
        }
        self.save();
    }

    // ----- sessions and layout (server calls) -----

    fn block_on<T>(&self, fut: impl std::future::Future<Output = T>) -> T {
        self.runtime.block_on(fut)
    }

    /// Run a method on a host and refresh the session view afterwards.
    pub fn call(&self, host_id: &str, method: &str, payload: Value) -> Result<Value> {
        let h = self.host(host_id)?;
        let session_id = payload["session_id"].as_str().map(str::to_owned);
        let result = self.block_on(h.call(method, payload))?;
        if let Some(s) = session_id {
            h.send(HostCmd::Refresh(s));
        }
        Ok(result)
    }

    /// Type `text` (and Enter when `enter`) into a pane without opening it, e.g. "y" from a
    /// notification. Connects first when the host is down and, while the app is
    /// in the background, lets that connection go again afterwards.
    ///
    /// With `answers` (the reply answers a Blocked alert or inbox item), nothing is typed and
    /// [`EngineError::NotWaiting`] comes back unless the block still waits on that prompt.
    pub fn quick_reply(&self, host_id: &str, session_id: &str, block_id: &str, text: &str, enter: bool, answers: Option<&Prompt>) -> Result<()> {
        let h = self.host(host_id)?;
        let was_up = h.control().is_some();
        if !was_up {
            h.send(HostCmd::Connect);
        }
        let data = reply_data(text, enter);
        let result = self.block_on(async {
            // The host publishes its control only after a connect's full snapshot (sessions,
            // views, program status), so from here the model is fresh even after a cold connect.
            let control = wait_for_control(&h, QUICK_REPLY_TIMEOUT).await?;
            if let Some(prompt) = answers
                && !still_waiting(h.state.lock().block(session_id, block_id), prompt)
            {
                return Err(EngineError::NotWaiting);
            }
            control.call_block(session_id, block_id, "write", json!({"data": data})).await?;
            Ok::<_, EngineError>(())
        });
        if !was_up && self.terminals.background() && !h.record.lock().background_alerts {
            h.send(HostCmd::Disconnect);
        }
        result
    }

    /// The last `lines` non-blank lines of a pane's screen as plain text, for a glance without opening it.
    pub fn block_preview(&self, host_id: &str, session_id: &str, block_id: &str, lines: usize) -> Result<String> {
        let control = self.host(host_id)?.control().ok_or(EngineError::Offline)?;
        let text = self.block_on(async {
            let args = json!({"format": "text", "trim": true, "max_bytes": PREVIEW_BYTES});
            let v = control.call_block(session_id, block_id, "format", args).await?;
            let mut content = v["content"].as_str().unwrap_or_default().to_owned();
            // Long scrollback: the reply is only the head; read the tail of the kept result instead.
            if let (Some(id), Some(total)) = (v["id"].as_u64(), v["total"].as_u64()) {
                let offset = total.saturating_sub(PREVIEW_BYTES);
                let v = control.call_block(session_id, block_id, "blob_read", json!({"id": id, "offset": offset, "max_bytes": PREVIEW_BYTES})).await?;
                content = v["data"].as_str().unwrap_or_default().to_owned();
                if offset > 0 {
                    content = content.split_once('\n').map(|(_, rest)| rest.to_owned()).unwrap_or_default();
                }
            }
            Ok::<_, EngineError>(content)
        })?;
        Ok(tail_lines(&text, lines))
    }

    /// The server's own actions (configuration file and block actions), for a palette.
    pub fn actions(&self, host_id: &str) -> Result<Vec<Action>> {
        // The server lists its actions only when the request names at least one of ours.
        let payload = json!({"actions": [{"name": "rexc.palette"}], "server_actions": "all"});
        Ok(parse_actions(&self.call(host_id, "client.keymap", payload)?))
    }

    /// Run a server action on a session, on `block_id` or else the session's focused pane.
    pub fn run_action(&self, host_id: &str, session_id: &str, block_id: Option<&str>, action: &str) -> Result<()> {
        let mut payload = json!({"action": action, "origin": "palette", "session_id": session_id});
        if let Some(b) = block_id {
            payload["block_id"] = json!(b);
        }
        self.call(host_id, "client.action", payload)?;
        Ok(())
    }

    pub fn new_session(&self, host_id: &str, label: Option<&str>) -> Result<String> {
        let mut payload = json!({"initial_windows": [{"layout": {"block": {"flavor": rexc_proto::SHELL_FLAVOR}}}]});
        if let Some(l) = label.filter(|l| !l.trim().is_empty()) {
            payload["label"] = json!(l.trim());
        }
        let v = self.call(host_id, "session.create", payload)?;
        Ok(v["session_id"].as_str().unwrap_or_default().to_string())
    }

    pub fn destroy_session(&self, host_id: &str, session_id: &str) -> Result<()> {
        self.call(host_id, "session.destroy", json!({"session_id": session_id}))?;
        Ok(())
    }

    pub fn rename_session(&self, host_id: &str, session_id: &str, label: &str) -> Result<()> {
        self.call(host_id, "session.set_label", json!({"session_id": session_id, "label": label}))?;
        Ok(())
    }

    /// A new tab with a shell. Returns (window_id, block_id).
    pub fn new_window(&self, host_id: &str, session_id: &str, cwd: Option<&str>) -> Result<(String, String)> {
        let mut block = json!({"flavor": rexc_proto::SHELL_FLAVOR});
        if let Some(c) = cwd {
            block["options"] = json!({"cwd": c});
        }
        let v = self.call(host_id, "session.new_window", json!({"session_id": session_id, "layout": {"block": block}, "focus": false}))?;
        Ok((v["window_id"].as_str().unwrap_or_default().to_string(), v["block_ids"][0].as_str().unwrap_or_default().to_string()))
    }

    pub fn close_window(&self, host_id: &str, session_id: &str, window_id: &str) -> Result<()> {
        self.call(host_id, "session.close_window", json!({"session_id": session_id, "window_id": window_id}))?;
        Ok(())
    }

    pub fn rename_window(&self, host_id: &str, session_id: &str, window_id: &str, label: &str) -> Result<()> {
        self.call(host_id, "session.set_window_label", json!({"session_id": session_id, "window_id": window_id, "label": label}))?;
        Ok(())
    }

    pub fn move_window(&self, host_id: &str, session_id: &str, window_id: &str, before_window_id: Option<&str>) -> Result<()> {
        let mut payload = json!({"session_id": session_id, "window_id": window_id});
        if let Some(b) = before_window_id {
            payload["before_window_id"] = json!(b);
        }
        self.call(host_id, "session.move_window", payload)?;
        Ok(())
    }

    /// Split a pane; `vertical` stacks the new one below, otherwise beside. Returns the new block id.
    pub fn split(&self, host_id: &str, session_id: &str, block_id: &str, vertical: bool, cwd: Option<&str>) -> Result<String> {
        let mut block = json!({"flavor": rexc_proto::SHELL_FLAVOR});
        if let Some(c) = cwd {
            block["options"] = json!({"cwd": c});
        }
        let payload = json!({"session_id": session_id, "anchor_id": block_id, "direction": if vertical { "vertical" } else { "horizontal" }, "side": "after", "layout": {"block": block}});
        let v = self.call(host_id, "session.new_split", payload)?;
        Ok(v["block_ids"][0].as_str().unwrap_or_default().to_string())
    }

    pub fn close_block(&self, host_id: &str, session_id: &str, block_id: &str) -> Result<()> {
        self.call(host_id, "block.close", json!({"session_id": session_id, "block_id": block_id}))?;
        Ok(())
    }

    /// Tell the desktop to show this block too (Rex's focus follows).
    pub fn focus_block(&self, host_id: &str, session_id: &str, block_id: &str) -> Result<()> {
        self.call(host_id, "session.focus_block", json!({"session_id": session_id, "block_id": block_id}))?;
        Ok(())
    }

    pub fn clear_terminal(&self, host_id: &str, session_id: &str, block_id: &str) -> Result<()> {
        self.call(host_id, &rexc_proto::terminal_method("clear"), json!({"session_id": session_id, "block_id": block_id, "args": {}}))?;
        Ok(())
    }

    // ----- terminals -----

    pub fn open_terminal(&self, host_id: &str, session_id: &str, block_id: &str, take: bool) -> Result<u64> {
        let h = self.host(host_id)?;
        let id = self.next_terminal.fetch_add(1, Ordering::Relaxed);
        let theme = self.theme_for(&h);
        let t = self.terminals.open(id, h, session_id, block_id, take, self.notifier.clone(), self.runtime.handle());
        t.term.lock().set_theme(theme);
        Ok(id)
    }

    pub fn close_terminal(&self, terminal_id: u64) {
        self.terminals.set_visible(terminal_id, false);
        if let Some(t) = self.terminals.remove(terminal_id) {
            t.send(TermCmd::Close);
        }
    }

    /// The terminal the app shows right now (`visible`), or no longer shows. While one is
    /// shown and the app is in the foreground, its block posts no alerts.
    pub fn set_terminal_visible(&self, terminal_id: u64, visible: bool) {
        self.terminals.set_visible(terminal_id, visible);
    }

    fn terminal(&self, id: u64) -> Result<Arc<terminal::TerminalHandle>> {
        self.terminals.get(id).ok_or(EngineError::NoTerminal)
    }

    pub fn terminal_info(&self, id: u64) -> Result<TerminalInfo> {
        Ok(self.terminal(id)?.info())
    }

    pub fn terminal_frame(&self, id: u64) -> Result<Frame> {
        Ok(self.terminal(id)?.term.lock().frame())
    }

    /// The frame with only the rows that changed after revision `since` (a previous
    /// frame's `rev`; 0 for all of them). The rest come back empty and not `dirty`.
    pub fn terminal_frame_since(&self, id: u64, since: u64) -> Result<Frame> {
        Ok(self.terminal(id)?.term.lock().frame_since(since))
    }

    /// The phone's size for this pane. Applied on the server when we own it.
    pub fn terminal_set_viewport(&self, id: u64, cols: u16, rows: u16, cell_w: u32, cell_h: u32) -> Result<()> {
        self.terminal(id)?.send(TermCmd::SetViewport(Viewport { cols: cols.max(2), rows: rows.max(1), cell_w, cell_h }));
        Ok(())
    }

    pub fn terminal_take_over(&self, id: u64) -> Result<()> {
        self.terminal(id)?.send(TermCmd::TakeOver);
        Ok(())
    }

    pub fn terminal_release(&self, id: u64) -> Result<()> {
        self.terminal(id)?.send(TermCmd::Release);
        Ok(())
    }

    pub fn terminal_send_text(&self, id: u64, text: &str) -> Result<()> {
        let t = self.terminal(id)?;
        let mut bytes = Vec::with_capacity(text.len());
        {
            let mut term = t.term.lock();
            let predicted = term.local_echo_typed(text);
            for ch in text.chars() {
                match ch {
                    '\n' | '\r' => bytes.extend(term.encode_key("enter", KeyMods::default(), "", None).unwrap_or_else(|| b"\r".to_vec())),
                    '\t' => bytes.extend(term.encode_key("tab", KeyMods::default(), "", None).unwrap_or_else(|| b"\t".to_vec())),
                    '\u{8}' | '\u{7f}' => bytes.extend(term.encode_key("backspace", KeyMods::default(), "", None).unwrap_or_else(|| b"\x7f".to_vec())),
                    c => {
                        let mut buf = [0u8; 4];
                        bytes.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                    }
                }
            }
            drop(term);
            if predicted {
                self.refresh_prediction(id);
            }
        }
        t.write(bytes);
        Ok(())
    }

    /// Local echo on or off for this terminal (slow links; a prediction only ever shows
    /// after the server has stayed silent for a moment).
    pub fn terminal_set_local_echo(&self, id: u64, on: bool) -> Result<()> {
        self.terminal(id)?.term.lock().set_local_echo(on);
        Ok(())
    }

    /// Wake the UI when a fresh prediction is due to show, and again when it would be dropped.
    fn refresh_prediction(&self, id: u64) {
        let notifier = self.notifier.clone();
        self.runtime.handle().spawn(async move {
            tokio::time::sleep(rexc_term::predict::SHOW_AFTER + Duration::from_millis(4)).await;
            notifier.terminal(id);
            tokio::time::sleep(rexc_term::predict::GIVE_UP_AFTER).await;
            notifier.terminal(id);
        });
    }

    /// A named key (`arrow_up`, `key_c`, `enter`...) with modifiers.
    pub fn terminal_send_key(&self, id: u64, key: &str, mods: KeyMods) -> Result<bool> {
        let t = self.terminal(id)?;
        let (text, codepoint) = key_text(key, mods);
        let (bytes, predicted) = {
            let mut term = t.term.lock();
            let bytes = term.encode_key(key, mods, &text, codepoint);
            // Only a plain typed character is guessed; Enter, Backspace, arrows, Ctrl-C... reset.
            let plain = bytes.is_some() && !(mods.ctrl || mods.alt || mods.meta) && !text.is_empty();
            let predicted = plain && term.local_echo_typed(&text);
            if !plain {
                term.local_echo_reset();
            }
            (bytes, predicted)
        };
        if predicted {
            self.refresh_prediction(id);
        }
        match bytes {
            Some(b) => {
                t.write(b);
                Ok(true)
            }
            None => Ok(false),
        }
    }

    pub fn terminal_paste(&self, id: u64, text: &str) -> Result<()> {
        let t = self.terminal(id)?;
        let bytes = {
            let mut term = t.term.lock();
            term.local_echo_reset();
            term.encode_paste(text)
        };
        t.write(bytes);
        Ok(())
    }

    /// Type text in without pressing Enter: bracketed when the program asked for pastes,
    /// else on one line ([`rexc_term::Terminal::encode_insert`]).
    pub fn terminal_insert(&self, id: u64, text: &str) -> Result<()> {
        let t = self.terminal(id)?;
        let bytes = {
            let mut term = t.term.lock();
            term.local_echo_reset();
            term.encode_insert(text)
        };
        t.write(bytes);
        Ok(())
    }

    /// Scroll history by rows; when a full-screen program tracks the mouse,
    /// send it wheel events at the given cell instead.
    pub fn terminal_scroll(&self, id: u64, delta_rows: i32, col: u16, row: u16) -> Result<()> {
        let t = self.terminal(id)?;
        let mut term = t.term.lock();
        if term.mouse_tracking() {
            let down = delta_rows > 0;
            let mut bytes = Vec::new();
            for _ in 0..delta_rows.unsigned_abs().clamp(1, 10) {
                if let Some(b) = term.wheel(col, row, down) {
                    bytes.extend(b);
                }
            }
            drop(term);
            t.write(bytes);
        } else if term.alternate_screen() {
            // No history on the alternate screen: the program moves instead (xterm's alternate scroll).
            let key = if delta_rows > 0 { "arrow_down" } else { "arrow_up" };
            let mut bytes = Vec::new();
            for _ in 0..delta_rows.unsigned_abs().clamp(1, 10) {
                bytes.extend(term.encode_key(key, KeyMods::default(), "", None).unwrap_or_default());
            }
            drop(term);
            t.write(bytes);
        } else {
            term.scroll(delta_rows as isize);
            drop(term);
            self.notifier.terminal(id);
        }
        Ok(())
    }

    /// A click at a cell for a program that tracks the mouse. False (nothing sent) when it doesn't.
    pub fn terminal_click(&self, id: u64, col: u16, row: u16, right: bool) -> Result<bool> {
        let t = self.terminal(id)?;
        let button = if right { MouseButton::Right } else { MouseButton::Left };
        let bytes = t.term.lock().click(col, row, button);
        Ok(bytes.map(|b| t.write(b)).is_some())
    }

    pub fn terminal_scroll_to_bottom(&self, id: u64) -> Result<()> {
        self.terminal(id)?.term.lock().scroll_to_bottom();
        self.notifier.terminal(id);
        Ok(())
    }

    pub fn terminal_scroll_to_row(&self, id: u64, row: u64) -> Result<()> {
        self.terminal(id)?.term.lock().scroll_to_row(row as usize);
        self.notifier.terminal(id);
        Ok(())
    }

    pub fn terminal_select(&self, id: u64, from_col: u16, from_row: u16, to_col: u16, to_row: u16) -> Result<bool> {
        let ok = self.terminal(id)?.term.lock().select((from_col, from_row), (to_col, to_row));
        self.notifier.terminal(id);
        Ok(ok)
    }

    pub fn terminal_select_word(&self, id: u64, col: u16, row: u16) -> Result<bool> {
        let ok = self.terminal(id)?.term.lock().select_word(col, row);
        self.notifier.terminal(id);
        Ok(ok)
    }

    pub fn terminal_select_line(&self, id: u64, row: u16) -> Result<bool> {
        let ok = self.terminal(id)?.term.lock().select_line(row);
        self.notifier.terminal(id);
        Ok(ok)
    }

    pub fn terminal_clear_selection(&self, id: u64) -> Result<()> {
        self.terminal(id)?.term.lock().clear_selection();
        self.notifier.terminal(id);
        Ok(())
    }

    pub fn terminal_selection_text(&self, id: u64) -> Result<Option<String>> {
        Ok(self.terminal(id)?.term.lock().selection_text())
    }

    pub fn terminal_all_text(&self, id: u64) -> Result<String> {
        Ok(self.terminal(id)?.term.lock().all_text())
    }

    /// A link under a cell: an OSC 8 hyperlink, else a URL printed there, soft-wrapped rows joined.
    pub fn terminal_link_at(&self, id: u64, col: u16, row: u16) -> Result<Option<String>> {
        Ok(self.terminal(id)?.term.lock().link_at(col, row))
    }

    // ----- search in the scrollback -----

    /// Search the terminal's text, smart-case (a capital letter makes it case-sensitive), and
    /// show the match nearest the bottom. Returns how many there are. An empty query clears.
    pub fn terminal_search(&self, id: u64, query: &str) -> Result<u32> {
        let found = self.terminal(id)?.term.lock().search(query, query.chars().any(|c| c.is_ascii_uppercase()));
        self.notifier.terminal(id);
        Ok(found.len() as u32)
    }

    /// Make the match `delta` places on current (positive goes down, wrapping) and scroll it
    /// into view. Returns its index among the matches, or `None` without matches.
    pub fn terminal_search_next(&self, id: u64, delta: i32) -> Result<Option<u32>> {
        let at = self.terminal(id)?.term.lock().search_step(delta as isize);
        self.notifier.terminal(id);
        Ok(at.map(|i| i as u32))
    }

    pub fn terminal_search_clear(&self, id: u64) -> Result<()> {
        self.terminal(id)?.term.lock().search_clear();
        self.notifier.terminal(id);
        Ok(())
    }
}

/// What a named key types without modifiers, for the encoder.
/// The theme a host's terminals draw: the host's, else the app's; `None` for the server's
/// colors. A name that is not built in counts as unset.
fn effective_theme(host: Option<&str>, app: Option<&str>) -> Option<Theme> {
    host.and_then(themes::builtin).or_else(|| app.and_then(themes::builtin))
}

fn key_text(key: &str, mods: KeyMods) -> (String, Option<char>) {
    let c = match key.strip_prefix("key_") {
        Some(l) if l.len() == 1 => l.chars().next(),
        _ => match key.strip_prefix("digit_") {
            Some(d) if d.len() == 1 => d.chars().next(),
            _ => match key {
                "space" => Some(' '),
                "minus" => Some('-'),
                "equal" => Some('='),
                "bracket_left" => Some('['),
                "bracket_right" => Some(']'),
                "backslash" => Some('\\'),
                "semicolon" => Some(';'),
                "quote" => Some('\''),
                "comma" => Some(','),
                "period" => Some('.'),
                "slash" => Some('/'),
                "backquote" => Some('`'),
                _ => None,
            },
        },
    };
    let Some(c) = c else { return (String::new(), None) };
    let typed = if mods.shift { shifted(c) } else { c };
    (typed.to_string(), Some(c))
}

fn shifted(c: char) -> char {
    match c {
        'a'..='z' => c.to_ascii_uppercase(),
        '1' => '!',
        '2' => '@',
        '3' => '#',
        '4' => '$',
        '5' => '%',
        '6' => '^',
        '7' => '&',
        '8' => '*',
        '9' => '(',
        '0' => ')',
        '-' => '_',
        '=' => '+',
        '[' => '{',
        ']' => '}',
        '\\' => '|',
        ';' => ':',
        '\'' => '"',
        ',' => '<',
        '.' => '>',
        '/' => '?',
        '`' => '~',
        other => other,
    }
}

const QUICK_REPLY_TIMEOUT: Duration = Duration::from_secs(12);
const PREVIEW_BYTES: u64 = 16 * 1024;

/// The host's control connection, waiting for one being made; the host's error when the attempt fails.
async fn wait_for_control(h: &HostHandle, timeout: Duration) -> Result<rexc_client::Control> {
    let start = tokio::time::Instant::now();
    let mut tried = false;
    loop {
        if let Some(c) = h.control() {
            return Ok(c);
        }
        let (status, error) = {
            let st = h.state.lock();
            (st.status, st.error.clone())
        };
        tried |= status != HostStatus::Offline;
        if tried && status == HostStatus::Offline {
            return Err(EngineError::Connect(error.unwrap_or_else(|| "host is not connected".into())));
        }
        if start.elapsed() >= timeout {
            return Err(EngineError::Connect(error.unwrap_or_else(|| "timed out connecting".into())));
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

/// What a quick reply types: the text without trailing newlines, then Enter if asked.
fn reply_data(text: &str, enter: bool) -> String {
    let text = text.trim_end_matches(['\r', '\n']);
    if enter { format!("{text}\r") } else { text.to_owned() }
}

/// Whether a block still waits on `prompt`: some program in it is Blocked with the same kind
/// and, when both have one, the same message. A missing block waits on nothing.
fn still_waiting(block: Option<&BlockState>, prompt: &Prompt) -> bool {
    block.is_some_and(|b| {
        b.programs.iter().any(|p| {
            let same_msg = match (&p.msg, &prompt.msg) {
                (Some(now), Some(then)) => now == then,
                _ => true,
            };
            p.state == ProgramState::Blocked && p.kind == prompt.kind && same_msg
        })
    })
}

/// The actions a person can pick from a `client.keymap` reply: those with a title.
/// Block methods have none (they are for bindings), and the client's own are ours to list.
fn parse_actions(reply: &Value) -> Vec<Action> {
    let text = |a: &Value, key: &str| a[key].as_str().unwrap_or_default().trim().to_owned();
    reply["actions"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|a| a["kind"].as_str() != Some("client"))
        .map(|a| Action { name: text(a, "name"), title: text(a, "title"), description: text(a, "description"), category: text(a, "category"), keywords: text(a, "keywords") })
        .filter(|a| !a.name.is_empty() && !a.title.is_empty())
        .collect()
}

/// The last `n` non-blank lines, joined.
fn tail_lines(text: &str, n: usize) -> String {
    let lines: Vec<&str> = text.lines().map(str::trim_end).filter(|l| !l.is_empty()).collect();
    lines[lines.len().saturating_sub(n)..].join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_host_theme_overrides_the_app_theme_which_overrides_the_server() {
        assert_eq!(effective_theme(Some("Snazzy"), Some("Nightfox")), themes::builtin("Snazzy"));
        assert_eq!(effective_theme(None, Some("Nightfox")), themes::builtin("Nightfox"));
        assert_eq!(effective_theme(Some("gone"), Some("Nightfox")), themes::builtin("Nightfox"));
        assert!(effective_theme(None, None).is_none());
        assert!(effective_theme(Some("gone"), None).is_none());
    }

    #[test]
    fn tail_lines_keeps_the_last_non_blank_lines() {
        assert_eq!(tail_lines("a\n\nb  \nc\n\n", 2), "b\nc");
        assert_eq!(tail_lines("only", 4), "only");
        assert_eq!(tail_lines("\n \n", 4), "");
    }

    #[test]
    fn reply_data_ends_with_enter_only_when_asked() {
        assert_eq!(reply_data("y\n", true), "y\r");
        assert_eq!(reply_data("y", false), "y");
        assert_eq!(reply_data("\u{1b}", false), "\u{1b}");
    }

    #[test]
    fn a_reply_goes_out_only_while_its_prompt_is_open() {
        let status = |state, kind: &str, msg: Option<&str>| ProgramStatus { state, kind: Some(kind.into()), msg: msg.map(Into::into), ..Default::default() };
        let block = |p: ProgramStatus| BlockState { programs: vec![p], ..Default::default() };
        let prompt = Prompt { kind: Some("permission".into()), msg: Some("Allow Bash?".into()) };
        // Still blocked on the same question: send.
        assert!(still_waiting(Some(&block(status(ProgramState::Blocked, "permission", Some("Allow Bash?")))), &prompt));
        // A message missing on either side does not block the match.
        assert!(still_waiting(Some(&block(status(ProgramState::Blocked, "permission", None))), &prompt));
        assert!(still_waiting(Some(&block(status(ProgramState::Blocked, "permission", Some("x")))), &Prompt { msg: None, ..prompt.clone() }));
        // Kind changed, message changed, moved on, or the block is gone: refuse.
        assert!(!still_waiting(Some(&block(status(ProgramState::Blocked, "question", Some("Allow Bash?")))), &prompt));
        assert!(!still_waiting(Some(&block(status(ProgramState::Blocked, "permission", Some("Allow Edit?")))), &prompt));
        assert!(!still_waiting(Some(&block(status(ProgramState::Working, "permission", Some("Allow Bash?")))), &prompt));
        assert!(!still_waiting(Some(&block(status(ProgramState::Done, "permission", Some("Allow Bash?")))), &prompt));
        assert!(!still_waiting(Some(&BlockState::default()), &prompt));
        assert!(!still_waiting(None, &prompt));
    }

    #[test]
    fn parses_only_titled_server_actions() {
        let reply = json!({"actions": [
            {"name": "dev_layout", "kind": "lua", "title": " Open Dev Layout ", "category": "Actions", "keywords": "dev"},
            {"name": "com.superlogical.terminal.reset", "kind": "block", "title": "Reset Terminal", "description": "Clear state"},
            {"name": "com.superlogical.terminal.write", "kind": "block", "args": {}},
            {"name": "pane.split", "kind": "client", "title": "Split Pane"},
        ]});
        let got = parse_actions(&reply);
        assert_eq!(got.iter().map(|a| a.name.as_str()).collect::<Vec<_>>(), ["dev_layout", "com.superlogical.terminal.reset"]);
        assert_eq!(got[0], Action { name: "dev_layout".into(), title: "Open Dev Layout".into(), category: "Actions".into(), keywords: "dev".into(), ..Default::default() });
        assert_eq!(got[1].description, "Clear state");
        assert!(parse_actions(&json!({})).is_empty());
    }

    #[test]
    fn key_text_shifts() {
        assert_eq!(key_text("key_a", KeyMods { shift: true, ..Default::default() }), ("A".into(), Some('a')));
        assert_eq!(key_text("digit_1", KeyMods { shift: true, ..Default::default() }), ("!".into(), Some('1')));
        assert_eq!(key_text("arrow_up", KeyMods::default()), (String::new(), None));
    }
}
