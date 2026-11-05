//! The server's model: sessions, windows, layouts, blocks, program status, and
//! the typed events that change them.

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionSummary {
    pub session_id: String,
    #[serde(default)]
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct SessionView {
    #[serde(default)]
    pub revision: u64,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub active_window_id: Option<String>,
    #[serde(default)]
    pub windows: Vec<WindowView>,
}

impl SessionView {
    pub fn window(&self, id: &str) -> Option<&WindowView> {
        self.windows.iter().find(|w| w.window_id.as_deref() == Some(id))
    }

    pub fn active_window(&self) -> Option<&WindowView> {
        self.active_window_id.as_deref().and_then(|id| self.window(id)).or(self.windows.first())
    }

    /// Every block in every window, in layout order.
    pub fn blocks(&self) -> impl Iterator<Item = (&WindowView, &BlockView)> {
        self.windows.iter().flat_map(|w| w.blocks().map(move |b| (w, b)))
    }

    pub fn find_block(&self, block_id: &str) -> Option<(&WindowView, &BlockView)> {
        self.blocks().find(|(_, b)| b.block_id.as_deref() == Some(block_id))
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct WindowView {
    #[serde(default)]
    pub window_id: Option<String>,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub focused_block_id: Option<String>,
    #[serde(default)]
    pub zoomed_block_id: Option<String>,
    #[serde(default)]
    pub layers: Vec<LayerView>,
}

impl WindowView {
    /// Blocks of every layer, the tiled layer first, in layout order.
    pub fn blocks(&self) -> impl Iterator<Item = &BlockView> {
        self.layers.iter().filter(|l| l.state.as_deref() != Some("minimized")).flat_map(|l| l.blocks.iter())
    }

    /// The tiled layer's blocks, left-to-right / top-to-bottom as the layout places them.
    pub fn tiled_blocks(&self) -> Vec<&BlockView> {
        let Some(layer) = self.layers.iter().find(|l| l.kind == "tiled").or(self.layers.first()) else {
            return Vec::new();
        };
        let mut ordered = Vec::new();
        if let Some(root) = &layer.layout {
            root.leaves(&mut ordered);
        }
        ordered
            .into_iter()
            .filter_map(|id| layer.blocks.iter().find(|b| b.block_id.as_deref() == Some(id)))
            .collect()
    }

    pub fn focused_block(&self) -> Option<&BlockView> {
        self.focused_block_id.as_deref().and_then(|id| self.blocks().find(|b| b.block_id.as_deref() == Some(id)))
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
pub struct LayerView {
    #[serde(default)]
    pub layer_id: Option<String>,
    #[serde(default)]
    pub kind: String,
    #[serde(default = "full_rect")]
    pub bounds: [f64; 4],
    #[serde(default)]
    pub state: Option<String>,
    #[serde(default)]
    pub layout: Option<LayoutNode>,
    #[serde(default)]
    pub blocks: Vec<BlockView>,
}

fn full_rect() -> [f64; 4] {
    [0.0, 0.0, 1.0, 1.0]
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LayoutNode {
    #[serde(default = "full_rect")]
    pub rect: [f64; 4],
    #[serde(default)]
    pub block_id: Option<String>,
    #[serde(default)]
    pub split: Option<Box<Split>>,
}

impl LayoutNode {
    pub fn leaves<'a>(&'a self, out: &mut Vec<&'a str>) {
        if let Some(id) = &self.block_id {
            out.push(id);
        }
        if let Some(s) = &self.split {
            s.before.leaves(out);
            s.after.leaves(out);
        }
    }

    /// Rect of a block as fractions of the window.
    pub fn rect_of(&self, block_id: &str) -> Option<[f64; 4]> {
        if self.block_id.as_deref() == Some(block_id) {
            return Some(self.rect);
        }
        let s = self.split.as_ref()?;
        s.before.rect_of(block_id).or_else(|| s.after.rect_of(block_id))
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Split {
    #[serde(default)]
    pub split_id: Option<String>,
    #[serde(default)]
    pub direction: String,
    #[serde(default)]
    pub ratio: f64,
    pub before: LayoutNode,
    pub after: LayoutNode,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct BlockView {
    #[serde(default)]
    pub block_id: Option<String>,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub creator_name: String,
    #[serde(default)]
    pub flavor: String,
    #[serde(default)]
    pub host_id: Option<String>,
    #[serde(default)]
    pub owner: Option<String>,
}

impl BlockView {
    pub fn is_terminal(&self) -> bool {
        self.creator_name == crate::TERMINAL_CREATOR || self.creator_name.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct DataTicket {
    pub session_id: String,
    pub block_id: String,
    #[serde(default)]
    pub data_ref_id: String,
    pub ticket: String,
    #[serde(default)]
    pub expires_in_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Deserialize, Default)]
pub struct TailscaleStatus {
    #[serde(default)]
    pub state: String,
    #[serde(default)]
    pub dns_name: Option<String>,
    #[serde(default)]
    pub https: Option<HttpsStatus>,
}

#[derive(Debug, Clone, PartialEq, Deserialize, Default)]
pub struct HttpsStatus {
    #[serde(default)]
    pub state: String,
    #[serde(default)]
    pub url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Deserialize, Default)]
pub struct ServerStatus {
    #[serde(default)]
    pub pid: i64,
    #[serde(default)]
    pub instance_id: String,
    #[serde(default)]
    pub host_id: Option<String>,
    #[serde(default)]
    pub started_at: String,
    #[serde(default)]
    pub session_count: u64,
    #[serde(default)]
    pub api_revision: u32,
    #[serde(default)]
    pub tailscale: Option<TailscaleStatus>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct AttachedSession {
    pub session: SessionSummary,
    pub view: SessionView,
}

#[derive(Debug, Clone, PartialEq, Deserialize, Default)]
pub struct AttachAll {
    #[serde(default)]
    pub sessions: Vec<SessionSummary>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct BootstrapResult {
    pub client_id: String,
    #[serde(default)]
    pub server_status: ServerStatus,
    #[serde(default)]
    pub attach_all: AttachAll,
    #[serde(default)]
    pub selected_session: Option<AttachedSession>,
    #[serde(default)]
    pub attached_sessions: Vec<AttachedSession>,
    #[serde(default)]
    pub data_tickets: Vec<DataTicket>,
}

/// One process, as the terminal reports its foreground.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ProcessInfo {
    #[serde(default)]
    pub pid: i64,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub argv0: Option<String>,
    #[serde(default)]
    pub script: Option<String>,
    #[serde(default)]
    pub cwd: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
}

impl ProcessInfo {
    /// The label Rex suggests: script, then argv0, then name.
    pub fn display_name(&self) -> String {
        if let Some(s) = &self.script {
            if let Some(base) = s.rsplit('/').next() {
                return base.to_string();
            }
        }
        if let Some(a) = &self.argv0 {
            let a = a.trim_start_matches('-');
            if let Some(base) = a.rsplit('/').next() {
                if !base.is_empty() {
                    return base.to_string();
                }
            }
        }
        self.name.clone()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default, Hash, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum ProgramState {
    #[default]
    Idle,
    Working,
    Done,
    Blocked,
    Error,
}

impl ProgramState {
    pub fn parse(s: &str) -> Self {
        match s {
            "working" => Self::Working,
            "done" => Self::Done,
            "blocked" => Self::Blocked,
            "error" => Self::Error,
            _ => Self::Idle,
        }
    }
}

/// A program's status report (OSC 7501).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ProgramStatus {
    #[serde(default)]
    pub id: String,
    #[serde(default, deserialize_with = "state_from_str")]
    pub state: ProgramState,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub progress: Option<u8>,
    #[serde(default)]
    pub app: Option<String>,
    #[serde(default)]
    pub effective_app: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub msg: Option<String>,
    #[serde(default)]
    pub updated_at: Option<String>,
}

fn state_from_str<'de, D: serde::Deserializer<'de>>(d: D) -> Result<ProgramState, D::Error> {
    let s: Option<String> = Option::deserialize(d)?;
    Ok(s.as_deref().map(ProgramState::parse).unwrap_or_default())
}

/// A block's event, decoded from `block_event` by its name.
#[derive(Debug, Clone, PartialEq)]
pub enum TerminalEvent {
    Bell,
    ChildExited { exit_code: i64, error: Option<String> },
    ClipboardWritten { text: Option<String>, id: Option<u64>, bytes: Option<u64> },
    DesktopNotification { title: String, body: String },
    ProcessChanged { foreground: Option<ProcessInfo> },
    ProgramStatusChanged { id: String, record: ProgramStatus },
    ProgramStatusRemoved { ids: Vec<String>, reason: String },
    PwdChanged { pwd: String },
    TitleChanged { title: String },
    SizeChanged { columns: u32, rows: u32 },
    UrlClicked { url: String },
    Other(String),
}

impl TerminalEvent {
    pub fn decode(name: &str, p: &Value) -> Self {
        let s = |k: &str| p.get(k).and_then(Value::as_str).map(str::to_owned);
        match name {
            "bell" => Self::Bell,
            "child_exited" => Self::ChildExited { exit_code: p["exit_code"].as_i64().unwrap_or(-1), error: s("error") },
            "clipboard_written" => Self::ClipboardWritten { text: s("text"), id: p["id"].as_u64(), bytes: p["bytes"].as_u64() },
            "desktop_notification" => Self::DesktopNotification { title: s("title").unwrap_or_default(), body: s("body").unwrap_or_default() },
            "process_changed" => Self::ProcessChanged { foreground: serde_json::from_value(p["foreground"].clone()).ok() },
            "program_status_changed" => Self::ProgramStatusChanged {
                id: s("id").unwrap_or_default(),
                record: serde_json::from_value(p["record"].clone()).unwrap_or_default(),
            },
            "program_status_removed" => Self::ProgramStatusRemoved {
                ids: p["ids"].as_array().map(|a| a.iter().filter_map(Value::as_str).map(str::to_owned).collect()).unwrap_or_default(),
                reason: s("reason").unwrap_or_default(),
            },
            "pwd_changed" => Self::PwdChanged { pwd: s("pwd").unwrap_or_default() },
            "title_changed" => Self::TitleChanged { title: s("title").unwrap_or_default() },
            "size_changed" => Self::SizeChanged { columns: p["columns"].as_u64().unwrap_or(0) as u32, rows: p["rows"].as_u64().unwrap_or(0) as u32 },
            "url_clicked" => Self::UrlClicked { url: s("url").unwrap_or_default() },
            other => Self::Other(other.to_owned()),
        }
    }
}

/// An event inside one session.
#[derive(Debug, Clone, PartialEq)]
pub enum SessionEvent {
    ActiveWindowChanged { window_id: Option<String> },
    BlockCreated { block_id: String },
    BlockClosed { block_id: String },
    BlockOwnerChanged { block_id: String, owner: Option<String>, previous: Option<String>, reason: String },
    Block { block_id: String, event: TerminalEvent },
    ClientConnected { client_id: String, display: String },
    ClientDisconnected { client_id: String },
    SessionLabelChanged { label: String },
    ViewChanged { revision: u64 },
    WindowCreated { window_id: String, label: String },
    WindowClosed { window_id: String },
    WindowLabelChanged { window_id: String, label: String },
    Other(String),
}

/// Any event on the control connection, decoded by name.
#[derive(Debug, Clone, PartialEq)]
pub enum ServerEvent {
    SessionCreated(SessionSummary),
    SessionDestroyed { session_id: String },
    SessionMoved { session_id: String, before_session_id: Option<String> },
    TailscaleStatusChanged(TailscaleStatus),
    Session { session_id: String, event: SessionEvent },
    Other(String),
}

impl ServerEvent {
    pub fn decode(name: &str, p: &Value) -> Self {
        let s = |k: &str| p.get(k).and_then(Value::as_str).map(str::to_owned);
        match name {
            "session_created" => serde_json::from_value(p["session"].clone()).map(Self::SessionCreated).unwrap_or(Self::Other(name.into())),
            "session_destroyed" => Self::SessionDestroyed { session_id: s("session_id").unwrap_or_default() },
            "session_moved" => Self::SessionMoved { session_id: s("session_id").unwrap_or_default(), before_session_id: s("before_session_id") },
            "tailscale_status_changed" => Self::TailscaleStatusChanged(serde_json::from_value(p.clone()).unwrap_or_default()),
            "session_event" => {
                let session_id = s("session_id").unwrap_or_default();
                let inner = &p["event"];
                let inner_name = inner["type"].as_str().unwrap_or_default();
                let q = &inner["payload"];
                let qs = |k: &str| q.get(k).and_then(Value::as_str).map(str::to_owned);
                let event = match inner_name {
                    "active_window_changed" => SessionEvent::ActiveWindowChanged { window_id: qs("window_id") },
                    "block_created" => SessionEvent::BlockCreated { block_id: qs("block_id").unwrap_or_default() },
                    "block_closed" => SessionEvent::BlockClosed { block_id: qs("block_id").unwrap_or_default() },
                    "block_owner_changed" => SessionEvent::BlockOwnerChanged {
                        block_id: qs("block_id").unwrap_or_default(),
                        owner: qs("owner"),
                        previous: qs("previous"),
                        reason: qs("reason").unwrap_or_default(),
                    },
                    "block_event" => SessionEvent::Block {
                        block_id: qs("block_id").unwrap_or_default(),
                        event: TerminalEvent::decode(q["name"].as_str().unwrap_or_default(), q.get("payload").unwrap_or(&Value::Null)),
                    },
                    "client_connected" => SessionEvent::ClientConnected {
                        client_id: qs("client_id").unwrap_or_default(),
                        display: q["principal"]["display"].as_str().unwrap_or_default().to_owned(),
                    },
                    "client_disconnected" => SessionEvent::ClientDisconnected { client_id: qs("client_id").unwrap_or_default() },
                    "session_label_changed" => SessionEvent::SessionLabelChanged { label: qs("label").unwrap_or_default() },
                    "session_view_changed" => SessionEvent::ViewChanged { revision: q["revision"].as_u64().unwrap_or(0) },
                    "window_created" => SessionEvent::WindowCreated { window_id: qs("window_id").unwrap_or_default(), label: qs("label").unwrap_or_default() },
                    "window_closed" => SessionEvent::WindowClosed { window_id: qs("window_id").unwrap_or_default() },
                    "window_label_changed" => SessionEvent::WindowLabelChanged { window_id: qs("window_id").unwrap_or_default(), label: qs("label").unwrap_or_default() },
                    other => SessionEvent::Other(other.to_owned()),
                };
                Self::Session { session_id, event }
            }
            other => Self::Other(other.to_owned()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn view() -> SessionView {
        serde_json::from_value(json!({
            "revision": 3, "session_id": "session:a", "label": "work", "active_window_id": "window:1",
            "windows": [{
                "window_id": "window:1", "label": "editor", "focused_block_id": "block:2",
                "layers": [{
                    "layer_id": "layer:1", "kind": "tiled", "bounds": [0,0,1,1],
                    "layout": {"rect": [0,0,1,1], "split": {"split_id": "split:1", "direction": "horizontal", "ratio": 0.5,
                        "before": {"rect": [0,0,0.5,1], "block_id": "block:1"},
                        "after": {"rect": [0.5,0,0.5,1], "block_id": "block:2"}}},
                    "blocks": [
                        {"block_id": "block:2", "label": "", "creator_name": "com.superlogical.terminal", "flavor": "com.superlogical.terminal.shell", "owner": "client:9"},
                        {"block_id": "block:1", "label": "shell", "creator_name": "com.superlogical.terminal"}
                    ]
                }]
            }]
        }))
        .unwrap()
    }

    #[test]
    fn view_walks_layout_in_order() {
        let v = view();
        let w = v.active_window().unwrap();
        let ids: Vec<_> = w.tiled_blocks().iter().map(|b| b.block_id.clone().unwrap()).collect();
        assert_eq!(ids, ["block:1", "block:2"]);
        assert_eq!(w.focused_block().unwrap().owner.as_deref(), Some("client:9"));
        assert_eq!(w.layers[0].layout.as_ref().unwrap().rect_of("block:2"), Some([0.5, 0.0, 0.5, 1.0]));
        assert_eq!(v.find_block("block:1").unwrap().1.label, "shell");
    }

    #[test]
    fn decodes_session_and_block_events() {
        let e = ServerEvent::decode(
            "session_event",
            &json!({"session_id": "session:a", "event": {"type": "block_event", "payload": {
                "block_id": "block:1", "creator_name": "com.superlogical.terminal", "name": "program_status_changed",
                "payload": {"id": "", "record": {"state": "blocked", "kind": "permission", "app": "claude", "msg": "Allow edit?"}}}}}),
        );
        match e {
            ServerEvent::Session { session_id, event: SessionEvent::Block { block_id, event: TerminalEvent::ProgramStatusChanged { record, .. } } } => {
                assert_eq!(session_id, "session:a");
                assert_eq!(block_id, "block:1");
                assert_eq!(record.state, ProgramState::Blocked);
                assert_eq!(record.kind.as_deref(), Some("permission"));
                assert_eq!(record.msg.as_deref(), Some("Allow edit?"));
            }
            other => panic!("{other:?}"),
        }
        let v = ServerEvent::decode("session_event", &json!({"session_id": "session:a", "event": {"type": "session_view_changed", "payload": {"revision": 12}}}));
        assert_eq!(v, ServerEvent::Session { session_id: "session:a".into(), event: SessionEvent::ViewChanged { revision: 12 } });
        let p = TerminalEvent::decode("process_changed", &json!({"generation": 2, "foreground": {"pid": 1, "user_id": 0, "name": "node", "script": "/x/y/claude.js", "cwd": "/home"}}));
        match p {
            TerminalEvent::ProcessChanged { foreground: Some(f) } => assert_eq!(f.display_name(), "claude.js"),
            other => panic!("{other:?}"),
        }
        assert_eq!(ServerEvent::decode("new_thing", &json!({})), ServerEvent::Other("new_thing".into()));
    }

    #[test]
    fn bootstrap_result_parses() {
        let r: BootstrapResult = serde_json::from_value(json!({
            "client_id": "client:1",
            "server_status": {"pid": 1, "instance_id": "i", "host_id": "h", "started_at": "t", "exit_empty": false, "session_count": 1, "config": {"generation": 1, "hash": ""}},
            "attach_all": {"sessions": [{"session_id": "session:a", "label": "work"}]},
            "selected_session": null,
            "attached_sessions": [{"session": {"session_id": "session:a", "label": "work"}, "view": {"revision": 1, "session_id": "session:a", "windows": []}}],
            "data_tickets": [{"session_id": "session:a", "block_id": "block:1", "data_ref_id": "terminal", "ticket": "AAECAwQFBgcICQoLDA0ODw", "expires_in_ms": 60000}]
        }))
        .unwrap();
        assert_eq!(r.attached_sessions[0].view.revision, 1);
        assert_eq!(r.data_tickets[0].block_id, "block:1");
        assert_eq!(r.server_status.host_id.as_deref(), Some("h"));
    }
}
