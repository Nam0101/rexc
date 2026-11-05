//! What the UI reads: one tree per host, plus the inbox built from it.
//! Plain data, cloned out from behind the engine's locks.

use rexc_proto::model::{ProcessInfo, ProgramState, ProgramStatus, SessionSummary, SessionView};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum HostStatus {
    #[default]
    Offline,
    Connecting,
    Connected,
    Reconnecting,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct HostState {
    pub host_id: String,
    pub name: String,
    pub address: String,
    pub status: HostStatus,
    /// Our client id on that server, once connected.
    pub client_id: Option<String>,
    /// The server's own host id (same machine as a block's `host_id`).
    pub server_host_id: Option<String>,
    pub error: Option<String>,
    pub sessions: Vec<SessionState>,
    /// Labels of other clients attached to any session, by client id.
    pub clients: HashMap<String, String>,
}

impl HostState {
    pub fn session(&self, id: &str) -> Option<&SessionState> {
        self.sessions.iter().find(|s| s.session_id == id)
    }

    pub fn session_mut(&mut self, id: &str) -> Option<&mut SessionState> {
        self.sessions.iter_mut().find(|s| s.session_id == id)
    }

    pub fn block(&self, session_id: &str, block_id: &str) -> Option<&BlockState> {
        self.session(session_id)?.block(block_id)
    }

    pub fn block_mut(&mut self, session_id: &str, block_id: &str) -> Option<&mut BlockState> {
        self.session_mut(session_id)?.block_mut(block_id)
    }

    /// Who holds a block, for people: "you", a client label, or none.
    pub fn owner_label(&self, owner: Option<&str>) -> Option<String> {
        let owner = owner?;
        if self.client_id.as_deref() == Some(owner) {
            return Some("this phone".into());
        }
        Some(self.clients.get(owner).cloned().unwrap_or_else(|| "another client".into()))
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct SessionState {
    pub session_id: String,
    pub label: String,
    pub revision: u64,
    /// The window the desktop shows; the phone keeps its own.
    pub active_window_id: Option<String>,
    pub windows: Vec<WindowState>,
    /// Metadata for blocks that are no longer in a window, kept until the block closes.
    pub loaded: bool,
}

impl SessionState {
    pub fn window(&self, id: &str) -> Option<&WindowState> {
        self.windows.iter().find(|w| w.window_id == id)
    }

    pub fn block(&self, id: &str) -> Option<&BlockState> {
        self.windows.iter().flat_map(|w| w.blocks.iter()).find(|b| b.block_id == id)
    }

    pub fn block_mut(&mut self, id: &str) -> Option<&mut BlockState> {
        self.windows.iter_mut().flat_map(|w| w.blocks.iter_mut()).find(|b| b.block_id == id)
    }

    pub fn window_of(&self, block_id: &str) -> Option<&WindowState> {
        self.windows.iter().find(|w| w.blocks.iter().any(|b| b.block_id == block_id))
    }

    /// True while something in the session works or waits.
    pub fn busy(&self) -> bool {
        self.windows.iter().any(WindowState::busy)
    }

    /// Replace structure from a new view, keeping per-block metadata.
    pub fn apply_view(&mut self, view: &SessionView) {
        if view.revision < self.revision && self.loaded {
            return;
        }
        let mut old: HashMap<String, BlockState> = self.windows.drain(..).flat_map(|w| w.blocks).map(|b| (b.block_id.clone(), b)).collect();
        self.revision = view.revision;
        if !view.label.is_empty() {
            self.label = view.label.clone();
        }
        self.active_window_id = view.active_window_id.clone();
        self.loaded = true;
        for w in &view.windows {
            let Some(window_id) = w.window_id.clone() else { continue };
            let tiled = w.layers.iter().find(|l| l.kind == "tiled").or(w.layers.first());
            let mut blocks = Vec::new();
            for b in w.tiled_blocks().into_iter().chain(w.blocks().filter(|b| !w.tiled_blocks().contains(b))) {
                let Some(id) = b.block_id.clone() else { continue };
                if blocks.iter().any(|x: &BlockState| x.block_id == id) {
                    continue;
                }
                let mut state = old.remove(&id).unwrap_or_else(|| BlockState { block_id: id.clone(), ..Default::default() });
                state.label = b.label.clone();
                state.flavor = b.flavor.clone();
                state.creator = b.creator_name.clone();
                state.owner = b.owner.clone();
                state.rect = tiled.and_then(|l| l.layout.as_ref()).and_then(|n| n.rect_of(&id)).unwrap_or([0.0, 0.0, 1.0, 1.0]);
                blocks.push(state);
            }
            blocks.sort_by(|a, b| a.rect[1].partial_cmp(&b.rect[1]).unwrap_or(std::cmp::Ordering::Equal).then(a.rect[0].partial_cmp(&b.rect[0]).unwrap_or(std::cmp::Ordering::Equal)));
            self.windows.push(WindowState {
                window_id,
                label: w.label.clone(),
                focused_block_id: w.focused_block_id.clone(),
                zoomed_block_id: w.zoomed_block_id.clone(),
                blocks,
            });
        }
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct WindowState {
    pub window_id: String,
    pub label: String,
    pub focused_block_id: Option<String>,
    pub zoomed_block_id: Option<String>,
    /// Blocks top-to-bottom, left-to-right as the layout places them.
    pub blocks: Vec<BlockState>,
}

impl WindowState {
    pub fn busy(&self) -> bool {
        self.blocks.iter().any(|b| matches!(b.program_state(), Some(ProgramState::Working | ProgramState::Blocked)))
    }

    pub fn focused(&self) -> Option<&BlockState> {
        self.focused_block_id.as_deref().and_then(|id| self.blocks.iter().find(|b| b.block_id == id)).or(self.blocks.first())
    }

    /// The name to show for a tab: a label a person set, the focused pane's
    /// title (if a program set it), the program, then the window's own label.
    pub fn display_name(&self) -> String {
        if !self.label.is_empty() && !is_generated_label(&self.label) {
            return self.label.clone();
        }
        if let Some(b) = self.focused() {
            if let Some(t) = b.display_title() {
                return t;
            }
            if let Some(p) = b.app_name() {
                return p;
            }
        }
        self.label.clone()
    }
}

/// Rex's generated window labels look like `Window 89abcdef`.
pub fn is_generated_label(label: &str) -> bool {
    label.strip_prefix("Window ").is_some_and(|rest| rest.len() == 8 && rest.chars().all(|c| c.is_ascii_hexdigit()))
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct BlockState {
    pub block_id: String,
    pub label: String,
    pub creator: String,
    pub flavor: String,
    pub owner: Option<String>,
    /// [x, y, w, h] fractions of the window.
    pub rect: [f64; 4],
    pub title: Option<String>,
    pub pwd: Option<String>,
    /// Foreground process name (claude, nvim, zsh...).
    pub process: Option<String>,
    pub process_info: Option<ProcessInfo>,
    /// Program status records by id ("" is the root).
    pub programs: Vec<ProgramStatus>,
    pub exit_code: Option<i64>,
}

impl BlockState {
    /// The most urgent program status: blocked > error > working > done > idle.
    pub fn program(&self) -> Option<&ProgramStatus> {
        self.programs.iter().max_by_key(|p| match p.state {
            ProgramState::Blocked => 5,
            ProgramState::Error => 4,
            ProgramState::Working => 3,
            ProgramState::Done => 2,
            ProgramState::Idle => 1,
        })
    }

    pub fn program_state(&self) -> Option<ProgramState> {
        self.program().map(|p| p.state)
    }

    /// The program's name: the most urgent record that names one, else the foreground process.
    pub fn app_name(&self) -> Option<String> {
        let urgent = self.program().and_then(|p| p.effective_app.clone().or(p.app.clone()));
        urgent.or_else(|| self.programs.iter().find_map(|p| p.effective_app.clone().or(p.app.clone()))).or_else(|| self.process.clone())
    }

    /// A title a program set, unless it's a shell's prompt-ish title.
    pub fn display_title(&self) -> Option<String> {
        let t = self.title.as_deref()?.trim();
        if t.is_empty() || looks_like_shell_title(t) {
            return None;
        }
        Some(t.to_string())
    }

    /// Home directory shortened: `/Users/me/src` → `~/src` is the UI's job; this just gives the last component.
    pub fn pwd_short(&self) -> Option<String> {
        let p = self.pwd.as_deref()?;
        let p = p.strip_prefix("file://").map(|rest| rest.split_once('/').map(|(_, path)| format!("/{path}")).unwrap_or_default()).unwrap_or(p.to_string());
        Some(p.trim_end_matches('/').rsplit('/').next().filter(|s| !s.is_empty()).unwrap_or("/").to_string())
    }
}

/// `user@host:dir`, `dir`, or a command line the shell wrote into the title.
fn looks_like_shell_title(t: &str) -> bool {
    let at_colon = t.contains('@') && t.contains(':');
    let path_only = t.starts_with('/') || t.starts_with('~');
    at_colon || path_only
}

/// A program that needs you, is working, or just finished.
#[derive(Debug, Clone, PartialEq)]
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

pub fn inbox(hosts: &[HostState]) -> Vec<InboxItem> {
    let mut items = Vec::new();
    for h in hosts {
        for s in &h.sessions {
            for w in &s.windows {
                for b in &w.blocks {
                    let Some(p) = b.program() else { continue };
                    if p.state == ProgramState::Idle {
                        continue;
                    }
                    items.push(InboxItem {
                        host_id: h.host_id.clone(),
                        host_name: h.name.clone(),
                        session_id: s.session_id.clone(),
                        session_label: s.label.clone(),
                        window_id: w.window_id.clone(),
                        window_label: w.display_name(),
                        block_id: b.block_id.clone(),
                        app: b.app_name().unwrap_or_else(|| "program".into()),
                        state: p.state,
                        kind: p.kind.clone(),
                        title: p.title.clone(),
                        message: p.msg.clone(),
                        updated_at: p.updated_at.clone(),
                    });
                }
            }
        }
    }
    let rank = |s: ProgramState| match s {
        ProgramState::Blocked => 0,
        ProgramState::Error => 1,
        ProgramState::Working => 2,
        ProgramState::Done => 3,
        ProgramState::Idle => 4,
    };
    items.sort_by(|a, b| rank(a.state).cmp(&rank(b.state)).then_with(|| b.updated_at.cmp(&a.updated_at)));
    items
}

pub fn summaries_to_sessions(list: &[SessionSummary]) -> Vec<SessionState> {
    list.iter().map(|s| SessionState { session_id: s.session_id.clone(), label: s.label.clone(), ..Default::default() }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn view_keeps_metadata_and_orders_blocks() {
        let mut s = SessionState { session_id: "session:a".into(), ..Default::default() };
        let v1: SessionView = serde_json::from_value(json!({"revision": 1, "windows": [{"window_id": "window:1", "label": "Window 89abcdef", "focused_block_id": "block:1",
            "layers": [{"kind": "tiled", "bounds": [0,0,1,1], "layout": {"rect": [0,0,1,1], "block_id": "block:1"}, "blocks": [{"block_id": "block:1", "creator_name": "com.superlogical.terminal"}]}]}]})).unwrap();
        s.apply_view(&v1);
        s.block_mut("block:1").unwrap().title = Some("π - rexc".into());
        let v2: SessionView = serde_json::from_value(json!({"revision": 2, "windows": [{"window_id": "window:1", "label": "Window 89abcdef", "focused_block_id": "block:1",
            "layers": [{"kind": "tiled", "bounds": [0,0,1,1], "layout": {"rect": [0,0,1,1], "split": {"direction": "vertical", "ratio": 0.5,
                "before": {"rect": [0,0,1,0.5], "block_id": "block:2"}, "after": {"rect": [0,0.5,1,0.5], "block_id": "block:1"}}},
             "blocks": [{"block_id": "block:1", "creator_name": "com.superlogical.terminal", "owner": "client:9"}, {"block_id": "block:2", "creator_name": "com.superlogical.terminal"}]}]}]})).unwrap();
        s.apply_view(&v2);
        let w = &s.windows[0];
        assert_eq!(w.blocks.iter().map(|b| b.block_id.as_str()).collect::<Vec<_>>(), ["block:2", "block:1"]);
        assert_eq!(s.block("block:1").unwrap().title.as_deref(), Some("π - rexc"));
        assert_eq!(s.block("block:1").unwrap().owner.as_deref(), Some("client:9"));
        assert_eq!(w.display_name(), "π - rexc");
        // An older view is ignored.
        s.apply_view(&v1);
        assert_eq!(s.windows[0].blocks.len(), 2);
    }

    #[test]
    fn titles_and_inbox() {
        let mut b = BlockState { title: Some("nam@mac:~/src".into()), process: Some("zsh".into()), pwd: Some("file://mac/Users/nam/src".into()), ..Default::default() };
        assert_eq!(b.display_title(), None);
        assert_eq!(b.pwd_short().as_deref(), Some("src"));
        b.programs.push(ProgramStatus { state: ProgramState::Working, app: Some("claude".into()), ..Default::default() });
        b.programs.push(ProgramStatus { id: "x".into(), state: ProgramState::Blocked, kind: Some("permission".into()), msg: Some("Allow?".into()), ..Default::default() });
        assert_eq!(b.program_state(), Some(ProgramState::Blocked));
        assert_eq!(b.app_name().as_deref(), Some("claude"));
        let host = HostState {
            host_id: "h".into(),
            name: "mac".into(),
            sessions: vec![SessionState { session_id: "s".into(), label: "work".into(), windows: vec![WindowState { window_id: "w".into(), label: "Window 89abcdef".into(), blocks: vec![b], ..Default::default() }], ..Default::default() }],
            ..Default::default()
        };
        let items = inbox(std::slice::from_ref(&host));
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].state, ProgramState::Blocked);
        assert_eq!(items[0].window_label, "claude");
        assert!(is_generated_label("Window 89abcdef"));
        assert!(!is_generated_label("Window notes"));
    }
}
