//! One terminal's data stream, kept alive across drops with a byte-exact RESUME.
//!
//! The stream task owns the framing: it separates the Ghostty snapshot from
//! the output and Rex's in-band messages, counts every stream byte it has
//! handed on (that count is what RESUME sends), and asks for a new ticket only
//! when the server says a resume is impossible.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use rexc_proto::TERMINAL_SUBPROTOCOL;
use rexc_proto::apc::{Chunk, StreamMessage, StreamSplitter};
use rexc_proto::data::{self, DataWelcome, HandshakeError};
use rexc_proto::snapshot::{Framed, SnapshotFramer};
use tokio::sync::{mpsc, watch};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;

use crate::endpoint::{Endpoint, Ws};
use crate::{ClientError, Result};

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const PING_EVERY: Duration = Duration::from_secs(20);
const DEAD_AFTER: Duration = Duration::from_secs(50);
const MAX_BACKOFF: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, PartialEq)]
pub enum DataEvent {
    /// A new stream began (fresh ticket). Reset the emulator; a snapshot follows.
    Started,
    /// The whole Ghostty snapshot. Restore it, then apply `Output`.
    Snapshot(Vec<u8>),
    /// Program output with Rex's messages removed.
    Output(Vec<u8>),
    Resize { cols: u32, rows: u32, cell_width_px: u32, cell_height_px: u32, owner: Option<String> },
    Theme { foreground: Option<String>, background: Option<String>, cursor: Option<String>, palette: Vec<String>, scheme: Option<String> },
    ChildExited { exit_code: i32 },
    /// The connection dropped; a resume is being tried.
    Reconnecting,
    /// Resumed without losing a byte.
    Resumed,
    /// Resume is impossible: call `restart` with a new ticket.
    NeedsTicket,
    /// The stream is over for good (block closed or stream ended).
    Ended { reason: String },
}

/// A running stream. Dropping it closes the connection with status 1000,
/// which ends the stream on the server too.
pub struct DataStream {
    ticket_tx: mpsc::UnboundedSender<String>,
    stop_tx: watch::Sender<bool>,
}

impl Drop for DataStream {
    fn drop(&mut self) {
        let _ = self.stop_tx.send(true);
    }
}

impl DataStream {
    pub fn open(endpoint: Endpoint, ticket: String) -> (Self, mpsc::UnboundedReceiver<DataEvent>) {
        let (ev_tx, ev_rx) = mpsc::unbounded_channel();
        let (ticket_tx, ticket_rx) = mpsc::unbounded_channel();
        let (stop_tx, stop_rx) = watch::channel(false);
        tokio::spawn(run(endpoint, ticket, ticket_rx, stop_rx, ev_tx));
        (Self { ticket_tx, stop_tx }, ev_rx)
    }

    /// Start over with a fresh ticket from `data.connect`.
    pub fn restart(&self, ticket: String) {
        let _ = self.ticket_tx.send(ticket);
    }
}

enum Outcome {
    Stopped,
    Ended(String),
    Dropped,
    Fresh,
}

enum Hand {
    Hello(String),
    Resume([u8; 16], u64),
}

struct Live {
    welcome: DataWelcome,
    received: u64,
}

