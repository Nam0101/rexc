//! A terminal on screen: its data stream, emulator, ownership, and input queue.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;
use rexc_client::{DataEvent, DataStream};
use rexc_proto::{TERMINAL_DATA_REF, TERMINAL_SUBPROTOCOL};
use rexc_term::Terminal;
use serde_json::json;
use tokio::sync::mpsc;

use crate::host::HostHandle;
use crate::notify::Notifier;

/// Longest single `write` call; bigger pastes are split in order.
const MAX_WRITE: usize = 48 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamState {
    Connecting,
    Live,
    Reconnecting,
    Ended { reason: String },
}

/// Fit: this phone owns the terminal and it reflows to the phone's size.
/// Mirror: another client owns it; we show it at that size.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SizeMode {
    Fit,
    Mirror,
}

#[derive(Debug, Clone, PartialEq, Eq)]
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Viewport {
    pub cols: u16,
    pub rows: u16,
    pub cell_w: u32,
    pub cell_h: u32,
}

#[derive(Debug, Clone)]
pub enum TermCmd {
    OwnerChanged(Option<String>),
    BlockClosed,
    HostOffline,
    Reconnected,
    SetViewport(Viewport),
    TakeOver,
    Release,
    Close,
}

pub struct TerminalHandle {
    pub id: u64,
    pub host: Arc<HostHandle>,
    pub session_id: String,
    pub block_id: String,
    pub term: Mutex<Terminal>,
    pub info: Mutex<TerminalInfo>,
    viewport: Mutex<Option<Viewport>>,
    cmd: mpsc::UnboundedSender<TermCmd>,
    input: mpsc::UnboundedSender<Vec<u8>>,
    notifier: Notifier,
}

impl TerminalHandle {
    pub fn send(&self, cmd: TermCmd) {
        let _ = self.cmd.send(cmd);
    }

    /// Queue bytes for the program, in order, batched into as few calls as possible.
    pub fn write(&self, bytes: Vec<u8>) {
        if !bytes.is_empty() {
            let _ = self.input.send(bytes);
        }
    }

    pub fn info(&self) -> TerminalInfo {
        self.info.lock().clone()
    }

    pub fn viewport(&self) -> Option<Viewport> {
        *self.viewport.lock()
    }

    fn update<R>(&self, f: impl FnOnce(&mut TerminalInfo) -> R) -> R {
        let r = f(&mut self.info.lock());
        self.notifier.terminal_state(self.id);
        r
    }
}

#[derive(Default)]
pub struct TerminalRegistry {
    terminals: Mutex<HashMap<u64, Arc<TerminalHandle>>>,
}

impl TerminalRegistry {
    pub fn get(&self, id: u64) -> Option<Arc<TerminalHandle>> {
        self.terminals.lock().get(&id).cloned()
    }

    pub fn remove(&self, id: u64) -> Option<Arc<TerminalHandle>> {
        self.terminals.lock().remove(&id)
    }

    pub fn all(&self) -> Vec<Arc<TerminalHandle>> {
        self.terminals.lock().values().cloned().collect()
    }

    pub fn broadcast(&self, host_id: &str, cmd: TermCmd) {
        for t in self.all().into_iter().filter(|t| t.host.id == host_id) {
            t.send(cmd.clone());
        }
    }

    pub fn broadcast_session(&self, host_id: &str, session_id: &str, cmd: TermCmd) {
        for t in self.all().into_iter().filter(|t| t.host.id == host_id && t.session_id == session_id) {
            t.send(cmd.clone());
        }
    }

    pub fn broadcast_block(&self, host_id: &str, block_id: &str, cmd: TermCmd) {
        for t in self.all().into_iter().filter(|t| t.host.id == host_id && t.block_id == block_id) {
            t.send(cmd.clone());
        }
    }

