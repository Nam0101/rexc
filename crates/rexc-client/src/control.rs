//! The control connection: hello/welcome, requests matched to responses, and
//! events delivered in order with their `seq`.

use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use rexc_proto::CONTROL_SUBPROTOCOL;
use rexc_proto::control::{ClientInfo, Hello, Request, Response, ServerMessage, Welcome};
use serde_json::Value;
use tokio::sync::{Mutex, mpsc, oneshot};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;

use crate::endpoint::{Endpoint, Ws};
use crate::{ClientError, Result};

const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
const PING_EVERY: Duration = Duration::from_secs(20);
const DEAD_AFTER: Duration = Duration::from_secs(50);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq)]
pub enum ControlEvent {
    /// An event from the server, in `seq` order.
    Event { seq: u64, name: String, payload: Value },
    /// The connection is gone. Pending and future calls fail with `Closed`.
    Closed { code: u16, reason: String },
}

type Pending = Arc<Mutex<HashMap<String, oneshot::Sender<Result<(u64, Value)>>>>>;

/// A live control connection. Clone it freely; dropping the last clone closes it.
#[derive(Clone)]
pub struct Control {
    inner: Arc<Inner>,
}

struct Inner {
    welcome: Welcome,
    out: mpsc::UnboundedSender<Message>,
    pending: Pending,
    next_id: AtomicU64,
    reader: tokio::task::JoinHandle<()>,
    writer: tokio::task::JoinHandle<()>,
}

impl Drop for Inner {
    fn drop(&mut self) {
        let _ = self.out.send(Message::Close(Some(CloseFrame { code: CloseCode::Normal, reason: "".into() })));
        self.reader.abort();
        let writer = std::mem::replace(&mut self.writer, tokio::spawn(async {}));
        tokio::spawn(async move {
            let _ = tokio::time::timeout(Duration::from_secs(2), writer).await;
        });
    }
}

impl Control {
    /// Open the connection and complete the hello/welcome exchange.
    pub async fn connect(endpoint: &Endpoint, client: &ClientInfo, reconnect_token: Option<&str>) -> Result<(Self, mpsc::UnboundedReceiver<ControlEvent>)> {
        let mut ws = endpoint.websocket("/api/control", CONTROL_SUBPROTOCOL).await?;
        let hello = serde_json::to_string(&Hello::new(client, reconnect_token)).map_err(|e| ClientError::Protocol(e.to_string()))?;
        ws.send(Message::Text(hello.into())).await?;
        let welcome = tokio::time::timeout(HELLO_TIMEOUT, read_welcome(&mut ws)).await.map_err(|_| ClientError::Timeout)??;
        if welcome.protocol != CONTROL_SUBPROTOCOL {
            return Err(ClientError::Protocol(format!("unexpected protocol {}", welcome.protocol)));
        }
        let (out_tx, out_rx) = mpsc::unbounded_channel::<Message>();
        let (ev_tx, ev_rx) = mpsc::unbounded_channel::<ControlEvent>();
        let pending: Pending = Arc::default();
        let (sink, stream) = ws.split();
        let writer = tokio::spawn(write_loop(sink, out_rx));
        let reader = tokio::spawn(read_loop(stream, out_tx.clone(), ev_tx, pending.clone()));
        Ok((Self { inner: Arc::new(Inner { welcome, out: out_tx, pending, next_id: AtomicU64::new(1), reader, writer }) }, ev_rx))
    }

    pub fn welcome(&self) -> &Welcome {
        &self.inner.welcome
    }

    pub fn client_id(&self) -> Option<&str> {
        self.inner.welcome.client.as_ref().map(|c| c.id.as_str())
    }

    pub fn reconnect_token(&self) -> Option<&str> {
        self.inner.welcome.client.as_ref().map(|c| c.reconnect_token.as_str())
    }

    pub fn is_open(&self) -> bool {
        !self.inner.reader.is_finished()
    }

    /// Call a method. Returns the response's `seq` and payload.
    pub async fn call_seq(&self, method: &str, payload: Value) -> Result<(u64, Value)> {
        let id = self.inner.next_id.fetch_add(1, Ordering::Relaxed);
        let payload_ref = (!payload.is_null()).then_some(&payload);
        let text = serde_json::to_string(&Request::new(id, method, payload_ref)).map_err(|e| ClientError::Protocol(e.to_string()))?;
        let (tx, rx) = oneshot::channel();
        self.inner.pending.lock().await.insert(id.to_string(), tx);
        if self.inner.out.send(Message::Text(text.into())).is_err() {
            self.inner.pending.lock().await.remove(&id.to_string());
            return Err(ClientError::Closed);
        }
        match tokio::time::timeout(REQUEST_TIMEOUT, rx).await {
            Ok(Ok(r)) => r,
            Ok(Err(_)) => Err(ClientError::Closed),
            Err(_) => {
                self.inner.pending.lock().await.remove(&id.to_string());
                Err(ClientError::Timeout)
            }
        }
    }

