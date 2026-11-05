//! One server: its control connection, reconnects, and the live state tree.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use futures_util::future::join_all;
use parking_lot::Mutex;
use rexc_client::{ClientError, Control, ControlEvent, Endpoint};
use rexc_proto::control::ClientInfo;
use rexc_proto::model::{BootstrapResult, ProgramState, ServerEvent, SessionEvent, SessionView, TerminalEvent};
use serde_json::{Value, json};
use tokio::sync::mpsc;

use crate::cache::{self, HostCache};
use crate::model::{BlockState, HostState, HostStatus, SessionState, summaries_to_sessions};
use crate::notify::Notifier;
use crate::store::HostRecord;
use crate::terminal::{TermCmd, TerminalRegistry};
use crate::{Alert, AlertKind, Event};

const MIN_BACKOFF: Duration = Duration::from_secs(1);
const MAX_BACKOFF: Duration = Duration::from_secs(30);
const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
const VIEW_DEBOUNCE: Duration = Duration::from_millis(40);
/// The cache is written this long after a change, folding the changes in between into one write.
const CACHE_DEBOUNCE: Duration = Duration::from_secs(2);
/// A Blocked, Error or Done alert waits this long and goes out only if the status held,
/// so an agent flapping through a state does not buzz the phone.
const ALERT_GRACE: Duration = Duration::from_secs(1);
const TITLE_MAX: usize = 160;
const BODY_MAX: usize = 512;

#[derive(Debug)]
pub enum HostCmd {
    Connect,
    Disconnect,
    /// The network changed: end a backoff wait, or check a live socket still answers.
    Probe,
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
    /// Where the session tree is cached; `None` once the host is removed (or in tests).
    cache: Arc<Mutex<Option<PathBuf>>>,
    cache_pending: AtomicBool,
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

    /// An alert for `key` was dropped: unless something newer was noted since, forget it so
    /// the same status arriving again later alerts.
    fn forget_if(&mut self, block_id: &str, key: &AlertKey) {
        if self.0.get(block_id) == Some(key) {
            self.0.remove(block_id);
        }
    }
}

impl HostHandle {
    /// With `cache`, the host starts from the session tree cached there, marked stale.
    pub fn spawn(record: HostRecord, notifier: Notifier, terminals: Arc<TerminalRegistry>, client_info: ClientInfo, cache: Option<PathBuf>, handle: &tokio::runtime::Handle) -> Arc<Self> {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let mut state = HostState { host_id: record.id.clone(), name: record.name.clone(), address: record.address.clone(), ..Default::default() };
        if let Some(c) = cache.as_deref().and_then(|f| cache::load(f, &record.id)) {
            state.sessions = c.sessions;
            state.stale = true;
            state.as_of_ms = Some(c.as_of_ms);
        }
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
            cache: Arc::new(Mutex::new(cache)),
            cache_pending: AtomicBool::new(false),
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
            if status == HostStatus::Connected {
                s.stale = false;
                s.as_of_ms = None;
            } else if s.status == HostStatus::Connected {
                // The tree stays as it was last seen live.
                s.stale = true;
                s.as_of_ms = Some(cache::now_ms());
            }
            s.status = status;
            s.error = error;
            if status != HostStatus::Connected {
                s.client_id = None;
            }
        }
        self.notifier.host(&self.id);
    }

    /// The session tree changed: tell the UI now, and write the cache a little later.
    fn changed(self: &Arc<Self>) {
        self.notifier.host(&self.id);
        if self.cache.lock().is_none() || self.cache_pending.swap(true, Ordering::AcqRel) {
            return;
        }
        let host = self.clone();
        tokio::spawn(async move {
            tokio::time::sleep(CACHE_DEBOUNCE).await;
            host.cache_pending.store(false, Ordering::Release);
            // A stale tree was already written when it went stale.
            if !host.state.lock().stale {
                host.write_cache();
            }
        });
    }

    /// Write the session tree to the cache on the blocking pool, dated when it was last live.
    fn write_cache(&self) {
        if self.cache.lock().is_none() {
            return;
        }
        let snapshot = {
            let st = self.state.lock();
            HostCache::new(&self.id, st.as_of_ms.unwrap_or_else(cache::now_ms), st.sessions.clone())
        };
        let file = self.cache.clone();
        tokio::task::spawn_blocking(move || {
            // Held while writing, so `forget_cache` can't race a write back in.
            let file = file.lock();
            let Some(path) = file.as_deref() else { return };
            if let Err(e) = cache::save(path, &snapshot) {
                tracing::warn!("{}: writing cache: {e}", snapshot.host_id);
            }
        });
    }

    /// The host is gone: delete its cache and write no more.
    pub fn forget_cache(&self) {
        if let Some(path) = self.cache.lock().take() {
            match std::fs::remove_file(&path) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => tracing::warn!("{}: removing cache: {e}", self.id),
                _ => {}
            }
        }
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
                self.changed();
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
                let Some(b) = st.block_mut(session_id, &block_id) else { continue };
                let Ok(v) = result else {
                    // Unknown is not still Blocked: cached programs must not outlive a failed read.
                    if method == "program_status" {
                        b.programs.clear();
                    }
                    continue;
                };
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
        self.changed();
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
                host.write_cache();
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
                host.write_cache();
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
                    Some(HostCmd::Connect | HostCmd::Probe) => backoff = MIN_BACKOFF,
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
    host.changed();
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
    host.changed();
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
                Some(HostCmd::Probe) if !alive(&control).await => return Outcome::Dropped("no answer after a network change".into()),
                Some(HostCmd::Probe) => {}
                Some(HostCmd::Refresh(session_id)) => schedule_refresh(session_id),
                None => return Outcome::Disconnect,
            },
        }
    }
}