    /// Open a stream to a block. `take` claims the block right away even if
    /// another client owns it; otherwise we only claim an unowned block.
    #[allow(clippy::too_many_arguments)]
    pub fn open(self: &Arc<Self>, id: u64, host: Arc<HostHandle>, session_id: &str, block_id: &str, take: bool, notifier: Notifier, handle: &tokio::runtime::Handle) -> Arc<TerminalHandle> {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let (in_tx, in_rx) = mpsc::unbounded_channel();
        let owner = host.state.lock().block(session_id, block_id).and_then(|b| b.owner.clone());
        let me = host.client_id();
        let owned_by_me = owner.is_some() && owner == me;
        let t = Arc::new(TerminalHandle {
            id,
            host,
            session_id: session_id.to_string(),
            block_id: block_id.to_string(),
            term: Mutex::new(Terminal::new(80, 24)),
            info: Mutex::new(TerminalInfo {
                terminal_id: id,
                host_id: String::new(),
                session_id: session_id.to_string(),
                block_id: block_id.to_string(),
                stream: StreamState::Connecting,
                owner: owner.clone(),
                owned_by_me,
                mode: if owned_by_me || owner.is_none() || take { SizeMode::Fit } else { SizeMode::Mirror },
                cols: 0,
                rows: 0,
                exit_code: None,
                has_snapshot: false,
            }),
            viewport: Mutex::new(None),
            cmd: cmd_tx,
            input: in_tx,
            notifier,
        });
        t.info.lock().host_id = t.host.id.clone();
        self.terminals.lock().insert(id, t.clone());
        handle.spawn(run(t.clone(), cmd_rx, take));
        handle.spawn(input_loop(t.clone(), in_rx));
        t
    }
}

async fn ticket(t: &TerminalHandle) -> Option<String> {
    let control = t.host.control()?;
    let v = control
        .call("data.connect", json!({"session_id": t.session_id, "block_id": t.block_id, "data_ref_id": TERMINAL_DATA_REF, "subprotocol": TERMINAL_SUBPROTOCOL}))
        .await
        .ok()?;
    v["ticket"].as_str().map(str::to_owned)
}

async fn claim(t: &TerminalHandle, mode: &str) {
    let payload = json!({"session_id": t.session_id, "block_ids": [t.block_id], "mode": mode});
    match t.host.call("session.own_block", payload).await {
        Ok(v) => {
            let owner = v["blocks"][0]["owner"].as_str().map(str::to_owned);
            apply_owner(t, owner).await;
        }
        Err(e) => tracing::info!("own_block: {e}"),
    }
}

async fn release(t: &TerminalHandle) {
    let payload = json!({"session_id": t.session_id, "block_ids": [t.block_id]});
    match t.host.call("session.disown_block", payload).await {
        Ok(v) => {
            let owner = v["blocks"][0]["owner"].as_str().map(str::to_owned);
            t.update(|i| i.mode = SizeMode::Mirror);
            apply_owner(t, owner).await;
        }
        Err(e) => tracing::info!("disown_block: {e}"),
    }
}

async fn apply_owner(t: &TerminalHandle, owner: Option<String>) {
    let me = t.host.client_id();
    let mine = owner.is_some() && owner == me;
    let became_mine = t.update(|i| {
        let was = i.owned_by_me;
        i.owner = owner;
        i.owned_by_me = mine;
        if !mine && was {
            i.mode = SizeMode::Mirror;
        }
        mine && !was
    });
    if became_mine || mine {
        push_size(t).await;
    }
}

/// Tell the server our size, when we own the block (or nobody does).
async fn push_size(t: &TerminalHandle) {
    let Some(v) = t.viewport() else { return };
    let (mine, mode) = {
        let i = t.info.lock();
        (i.owned_by_me, i.mode)
    };
    if !mine || mode != SizeMode::Fit {
        return;
    }
    let args = json!({"columns": v.cols, "rows": v.rows, "cell_width_px": v.cell_w, "cell_height_px": v.cell_h});
    if let Some(control) = t.host.control() {
        if let Err(e) = control.call_block(&t.session_id, &t.block_id, "resize", args).await {
            tracing::debug!("resize: {e}");
        }
    }
}