async fn run(endpoint: Endpoint, first_ticket: String, mut ticket_rx: mpsc::UnboundedReceiver<String>, mut stop: watch::Receiver<bool>, tx: mpsc::UnboundedSender<DataEvent>) {
    let mut ticket = Some(first_ticket);
    let mut live: Option<Live> = None;
    let mut splitter = StreamSplitter::new(0);
    let mut framer: Option<SnapshotFramer> = None;
    let mut backoff = Duration::from_millis(250);

    loop {
        if *stop.borrow() {
            return;
        }
        let attempt = match (ticket.take(), &live) {
            (Some(t), _) => Some((connect(&endpoint, Hand::Hello(t)).await, true)),
            (None, Some(l)) => Some((connect(&endpoint, Hand::Resume(l.welcome.reconnect_token, l.received)).await, false)),
            (None, None) => None,
        };
        let Some((attempt, fresh)) = attempt else {
            if tx.send(DataEvent::NeedsTicket).is_err() {
                return;
            }
            tokio::select! {
                t = ticket_rx.recv() => match t { Some(t) => { ticket = Some(t); continue; } None => return },
                _ = stop.changed() => return,
            }
        };
        let mut ws = match attempt {
            Ok((ws, welcome)) => {
                if fresh {
                    splitter = StreamSplitter::new(welcome.nonce);
                    framer = Some(SnapshotFramer::new());
                    live = Some(Live { welcome, received: 0 });
                    if tx.send(DataEvent::Started).is_err() {
                        return;
                    }
                } else if let Some(l) = live.as_mut() {
                    l.welcome = welcome;
                    if tx.send(DataEvent::Resumed).is_err() {
                        return;
                    }
                }
                backoff = Duration::from_millis(250);
                ws
            }
            Err(ClientError::Handshake(HandshakeError::Rejected(r))) => {
                tracing::info!("data handshake rejected: {r}");
                live = None;
                continue;
            }
            Err(e) => {
                tracing::debug!("data connect failed: {e}");
                if live.is_none() {
                    continue; // asks for a ticket
                }
                tokio::select! {
                    _ = tokio::time::sleep(backoff) => {}
                    t = ticket_rx.recv() => if let Some(t) = t { ticket = Some(t); live = None; },
                    _ = stop.changed() => return,
                }
                backoff = (backoff * 2).min(MAX_BACKOFF);
                continue;
            }
        };

        let Some(l) = live.as_mut() else { continue };
        let outcome = pump(&mut ws, &mut splitter, &mut framer, &mut l.received, &tx, &mut stop, &mut ticket_rx, &mut ticket).await;
        match outcome {
            Outcome::Stopped => {
                let _ = ws.close(Some(CloseFrame { code: CloseCode::Normal, reason: "".into() })).await;
                return;
            }
            Outcome::Ended(reason) => {
                let _ = tx.send(DataEvent::Ended { reason });
                return;
            }
            Outcome::Dropped => {
                let _ = tx.send(DataEvent::Reconnecting);
            }
            Outcome::Fresh => live = None,
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn pump(
    ws: &mut Ws,
    splitter: &mut StreamSplitter,
    framer: &mut Option<SnapshotFramer>,
    received: &mut u64,
    tx: &mpsc::UnboundedSender<DataEvent>,
    stop: &mut watch::Receiver<bool>,
    ticket_rx: &mut mpsc::UnboundedReceiver<String>,
    ticket: &mut Option<String>,
) -> Outcome {
    let mut ticker = tokio::time::interval(PING_EVERY);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last = tokio::time::Instant::now();
    loop {
        tokio::select! {
            m = ws.next() => {
                last = tokio::time::Instant::now();
                match m {
                    Some(Ok(Message::Binary(b))) => {
                        *received += b.len() as u64;
                        let live: Vec<u8> = match framer.as_mut().map(|f| f.push(&b)) {
                            None => b.to_vec(),
                            Some(Framed::Pending) => continue,
                            Some(Framed::Complete { snapshot, rest }) => {
                                *framer = None;
                                if tx.send(DataEvent::Snapshot(snapshot)).is_err() { return Outcome::Stopped; }
                                rest
                            }
                            Some(Framed::NotSnapshot(raw)) => { *framer = None; raw }
                            Some(Framed::Invalid(msg)) => {
                                tracing::warn!("bad snapshot: {msg}");
                                *framer = None;
                                return Outcome::Fresh;
                            }
                        };
                        for chunk in splitter.feed(&live) {
                            let ev = match chunk {
                                Chunk::Output(o) => DataEvent::Output(o),
                                Chunk::Message(StreamMessage::Resize { cols, rows, cell_width_px, cell_height_px, owner }) =>
                                    DataEvent::Resize { cols, rows, cell_width_px, cell_height_px, owner },
                                Chunk::Message(StreamMessage::Theme { foreground, background, cursor, palette, scheme }) =>
                                    DataEvent::Theme { foreground, background, cursor, palette: palette.unwrap_or_default(), scheme },
                                Chunk::Message(StreamMessage::ChildExited { exit_code }) => DataEvent::ChildExited { exit_code },
                                Chunk::Message(StreamMessage::Unknown) => continue,
                            };
                            if tx.send(ev).is_err() { return Outcome::Stopped; }
                        }
                    }
                    Some(Ok(Message::Close(f))) => {
                        let (code, reason) = f.map(|f| (u16::from(f.code), f.reason.to_string())).unwrap_or((1005, String::new()));
                        return match code {
                            1000 | 4000 => Outcome::Ended(if reason.is_empty() { "stream ended".into() } else { reason }),
                            1008 if reason.contains("unavailable") => Outcome::Ended(reason),
                            1002 | 1008 | 1011 | 1013 => Outcome::Fresh,
                            _ => Outcome::Dropped,
                        };
                    }
                    Some(Ok(_)) => {}
                    Some(Err(_)) | None => return Outcome::Dropped,
                }
            }
            t = ticket_rx.recv() => {
                if let Some(t) = t {
                    *ticket = Some(t);
                    let _ = ws.close(Some(CloseFrame { code: CloseCode::Normal, reason: "".into() })).await;
                    return Outcome::Fresh;
                }
            }
            _ = stop.changed() => return Outcome::Stopped,
            _ = ticker.tick() => {
                if last.elapsed() > DEAD_AFTER { return Outcome::Dropped; }
                if ws.send(Message::Ping(Default::default())).await.is_err() { return Outcome::Dropped; }
            }
        }
    }
}

async fn connect(endpoint: &Endpoint, hand: Hand) -> Result<(Ws, DataWelcome)> {
    let fut = async {
        let mut ws = endpoint.websocket("/api/data", TERMINAL_SUBPROTOCOL).await?;
        let frame = match &hand {
            Hand::Hello(t) => data::hello(&data::decode_ticket(t)?),
            Hand::Resume(token, n) => data::resume(token, *n),
        };
        ws.send(Message::Binary(frame.into())).await?;
        loop {
            match ws.next().await {
                Some(Ok(Message::Binary(b))) => return Ok((ws, data::parse_reply(&b)?)),
                Some(Ok(Message::Close(_))) | None => return Err(ClientError::Closed),
                Some(Ok(_)) => continue,
                Some(Err(e)) => return Err(e.into()),
            }
        }
    };
    tokio::time::timeout(HANDSHAKE_TIMEOUT, fut).await.map_err(|_| ClientError::Timeout)?
}