/// Whether the server answers within [`PROBE_TIMEOUT`]. Any reply counts, even an error: it crossed the wire.
async fn alive(control: &Control) -> bool {
    match tokio::time::timeout(PROBE_TIMEOUT, control.call("server.status", json!({}))).await {
        Ok(Err(ClientError::Closed | ClientError::Timeout | ClientError::Io(_) | ClientError::WebSocket(_))) | Err(_) => false,
        Ok(_) => true,
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
            host.changed();
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
                    host.changed();
                    host.seed_blocks(control, &s.session_id, &blocks).await;
                }
            }
        }
        ServerEvent::SessionDestroyed { session_id } => {
            host.state.lock().sessions.retain(|s| s.session_id != session_id);
            host.changed();
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
            host.changed();
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
            host.changed();
        }
        SessionEvent::WindowLabelChanged { window_id, label } => {
            if let Some(w) = host.state.lock().session_mut(session_id).and_then(|s| s.windows.iter_mut().find(|w| w.window_id == window_id)) {
                w.label = label;
            }
            host.changed();
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
            host.changed();
            host.terminals.broadcast_block(&host.id, &block_id, TermCmd::BlockClosed);
            schedule_refresh(session_id.to_string());
        }
        SessionEvent::BlockOwnerChanged { block_id, owner, .. } => {
            if let Some(b) = host.state.lock().block_mut(session_id, &block_id) {
                b.owner = owner.clone();
            }
            host.changed();
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
    // A status alert's program id and status: it goes out after the grace only if they still match.
    let mut grace: Option<(String, AlertKey)> = None;
    let mut bell = false;
    // Titles, cwds and the like change often (agents animate titles): show them, but write
    // the cache only for tree and program-status changes.
    let persist = matches!(event, TerminalEvent::ProcessChanged { .. } | TerminalEvent::ProgramStatusChanged { .. } | TerminalEvent::ProgramStatusRemoved { .. } | TerminalEvent::ChildExited { .. });
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
        title: safe_text(&title, TITLE_MAX),
        body: safe_text(&body, BODY_MAX),
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
                if let Some(last) = host.alerted.lock().note(block_id, key.clone()) {
                    let was_working = previous == Some(ProgramState::Working) || last == Some(ProgramState::Working);
                    alert = match record.state {
                        ProgramState::Blocked => Some(make_alert(AlertKind::Blocked { kind: record.kind.clone(), msg: record.msg.clone() }, format!("{app} needs you"), body)),
                        ProgramState::Error => Some(make_alert(AlertKind::Error, format!("{app} failed"), body)),
                        ProgramState::Done if was_working => Some(make_alert(AlertKind::Done, format!("{app} finished"), body)),
                        _ => None,
                    };
                    grace = alert.is_some().then_some((id, key));
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
            TerminalEvent::Bell => bell = true,
            TerminalEvent::SizeChanged { .. } | TerminalEvent::Other(_) => {}
        }
    }
    if persist {
        host.changed();
    } else {
        host.notifier.host(&host.id);
    }
    if bell {
        for terminal_id in host.terminals.ring(&host.id, block_id) {
            host.notifier.emit(Event::Bell { terminal_id });
        }
    }
    let Some(alert) = alert else { return };
    let Some((program_id, key)) = grace else { return deliver(host, alert) };
    let host = host.clone();
    let session_id = session_id.to_string();
    tokio::spawn(async move {
        tokio::time::sleep(ALERT_GRACE).await;
        let held = still_alerting(host.state.lock().block(&session_id, &alert.block_id), &program_id, &key);
        if held {
            deliver(&host, alert);
        } else {
            host.alerted.lock().forget_if(&alert.block_id, &key);
        }
    });
}