async fn run(t: Arc<TerminalHandle>, mut cmd_rx: mpsc::UnboundedReceiver<TermCmd>, take: bool) {
    // Claim first so the snapshot already comes at our size when we fit.
    let (owner, mine) = {
        let i = t.info.lock();
        (i.owner.clone(), i.owned_by_me)
    };
    if take || (owner.is_none() && !mine) {
        claim(&t, if take { "take" } else { "request" }).await;
    }
    let endpoint = match t.host.endpoint() {
        Ok(e) => e,
        Err(e) => {
            t.update(|i| i.stream = StreamState::Ended { reason: e.to_string() });
            return;
        }
    };
    let mut stream: Option<(DataStream, mpsc::UnboundedReceiver<DataEvent>)> = None;
    let mut want_ticket = true;
    loop {
        if want_ticket {
            want_ticket = false;
            match ticket(&t).await {
                Some(tk) => match &stream {
                    Some((s, _)) => s.restart(tk),
                    None => stream = Some(DataStream::open(endpoint.clone(), tk)),
                },
                None => {
                    // No connection yet; wait for Reconnected.
                    t.update(|i| i.stream = StreamState::Reconnecting);
                }
            }
        }
        let cmd = match stream.as_mut() {
            Some((_, rx)) => tokio::select! {
                ev = rx.recv() => match ev {
                    Some(ev) => { if handle_data(&t, ev, &mut want_ticket).await { continue } else { return } }
                    None => return,
                },
                cmd = cmd_rx.recv() => cmd,
            },
            None => cmd_rx.recv().await,
        };
        match cmd {
            Some(TermCmd::OwnerChanged(owner)) => apply_owner(&t, owner).await,
            Some(TermCmd::SetViewport(v)) => {
                let changed = {
                    let mut vp = t.viewport.lock();
                    let changed = *vp != Some(v);
                    *vp = Some(v);
                    changed
                };
                {
                    let mut term = t.term.lock();
                    let (c, r) = term.size();
                    let mode = t.info.lock().mode;
                    if mode == SizeMode::Fit && t.info.lock().owned_by_me {
                        term.resize(v.cols, v.rows, v.cell_w, v.cell_h);
                    } else {
                        term.resize(c, r, v.cell_w, v.cell_h);
                    }
                }
                if changed {
                    push_size(&t).await;
                    t.notifier.terminal(t.id);
                }
            }
            Some(TermCmd::TakeOver) => {
                t.update(|i| i.mode = SizeMode::Fit);
                claim(&t, "take").await;
            }
            Some(TermCmd::Release) => release(&t).await,
            Some(TermCmd::Reconnected) => {
                let (owner, mine) = {
                    let i = t.info.lock();
                    (i.owner.clone(), i.owned_by_me)
                };
                if mine || owner.is_none() {
                    claim(&t, "request").await;
                }
                if matches!(t.info.lock().stream, StreamState::Reconnecting | StreamState::Connecting) && stream.is_none() {
                    want_ticket = true;
                }
            }
            Some(TermCmd::HostOffline) => t.update(|i| i.stream = StreamState::Reconnecting),
            Some(TermCmd::BlockClosed) => {
                t.update(|i| i.stream = StreamState::Ended { reason: "closed".into() });
                return;
            }
            Some(TermCmd::Close) | None => {
                let mine = t.info.lock().owned_by_me;
                if mine {
                    release(&t).await;
                }
                return;
            }
        }
    }
}

