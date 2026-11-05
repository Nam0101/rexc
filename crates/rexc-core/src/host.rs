//! One server: its control connection, reconnects, and the live state tree.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

use futures_util::future::join_all;
use parking_lot::Mutex;
use rexc_client::{ClientError, Control, ControlEvent, Endpoint};
use rexc_proto::control::ClientInfo;
use rexc_proto::model::{BootstrapResult, ProgramState, ServerEvent, SessionEvent, SessionView, TerminalEvent};
use serde_json::{Value, json};
use tokio::sync::mpsc;

use crate::model::{HostState, HostStatus, SessionState, summaries_to_sessions};
use crate::notify::Notifier;
use crate::store::HostRecord;
use crate::terminal::{TermCmd, TerminalRegistry};
use crate::{Alert, AlertKind, Event};

const MIN_BACKOFF: Duration = Duration::from_secs(1);
const MAX_BACKOFF: Duration = Duration::from_secs(30);
const VIEW_DEBOUNCE: Duration = Duration::from_millis(40);

#[derive(Debug)]
pub enum HostCmd {
    Connect,
    Disconnect,
    /// Re-read a session's view (after a change we made ourselves).
    Refresh(String),
}

pub struct HostHandle {
    pub id: String,
    pub record: Mutex<HostRecord>,
    pub state: Mutex<HostState>,
    control: Mutex<Option<Control>>,
    cmd: mpsc::UnboundedSender<HostCmd>,
    pub(crate) notifier: Notifier,
    pub(crate) terminals: Arc<TerminalRegistry>,
    pub(crate) client_info: ClientInfo,
    /// What each block last alerted about, so the same status arriving again stays quiet.
    alerted: Mutex<AlertLedger>,
}

/// The part of a program status worth a new alert. Titles are left out: agents animate them.
#[derive(Debug, Clone, PartialEq)]
struct AlertKey {
    state: ProgramState,
    kind: Option<String>,
    msg: Option<String>,
}

#[derive(Debug, Default)]
struct AlertLedger(HashMap<String, AlertKey>);

impl AlertLedger {
    /// Records `key` for the block. `Some(previous state)` when it is news, `None` when it repeats the last alert.
    fn note(&mut self, block_id: &str, key: AlertKey) -> Option<Option<ProgramState>> {
        let previous = self.0.insert(block_id.to_string(), key.clone());
        (previous.as_ref() != Some(&key)).then(|| previous.map(|p| p.state))
    }

    fn forget(&mut self, block_id: &str) {
        self.0.remove(block_id);
    }
}

impl HostHandle {
    pub fn spawn(record: HostRecord, notifier: Notifier, terminals: Arc<TerminalRegistry>, client_info: ClientInfo, handle: &tokio::runtime::Handle) -> Arc<Self> {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let state = HostState { host_id: record.id.clone(), name: record.name.clone(), address: record.address.clone(), ..Default::default() };
        let this = Arc::new(Self {
            id: record.id.clone(),
            record: Mutex::new(record),
            state: Mutex::new(state),
            control: Mutex::new(None),
            cmd: cmd_tx,
            notifier,
            terminals,
            client_info,
            alerted: Mutex::new(AlertLedger::default()),
        });
        handle.spawn(run(this.clone(), cmd_rx));
        this
    }

    pub fn send(&self, cmd: HostCmd) {
        let _ = self.cmd.send(cmd);
    }

    pub fn control(&self) -> Option<Control> {
        self.control.lock().clone().filter(Control::is_open)
    }

    pub fn endpoint(&self) -> Result<Endpoint, ClientError> {
        Endpoint::parse(&self.record.lock().address)
    }

    pub fn client_id(&self) -> Option<String> {
        self.state.lock().client_id.clone()
    }

    pub fn status(&self) -> HostStatus {
        self.state.lock().status
    }

    fn set_status(&self, status: HostStatus, error: Option<String>) {
        {
            let mut s = self.state.lock();
            s.status = status;
            s.error = error;
            if status != HostStatus::Connected {
                s.client_id = None;
            }
        }
        self.notifier.host(&self.id);
    }

    /// Call a method on this host, failing when it isn't connected.
    pub async fn call(&self, method: &str, payload: Value) -> Result<Value, ClientError> {
        let control = self.control().ok_or(ClientError::Closed)?;
        control.call(method, payload).await
    }