    pub async fn call(&self, method: &str, payload: Value) -> Result<Value> {
        self.call_seq(method, payload).await.map(|(_, v)| v)
    }

    /// Call a method and decode its result.
    pub async fn call_as<T: serde::de::DeserializeOwned>(&self, method: &str, payload: Value) -> Result<(u64, T)> {
        let (seq, v) = self.call_seq(method, payload).await?;
        let t = serde_json::from_value(v).map_err(|e| ClientError::Protocol(format!("{method} result: {e}")))?;
        Ok((seq, t))
    }

    /// Call a block method: `com.superlogical.terminal.<short>`.
    pub async fn call_block(&self, session_id: &str, block_id: &str, short: &str, args: Value) -> Result<Value> {
        let payload = serde_json::json!({"session_id": session_id, "block_id": block_id, "args": args});
        self.call(&rexc_proto::terminal_method(short), payload).await
    }
}

async fn read_welcome(ws: &mut Ws) -> Result<Welcome> {
    loop {
        match ws.next().await {
            Some(Ok(Message::Text(t))) => match ServerMessage::parse(&t).map_err(|e| ClientError::Protocol(e.to_string()))? {
                ServerMessage::Welcome(w) => return Ok(w),
                ServerMessage::Other => continue,
                other => return Err(ClientError::Protocol(format!("expected welcome, got {other:?}"))),
            },
            Some(Ok(Message::Close(f))) => {
                let reason = f.map(|f| format!("{} {}", u16::from(f.code), f.reason)).unwrap_or_default();
                return Err(ClientError::Refused(reason));
            }
            Some(Ok(_)) => continue,
            Some(Err(e)) => return Err(e.into()),
            None => return Err(ClientError::Closed),
        }
    }
}

async fn write_loop(mut sink: futures_util::stream::SplitSink<Ws, Message>, mut rx: mpsc::UnboundedReceiver<Message>) {
    while let Some(m) = rx.recv().await {
        let closing = matches!(m, Message::Close(_));
        if sink.send(m).await.is_err() || closing {
            break;
        }
    }
    let _ = sink.close().await;
}

async fn read_loop(mut stream: futures_util::stream::SplitStream<Ws>, out: mpsc::UnboundedSender<Message>, events: mpsc::UnboundedSender<ControlEvent>, pending: Pending) {
    let mut ticker = tokio::time::interval(PING_EVERY);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last = tokio::time::Instant::now();
    let (code, reason) = loop {
        tokio::select! {
            m = stream.next() => {
                last = tokio::time::Instant::now();
                match m {
                    Some(Ok(Message::Text(t))) => match ServerMessage::parse(&t) {
                        Ok(ServerMessage::Response(r)) => deliver(&pending, r).await,
                        Ok(ServerMessage::Event(e)) => {
                            if events.send(ControlEvent::Event { seq: e.seq, name: e.payload.name, payload: e.payload.payload }).is_err() {
                                break (1000, String::from("listener gone"));
                            }
                        }
                        Ok(_) => {}
                        Err(e) => tracing::warn!("unreadable control message: {e}"),
                    },
                    Some(Ok(Message::Close(f))) => {
                        break f.map(|f| (u16::from(f.code), f.reason.to_string())).unwrap_or((1005, String::new()));
                    }
                    Some(Ok(Message::Ping(_))) | Some(Ok(Message::Pong(_))) | Some(Ok(Message::Frame(_))) | Some(Ok(Message::Binary(_))) => {}
                    Some(Err(e)) => break (1006, e.to_string()),
                    None => break (1006, String::from("connection dropped")),
                }
            }
            _ = ticker.tick() => {
                if last.elapsed() > DEAD_AFTER {
                    break (1006, String::from("no traffic"));
                }
                if out.send(Message::Ping(Default::default())).is_err() {
                    break (1006, String::from("writer gone"));
                }
            }
        }
    };
    for (_, tx) in pending.lock().await.drain() {
        let _ = tx.send(Err(ClientError::Closed));
    }
    let _ = events.send(ControlEvent::Closed { code, reason });
}

async fn deliver(pending: &Pending, r: Response) {
    let Some(tx) = pending.lock().await.remove(&r.id) else {
        tracing::debug!("response for unknown request {}", r.id);
        return;
    };
    let result = match r.error {
        Some(e) => Err(ClientError::Response(e)),
        None => Ok((r.seq, r.payload.unwrap_or(Value::Null))),
    };
    let _ = tx.send(result);
}
