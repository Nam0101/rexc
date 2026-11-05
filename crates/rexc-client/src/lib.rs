//! Talks to a Rex server: one control connection per server and one data
//! stream per terminal on screen. Policy (reconnect schedules, ownership,
//! what to show) lives in `rexc-core`; this crate only moves messages.

pub mod control;
pub mod data;
pub mod dns;
pub mod endpoint;

pub use control::{Control, ControlEvent};
pub use data::{DataEvent, DataStream};
pub use endpoint::Endpoint;
use rexc_proto::control::ResponseError;
use rexc_proto::data::HandshakeError;

#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    #[error("bad address: {0}")]
    Address(String),
    #[error("connect: {0}")]
    Io(#[from] std::io::Error),
    #[error("websocket: {0}")]
    WebSocket(#[from] tokio_tungstenite::tungstenite::Error),
    #[error("tls: {0}")]
    Tls(String),
    #[error("server refused: {0}")]
    Refused(String),
    #[error("protocol: {0}")]
    Protocol(String),
    #[error("timed out")]
    Timeout,
    #[error("connection closed")]
    Closed,
    #[error("{0}")]
    Handshake(#[from] HandshakeError),
    #[error("{0}")]
    Response(ResponseError),
}

impl ClientError {
    /// The error code of a failed request, such as `not_found`.
    pub fn code(&self) -> Option<&str> {
        match self {
            Self::Response(e) => Some(&e.code),
            _ => None,
        }
    }
}

pub type Result<T> = std::result::Result<T, ClientError>;