    /// Ask for a fresh view of a session and apply it.
    pub async fn refresh_view(self: &Arc<Self>, session_id: &str) {
        let Some(control) = self.control() else { return };
        match control.call_as::<SessionView>("session.view", json!({"session_id": session_id})).await {
            Ok((_, view)) => {
                let new_blocks: Vec<String> = {
                    let mut st = self.state.lock();
                    let Some(s) = st.session_mut(session_id) else { return };
                    let before: HashSet<String> = s.windows.iter().flat_map(|w| w.blocks.iter().map(|b| b.block_id.clone())).collect();
                    s.apply_view(&view);
                    s.windows.iter().flat_map(|w| w.blocks.iter().map(|b| b.block_id.clone())).filter(|id| !before.contains(id)).collect()
                };
                self.notifier.host(&self.id);
                if !new_blocks.is_empty() {
                    self.seed_blocks(&control, session_id, &new_blocks).await;
                }
            }
            Err(e) => tracing::debug!("session.view {session_id}: {e}"),
        }
    }

    /// Read title, pwd, process and program status for blocks we just learned about.
    async fn seed_blocks(self: &Arc<Self>, control: &Control, session_id: &str, block_ids: &[String]) {
        let calls = block_ids.iter().flat_map(|b| ["title", "pwd", "process", "program_status"].into_iter().map(move |m| (b.clone(), m)));
        let results = join_all(calls.map(|(b, m)| {
            let control = control.clone();
            let session_id = session_id.to_string();
            async move { (b.clone(), m, control.call_block(&session_id, &b, m, json!({})).await) }
        }))
        .await;
        {
            let mut st = self.state.lock();
            for (block_id, method, result) in results {
                let Ok(v) = result else { continue };
                let Some(b) = st.block_mut(session_id, &block_id) else { continue };
                match method {
                    "title" => b.title = v["title"].as_str().map(str::to_owned),
                    "pwd" => b.pwd = v["pwd"].as_str().map(str::to_owned),
                    "process" => {
                        let info: Option<rexc_proto::model::ProcessInfo> = serde_json::from_value(v["foreground"].clone()).ok();
                        b.process = info.as_ref().map(|p| p.display_name());
                        b.process_info = info;
                        b.exit_code = v["last_exit"]["exit_code"].as_i64();
                    }
                    "program_status" => {
                        b.programs = serde_json::from_value(v["records"].clone()).unwrap_or_default();
                    }
                    _ => {}
                }
            }
        }
        self.notifier.host(&self.id);
    }
}

enum Outcome {
    Disconnect,
    Dropped(String),
}