/// Apply one data event. Returns false when the stream is over.
async fn handle_data(t: &Arc<TerminalHandle>, ev: DataEvent, want_ticket: &mut bool) -> bool {
    match ev {
        DataEvent::Started => t.update(|i| {
            i.stream = StreamState::Connecting;
            i.has_snapshot = false;
        }),
        DataEvent::Snapshot(bytes) => {
            let result = t.term.lock().restore(&bytes);
            match result {
                Ok(()) => {
                    let (c, r) = t.term.lock().size();
                    t.update(|i| {
                        i.stream = StreamState::Live;
                        i.has_snapshot = true;
                        i.cols = c;
                        i.rows = r;
                    });
                    t.notifier.terminal(t.id);
                }
                Err(e) => {
                    tracing::warn!("snapshot: {e}");
                    *want_ticket = true;
                }
            }
        }
        DataEvent::Output(bytes) => {
            t.term.lock().write(&bytes);
            t.notifier.terminal(t.id);
        }
        DataEvent::Resize { cols, rows, owner, .. } => {
            let vp = t.viewport();
            {
                let mut term = t.term.lock();
                let (cw, ch) = vp.map(|v| (v.cell_w, v.cell_h)).unwrap_or((0, 0));
                term.resize(cols as u16, rows as u16, cw, ch);
            }
            let me = t.host.client_id();
            t.update(|i| {
                i.cols = cols as u16;
                i.rows = rows as u16;
                if owner.is_some() {
                    i.owner = owner.clone();
                    i.owned_by_me = owner == me;
                }
                i.stream = if i.has_snapshot { StreamState::Live } else { i.stream.clone() };
            });
            t.notifier.terminal(t.id);
        }
        DataEvent::Theme { foreground, background, cursor, palette, .. } => {
            t.term.lock().apply_theme(foreground.as_deref(), background.as_deref(), cursor.as_deref(), &palette);
            t.notifier.terminal(t.id);
        }
        DataEvent::ChildExited { exit_code } => t.update(|i| i.exit_code = Some(exit_code)),
        DataEvent::Reconnecting => t.update(|i| i.stream = StreamState::Reconnecting),
        DataEvent::Resumed => t.update(|i| i.stream = StreamState::Live),
        DataEvent::NeedsTicket => *want_ticket = true,
        DataEvent::Ended { reason } => {
            t.update(|i| i.stream = StreamState::Ended { reason });
            return false;
        }
    }
    true
}

/// Sends queued input as `terminal.write` calls, one in flight at a time so
/// the program sees keys in order, and coalesces whatever arrived meanwhile.
async fn input_loop(t: Arc<TerminalHandle>, mut rx: mpsc::UnboundedReceiver<Vec<u8>>) {
    while let Some(first) = rx.recv().await {
        let mut batch = first;
        while batch.len() < MAX_WRITE {
            match rx.try_recv() {
                Ok(more) => batch.extend(more),
                Err(_) => break,
            }
        }
        for chunk in split_utf8(&batch, MAX_WRITE) {
            let data = String::from_utf8_lossy(chunk).into_owned();
            let mut tries = 0;
            loop {
                let Some(control) = t.host.control() else {
                    tokio::time::sleep(Duration::from_millis(200)).await;
                    tries += 1;
                    if tries > 50 {
                        break;
                    }
                    continue;
                };
                match control.call_block(&t.session_id, &t.block_id, "write", json!({"data": data})).await {
                    Ok(_) => break,
                    Err(rexc_client::ClientError::Closed) if tries < 3 => {
                        tries += 1;
                        tokio::time::sleep(Duration::from_millis(300)).await;
                    }
                    Err(e) => {
                        tracing::info!("write failed: {e}");
                        break;
                    }
                }
            }
        }
    }
}

/// Split at UTF-8 boundaries so a chunk never ends mid-character.
fn split_utf8(bytes: &[u8], max: usize) -> Vec<&[u8]> {
    let mut out = Vec::new();
    let mut start = 0;
    while start < bytes.len() {
        let mut end = (start + max).min(bytes.len());
        while end < bytes.len() && end > start && (bytes[end] & 0xC0) == 0x80 {
            end -= 1;
        }
        out.push(&bytes[start..end]);
        start = end;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_on_character_boundaries() {
        let s = "aé你b".repeat(100);
        let parts = split_utf8(s.as_bytes(), 7);
        let joined: Vec<u8> = parts.iter().flat_map(|p| p.to_vec()).collect();
        assert_eq!(joined, s.as_bytes());
        for p in parts {
            assert!(std::str::from_utf8(p).is_ok());
            assert!(p.len() <= 7);
        }
    }
}