/// Hand an alert to the UI, unless the person is looking at that block's terminal right now.
fn deliver(host: &HostHandle, alert: Alert) {
    if !host.terminals.on_screen(&host.id, &alert.block_id) {
        host.notifier.emit(Event::Alert(alert));
    }
}

/// Whether program `program_id` in the block still has the status an alert was raised for.
/// A Done or Error program (or block) gone since counts as held: that status was its last word.
/// A Blocked one gone no longer needs anyone.
fn still_alerting(block: Option<&BlockState>, program_id: &str, key: &AlertKey) -> bool {
    match block.and_then(|b| b.programs.iter().find(|p| p.id == program_id)) {
        Some(p) => p.state == key.state && p.kind == key.kind && p.msg == key.msg,
        None => key.state != ProgramState::Blocked,
    }
}

/// Text from a program made safe for a notification: no control or bidi-override characters,
/// whitespace runs as one space, at most `max` characters (the last an ellipsis when cut).
// Adapted from sheprd (Apache-2.0): crates/sheprd-client/src/text.rs
fn safe_text(text: &str, max: usize) -> String {
    let mut out = String::new();
    let mut count = 0;
    let mut gap = false;
    for c in text.chars() {
        if c.is_whitespace() {
            gap = count > 0;
        } else if !(c.is_control() || matches!(c, '\u{200e}' | '\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}')) {
            if gap {
                out.push(' ');
                count += 1;
                gap = false;
            }
            out.push(c);
            count += 1;
            // Bound the scan too: past `max` the rest is cut anyway.
            if count > max {
                let mut cut: String = out.chars().take(max.saturating_sub(1)).collect();
                cut.truncate(cut.trim_end().len());
                cut.push('…');
                return cut;
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Listener;
    use crate::model::WindowState;
    use rexc_proto::model::ProgramStatus;

    #[derive(Default)]
    struct Capture(Mutex<Vec<Alert>>);

    impl Listener for Capture {
        fn on_event(&self, event: Event) {
            if let Event::Alert(a) = event {
                self.0.lock().push(a);
            }
        }
    }

    /// A host with one block `b` in session `s`, its alerts captured. Not connected.
    fn test_host() -> (Arc<HostHandle>, Arc<Capture>) {
        let capture = Arc::new(Capture::default());
        let handle = tokio::runtime::Handle::current();
        let record = HostRecord { id: "h".into(), name: "mac".into(), address: "ws://127.0.0.1:1".into(), last: Default::default(), background_alerts: false, theme: None };
        let host = HostHandle::spawn(record, Notifier::new(capture.clone(), handle.clone()), Arc::default(), ClientInfo::app("test", "0", None), None, &handle);
        let block = BlockState { block_id: "b".into(), ..Default::default() };
        let window = WindowState { window_id: "w".into(), blocks: vec![block], ..Default::default() };
        host.state.lock().sessions.push(SessionState { session_id: "s".into(), windows: vec![window], ..Default::default() });
        (host, capture)
    }

    fn status(host: &Arc<HostHandle>, id: &str, state: ProgramState, msg: &str) {
        let record = ProgramStatus { state, kind: Some("permission".into()), msg: Some(msg.into()), ..Default::default() };
        apply_block_event(host, "s", "b", TerminalEvent::ProgramStatusChanged { id: id.into(), record });
    }

    async fn past_grace() {
        tokio::time::sleep(ALERT_GRACE + Duration::from_millis(200)).await;
    }

    #[tokio::test]
    async fn a_status_that_flaps_within_the_grace_does_not_alert() {
        let (host, alerts) = test_host();
        status(&host, "", ProgramState::Working, "");
        status(&host, "", ProgramState::Blocked, "Allow Bash?");
        status(&host, "", ProgramState::Working, "");
        past_grace().await;
        assert!(alerts.0.lock().is_empty());
        // The dropped alert does not silence a real one later.
        status(&host, "", ProgramState::Blocked, "Allow Bash?");
        past_grace().await;
        assert_eq!(alerts.0.lock().len(), 1);
    }

    #[tokio::test]
    async fn a_status_that_holds_alerts_after_the_grace() {
        let (host, alerts) = test_host();
        status(&host, "", ProgramState::Working, "");
        status(&host, "", ProgramState::Blocked, "Allow\u{202e} Bash?\n");
        assert!(alerts.0.lock().is_empty(), "nothing before the grace ends");
        past_grace().await;
        let got = alerts.0.lock().clone();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].kind, AlertKind::Blocked { kind: Some("permission".into()), msg: Some("Allow\u{202e} Bash?\n".into()) });
        assert_eq!(got[0].body, "Allow Bash?");
    }

    #[tokio::test]
    async fn an_alert_dropped_for_a_removed_program_does_not_silence_its_return() {
        let (host, alerts) = test_host();
        status(&host, "", ProgramState::Working, "");
        status(&host, "x", ProgramState::Blocked, "Allow Bash?");
        // `x` goes away while the root program stays, so the block's ledger is not cleared.
        apply_block_event(&host, "s", "b", TerminalEvent::ProgramStatusRemoved { ids: vec!["x".into()], reason: String::new() });
        past_grace().await;
        assert!(alerts.0.lock().is_empty());
        status(&host, "x", ProgramState::Blocked, "Allow Bash?");
        past_grace().await;
        assert_eq!(alerts.0.lock().len(), 1);
    }

    #[tokio::test]
    async fn a_finished_program_removed_within_the_grace_still_alerts() {
        let (host, alerts) = test_host();
        status(&host, "", ProgramState::Working, "");
        status(&host, "", ProgramState::Done, "All done");
        apply_block_event(&host, "s", "b", TerminalEvent::ProgramStatusRemoved { ids: vec!["".into()], reason: String::new() });
        past_grace().await;
        let got = alerts.0.lock().clone();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].kind, AlertKind::Done);
    }

    #[test]
    fn only_a_blocked_program_that_is_gone_is_no_longer_alerting() {
        let k = |state| AlertKey { state, kind: None, msg: None };
        let block = BlockState { programs: vec![ProgramStatus { state: ProgramState::Working, ..Default::default() }], ..Default::default() };
        for state in [ProgramState::Done, ProgramState::Error] {
            assert!(still_alerting(None, "", &k(state)));
            assert!(still_alerting(Some(&BlockState::default()), "", &k(state)));
            assert!(!still_alerting(Some(&block), "", &k(state)));
        }
        assert!(!still_alerting(None, "", &k(ProgramState::Blocked)));
        assert!(!still_alerting(Some(&BlockState::default()), "", &k(ProgramState::Blocked)));
    }

    #[tokio::test]
    async fn titles_do_not_schedule_a_cache_write_but_statuses_do() {
        let dir = std::env::temp_dir().join(crate::store::new_id("rexc-host-cache").replace(':', "-"));
        let handle = tokio::runtime::Handle::current();
        let record = HostRecord { id: "h".into(), name: "mac".into(), address: "ws://127.0.0.1:1".into(), last: Default::default(), background_alerts: false, theme: None };
        let host = HostHandle::spawn(record, Notifier::new(Arc::new(Capture::default()), handle.clone()), Arc::default(), ClientInfo::app("test", "0", None), Some(cache::path(&dir, "h")), &handle);
        let window = WindowState { window_id: "w".into(), blocks: vec![BlockState { block_id: "b".into(), ..Default::default() }], ..Default::default() };
        host.state.lock().sessions.push(SessionState { session_id: "s".into(), windows: vec![window], ..Default::default() });
        apply_block_event(&host, "s", "b", TerminalEvent::TitleChanged { title: "⠋ claude".into() });
        apply_block_event(&host, "s", "b", TerminalEvent::Bell);
        assert!(!host.cache_pending.load(Ordering::Acquire));
        status(&host, "", ProgramState::Working, "");
        assert!(host.cache_pending.load(Ordering::Acquire));
        host.forget_cache();
        std::fs::remove_dir_all(dir).ok();
    }

    #[tokio::test]
    async fn cached_state_is_stale_until_a_live_snapshot_and_again_after_a_drop() {
        let dir = std::env::temp_dir().join(crate::store::new_id("rexc-host-cache").replace(':', "-"));
        let file = cache::path(&dir, "h");
        let block = BlockState { block_id: "b".into(), programs: vec![ProgramStatus { state: ProgramState::Blocked, ..Default::default() }], ..Default::default() };
        let window = WindowState { window_id: "w".into(), blocks: vec![block], ..Default::default() };
        cache::save(&file, &HostCache::new("h", 1_000, vec![SessionState { session_id: "s".into(), windows: vec![window], ..Default::default() }])).unwrap();
        let handle = tokio::runtime::Handle::current();
        let record = HostRecord { id: "h".into(), name: "mac".into(), address: "ws://127.0.0.1:1".into(), last: Default::default(), background_alerts: false, theme: None };
        let host = HostHandle::spawn(record, Notifier::new(Arc::new(Capture::default()), handle.clone()), Arc::default(), ClientInfo::app("test", "0", None), Some(file.clone()), &handle);

        let st = host.state.lock().clone();
        assert!(st.stale);
        assert_eq!(st.as_of_ms, Some(1_000));
        assert_eq!(st.sessions.len(), 1);
        assert!(crate::model::inbox(std::slice::from_ref(&st)).iter().all(|i| i.stale));

        // The status goes Connected only once a connect's full snapshot is in.
        host.set_status(HostStatus::Connected, None);
        let st = host.state.lock().clone();
        assert!(!st.stale);
        assert_eq!(st.as_of_ms, None);
        assert!(crate::model::inbox(std::slice::from_ref(&st)).iter().all(|i| !i.stale));

        // A drop keeps the tree, stale since now.
        let before = cache::now_ms();
        host.set_status(HostStatus::Reconnecting, Some("gone".into()));
        let st = host.state.lock().clone();
        assert!(st.stale && st.as_of_ms.is_some_and(|t| t >= before));
        assert_eq!(st.sessions.len(), 1);

        host.forget_cache();
        assert!(!file.exists());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn alert_text_is_sanitised_and_capped() {
        assert_eq!(safe_text("  a\t\tb\r\n\n c  ", 160), "a b c");
        assert_eq!(safe_text("x\u{1b}[31my\u{7}\u{9b}z\u{0}", 160), "x[31myz");
        assert_eq!(safe_text("\u{202e}evil\u{2066}\u{200f}.txt\u{2069}\u{200e}", 160), "evil.txt");
        assert_eq!(safe_text("abcdef", 6), "abcdef");
        assert_eq!(safe_text("abcdefg", 6), "abcde…");
        assert_eq!(safe_text("abcd efgh", 6), "abcd…");
        assert_eq!(safe_text("ééééééé", 4), "ééé…");
        assert_eq!(safe_text(&"\u{1}".repeat(10_000), 4), "");
    }

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