async fn run(host: Arc<HostHandle>, mut cmd_rx: mpsc::UnboundedReceiver<HostCmd>) {
    let mut wanted = false;
    let mut token: Option<String> = None;
    let mut backoff = MIN_BACKOFF;
    loop {
        if !wanted {
            match cmd_rx.recv().await {
                Some(HostCmd::Connect) => wanted = true,
                Some(_) => continue,
                None => return,
            }
        }
        host.set_status(if token.is_some() { HostStatus::Reconnecting } else { HostStatus::Connecting }, None);
        let endpoint = match host.endpoint() {
            Ok(e) => e,
            Err(e) => {
                host.set_status(HostStatus::Offline, Some(e.to_string()));
                wanted = false;
                continue;
            }
        };
        match connect(&host, &endpoint, token.as_deref()).await {
            Ok((control, events)) => {
                token = control.reconnect_token().map(str::to_owned);
                backoff = MIN_BACKOFF;
                *host.control.lock() = Some(control.clone());
                host.set_status(HostStatus::Connected, None);
                host.terminals.broadcast(&host.id, TermCmd::Reconnected);
                let outcome = connected(&host, control, events, &mut cmd_rx).await;
                *host.control.lock() = None;
                match outcome {
                    Outcome::Disconnect => {
                        wanted = false;
                        token = None;
                        host.set_status(HostStatus::Offline, None);
                        host.terminals.broadcast(&host.id, TermCmd::HostOffline);
                    }
                    Outcome::Dropped(reason) => {
                        host.set_status(HostStatus::Reconnecting, Some(reason));
                        host.terminals.broadcast(&host.id, TermCmd::HostOffline);
                    }
                }
            }
            Err(e) => {
                tracing::info!("{}: connect failed: {e}", host.id);
                host.set_status(HostStatus::Reconnecting, Some(e.to_string()));
                if matches!(e, ClientError::Refused(_)) {
                    // 403 and friends won't fix themselves; stop hammering.
                    host.set_status(HostStatus::Offline, Some(e.to_string()));
                    wanted = false;
                    continue;
                }
            }
        }
        if wanted {
            tokio::select! {
                _ = tokio::time::sleep(backoff) => {}
                cmd = cmd_rx.recv() => match cmd {
                    Some(HostCmd::Disconnect) => { wanted = false; token = None; host.set_status(HostStatus::Offline, None); }
                    Some(HostCmd::Connect) => backoff = MIN_BACKOFF,
                    Some(HostCmd::Refresh(_)) => {}
                    None => return,
                }
            }
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
    }
}

async fn connect(host: &Arc<HostHandle>, endpoint: &Endpoint, token: Option<&str>) -> Result<(Control, mpsc::UnboundedReceiver<ControlEvent>), ClientError> {
    let (control, events) = Control::connect(endpoint, &host.client_info, token).await?;
    let (_, boot): (u64, BootstrapResult) = control.call_as("session.bootstrap", json!({"event_scope": "all", "attach_session_ids": []})).await?;
    {
        let mut st = host.state.lock();
        st.client_id = Some(boot.client_id.clone());
        st.server_host_id = boot.server_status.host_id.clone();
        // Keep metadata of sessions we already knew.
        let mut fresh = summaries_to_sessions(&boot.attach_all.sessions);
        for s in &mut fresh {
            if let Some(old) = st.sessions.iter_mut().find(|o| o.session_id == s.session_id) {
                *s = std::mem::take(old);
                s.loaded = false;
            }
        }
        st.sessions = fresh;
        st.clients.clear();
    }
    host.notifier.host(&host.id);
    let ids: Vec<String> = boot.attach_all.sessions.iter().map(|s| s.session_id.clone()).collect();
    let attaches = join_all(ids.iter().map(|id| {
        let control = control.clone();
        async move { (id.clone(), control.call("session.attach", json!({"session_id": id})).await) }
    }))
    .await;
    let mut seeds: Vec<(String, Vec<String>)> = Vec::new();
    {
        let mut st = host.state.lock();
        for (id, result) in attaches {
            let Ok(v) = result else { continue };
            let Ok(view) = serde_json::from_value::<SessionView>(v["view"].clone()) else { continue };
            if let Some(s) = st.session_mut(&id) {
                s.loaded = false;
                s.apply_view(&view);
                seeds.push((id.clone(), s.windows.iter().flat_map(|w| w.blocks.iter().map(|b| b.block_id.clone())).collect()));
            }
        }
    }
    host.notifier.host(&host.id);
    for (session_id, blocks) in seeds {
        host.seed_blocks(&control, &session_id, &blocks).await;
    }
    Ok((control, events))
}

async fn connected(host: &Arc<HostHandle>, control: Control, mut events: mpsc::UnboundedReceiver<ControlEvent>, cmd_rx: &mut mpsc::UnboundedReceiver<HostCmd>) -> Outcome {
    let refresh_pending: Arc<Mutex<HashSet<String>>> = Arc::default();
    let schedule_refresh = |session_id: String| {
        let first = refresh_pending.lock().insert(session_id.clone());
        if !first {
            return;
        }
        let host = host.clone();
        let pending = refresh_pending.clone();
        tokio::spawn(async move {
            tokio::time::sleep(VIEW_DEBOUNCE).await;
            pending.lock().remove(&session_id);
            host.refresh_view(&session_id).await;
        });
    };
    loop {
        tokio::select! {
            ev = events.recv() => match ev {
                Some(ControlEvent::Event { name, payload, .. }) => {
                    let decoded = ServerEvent::decode(&name, &payload);
                    apply_event(host, &control, decoded, &schedule_refresh).await;
                }
                Some(ControlEvent::Closed { code, reason }) => return Outcome::Dropped(format!("{code} {reason}")),
                None => return Outcome::Dropped("connection task ended".into()),
            },
            cmd = cmd_rx.recv() => match cmd {
                Some(HostCmd::Disconnect) => return Outcome::Disconnect,
                Some(HostCmd::Connect) => {}
                Some(HostCmd::Refresh(session_id)) => schedule_refresh(session_id),
                None => return Outcome::Disconnect,
            },
        }
    }
}

async fn apply_event(host: &Arc<HostHandle>, control: &Control, event: ServerEvent, schedule_refresh: &(dyn Fn(String) + Sync)) {
    match event {
        ServerEvent::SessionCreated(s) => {
            {
                let mut st = host.state.lock();
                if st.session(&s.session_id).is_none() {
                    st.sessions.push(SessionState { session_id: s.session_id.clone(), label: s.label.clone(), ..Default::default() });
                }
            }
            host.notifier.host(&host.id);
            if let Ok(v) = control.call("session.attach", json!({"session_id": s.session_id})).await {
                if let Ok(view) = serde_json::from_value::<SessionView>(v["view"].clone()) {
                    let blocks: Vec<String> = {
                        let mut st = host.state.lock();
                        match st.session_mut(&s.session_id) {
                            Some(sess) => {
                                sess.apply_view(&view);
                                sess.windows.iter().flat_map(|w| w.blocks.iter().map(|b| b.block_id.clone())).collect()
                            }
                            None => Vec::new(),
                        }
                    };
                    host.notifier.host(&host.id);
                    host.seed_blocks(control, &s.session_id, &blocks).await;
                }
            }
        }
        ServerEvent::SessionDestroyed { session_id } => {
            host.state.lock().sessions.retain(|s| s.session_id != session_id);
            host.notifier.host(&host.id);
            host.terminals.broadcast_session(&host.id, &session_id, TermCmd::BlockClosed);
        }
        ServerEvent::SessionMoved { session_id, before_session_id } => {
            let mut st = host.state.lock();
            if let Some(pos) = st.sessions.iter().position(|s| s.session_id == session_id) {
                let s = st.sessions.remove(pos);
                let at = before_session_id.and_then(|b| st.sessions.iter().position(|x| x.session_id == b)).unwrap_or(st.sessions.len());
                st.sessions.insert(at, s);
            }
            drop(st);
            host.notifier.host(&host.id);
        }
        ServerEvent::Session { session_id, event } => apply_session_event(host, &session_id, event, schedule_refresh),
        ServerEvent::TailscaleStatusChanged(_) | ServerEvent::Other(_) => {}
    }
}

fn apply_session_event(host: &Arc<HostHandle>, session_id: &str, event: SessionEvent, schedule_refresh: &(dyn Fn(String) + Sync)) {
    match event {
        SessionEvent::ViewChanged { revision } => {
            let known = host.state.lock().session(session_id).map(|s| s.revision).unwrap_or(0);
            if revision > known {
                schedule_refresh(session_id.to_string());
            }
        }
        SessionEvent::ActiveWindowChanged { .. } | SessionEvent::WindowCreated { .. } | SessionEvent::WindowClosed { .. } | SessionEvent::BlockCreated { .. } => {
            schedule_refresh(session_id.to_string());
        }
        SessionEvent::SessionLabelChanged { label } => {
            if let Some(s) = host.state.lock().session_mut(session_id) {
                s.label = label;
            }
            host.notifier.host(&host.id);
        }
        SessionEvent::WindowLabelChanged { window_id, label } => {
            if let Some(w) = host.state.lock().session_mut(session_id).and_then(|s| s.windows.iter_mut().find(|w| w.window_id == window_id)) {
                w.label = label;
            }
            host.notifier.host(&host.id);
        }
        SessionEvent::BlockClosed { block_id } => {
            {
                let mut st = host.state.lock();
                if let Some(s) = st.session_mut(session_id) {
                    for w in &mut s.windows {
                        w.blocks.retain(|b| b.block_id != block_id);
                    }
                    s.windows.retain(|w| !w.blocks.is_empty());
                }
            }
            host.notifier.host(&host.id);
            host.terminals.broadcast_block(&host.id, &block_id, TermCmd::BlockClosed);
            schedule_refresh(session_id.to_string());
        }
        SessionEvent::BlockOwnerChanged { block_id, owner, .. } => {
            if let Some(b) = host.state.lock().block_mut(session_id, &block_id) {
                b.owner = owner.clone();
            }
            host.notifier.host(&host.id);
            host.terminals.broadcast_block(&host.id, &block_id, TermCmd::OwnerChanged(owner));
        }
        SessionEvent::ClientConnected { client_id, display } => {
            host.state.lock().clients.insert(client_id, display);
        }
        SessionEvent::ClientDisconnected { client_id } => {
            host.state.lock().clients.remove(&client_id);
        }
        SessionEvent::Block { block_id, event } => apply_block_event(host, session_id, &block_id, event),
        SessionEvent::Other(_) => {}
    }
}

fn apply_block_event(host: &Arc<HostHandle>, session_id: &str, block_id: &str, event: TerminalEvent) {
    let mut alert: Option<Alert> = None;
    let (host_name, session_label, window_label, app_before) = {
        let st = host.state.lock();
        let s = st.session(session_id);
        let w = s.and_then(|s| s.window_of(block_id));
        let b = s.and_then(|s| s.block(block_id));
        (st.name.clone(), s.map(|s| s.label.clone()).unwrap_or_default(), w.map(|w| w.display_name()).unwrap_or_default(), b.and_then(|b| b.app_name()))
    };
    let make_alert = |kind: AlertKind, title: String, body: String| Alert {
        host_id: host.id.clone(),
        host_name: host_name.clone(),
        session_id: session_id.to_string(),
        session_label: session_label.clone(),
        block_id: block_id.to_string(),
        window_label: window_label.clone(),
        kind,
        title,
        body,
    };
    {
        let mut st = host.state.lock();
        let Some(b) = st.block_mut(session_id, block_id) else { return };
        match event {
            TerminalEvent::TitleChanged { title } => b.title = Some(title),
            TerminalEvent::PwdChanged { pwd } => b.pwd = Some(pwd),
            TerminalEvent::ProcessChanged { foreground } => {
                b.process = foreground.as_ref().map(|p| p.display_name());
                b.process_info = foreground;
            }
            TerminalEvent::ProgramStatusChanged { id, record } => {
                let previous = b.programs.iter().find(|p| p.id == id).map(|p| p.state);
                let mut record = record;
                record.id = id.clone();
                match b.programs.iter_mut().find(|p| p.id == id) {
                    Some(slot) => *slot = record.clone(),
                    None => b.programs.push(record.clone()),
                }
                let app = record.effective_app.clone().or(record.app.clone()).or(app_before.clone()).unwrap_or_else(|| "program".into());
                let body = record.msg.clone().or(record.title.clone()).unwrap_or_default();
                let key = AlertKey { state: record.state, kind: record.kind.clone(), msg: record.msg.clone() };
                // A status we already alerted about (re-registered program, reconnect, animated title) stays quiet.
                if let Some(last) = host.alerted.lock().note(block_id, key) {
                    let was_working = previous == Some(ProgramState::Working) || last == Some(ProgramState::Working);
                    alert = match record.state {
                        ProgramState::Blocked => Some(make_alert(AlertKind::Blocked { kind: record.kind.clone() }, format!("{app} needs you"), body)),
                        ProgramState::Error => Some(make_alert(AlertKind::Error, format!("{app} failed"), body)),
                        ProgramState::Done if was_working => Some(make_alert(AlertKind::Done, format!("{app} finished"), body)),
                        _ => None,
                    };
                }
            }
            TerminalEvent::ProgramStatusRemoved { ids, .. } => {
                b.programs.retain(|p| !ids.contains(&p.id));
                if b.programs.is_empty() {
                    host.alerted.lock().forget(block_id);
                }
            }
            TerminalEvent::ChildExited { exit_code, .. } => b.exit_code = Some(exit_code),
            TerminalEvent::DesktopNotification { title, body } => {
                alert = Some(make_alert(AlertKind::Notification, if title.is_empty() { app_before.clone().unwrap_or_else(|| host_name.clone()) } else { title }, body));
            }
            TerminalEvent::ClipboardWritten { text, .. } => {
                if let Some(text) = text {
                    host.notifier.emit(Event::Clipboard { host_id: host.id.clone(), text });
                }
            }
            TerminalEvent::UrlClicked { url } => host.notifier.emit(Event::OpenUrl { url }),
            TerminalEvent::SizeChanged { .. } | TerminalEvent::Bell | TerminalEvent::Other(_) => {}
        }
    }
    host.notifier.host(&host.id);
    if let Some(a) = alert {
        host.notifier.emit(Event::Alert(a));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(state: ProgramState, msg: &str) -> AlertKey {
        AlertKey { state, kind: None, msg: Some(msg.into()) }
    }

    #[test]
    fn ledger_alerts_once_per_status_and_again_for_news() {
        let mut l = AlertLedger::default();
        assert_eq!(l.note("b", key(ProgramState::Blocked, "Allow Bash?")), Some(None));
        // The same status again (re-registered id, reconnect, title spinner): quiet.
        assert_eq!(l.note("b", key(ProgramState::Blocked, "Allow Bash?")), None);
        // A new question while still blocked is news.
        assert_eq!(l.note("b", key(ProgramState::Blocked, "Allow Edit?")), Some(Some(ProgramState::Blocked)));
        assert_eq!(l.note("b", key(ProgramState::Working, "")), Some(Some(ProgramState::Blocked)));
        assert_eq!(l.note("b", key(ProgramState::Done, "")), Some(Some(ProgramState::Working)));
        // Blocks are independent; forgetting lets the next program alert afresh.
        assert_eq!(l.note("c", key(ProgramState::Done, "")), Some(None));
        l.forget("b");
        assert_eq!(l.note("b", key(ProgramState::Done, "")), Some(None));
    }
}
