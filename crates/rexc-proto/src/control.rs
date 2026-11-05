//! Messages on the control WebSocket: one JSON object per text frame.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::{API_MIN_REVISION, API_REVISION};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ClientInfo {
    pub kind: String,
    pub platform: String,
    pub name: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
}

impl ClientInfo {
    pub fn app(name: &str, version: &str, label: Option<String>) -> Self {
        Self {
            kind: "app".into(),
            platform: platform_name().into(),
            name: name.into(),
            version: version.into(),
            label,
        }
    }
}

fn platform_name() -> &'static str {
    if cfg!(target_os = "android") {
        "other"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else if cfg!(target_os = "linux") {
        "linux"
    } else {
        "other"
    }
}

/// The first message a client sends. Keep it under 512 bytes and never log the token.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Hello<'a> {
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub api_revision: u32,
    pub api_min_revision: u32,
    pub client: &'a ClientInfo,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reconnect_token: Option<&'a str>,
}

impl<'a> Hello<'a> {
    pub fn new(client: &'a ClientInfo, reconnect_token: Option<&'a str>) -> Self {
        Self {
            kind: "hello",
            api_revision: API_REVISION,
            api_min_revision: API_MIN_REVISION,
            client,
            reconnect_token,
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq, Default)]
pub struct WelcomeLimits {
    #[serde(default = "default_max_message")]
    pub max_message_bytes: u64,
}

fn default_max_message() -> u64 {
    65_536
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct WelcomeClient {
    pub id: String,
    pub reconnect_token: String,
    #[serde(default)]
    pub resumed: bool,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct Welcome {
    pub protocol: String,
    #[serde(default)]
    pub limits: WelcomeLimits,
    #[serde(default)]
    pub client: Option<WelcomeClient>,
    #[serde(default)]
    pub features: Vec<String>,
    #[serde(default)]
    pub api_revision: u32,
    #[serde(default)]
    pub api_server_revision: u32,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Request<'a> {
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub id: String,
    pub method: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payload: Option<&'a Value>,
}

impl<'a> Request<'a> {
    pub fn new(id: u64, method: &'a str, payload: Option<&'a Value>) -> Self {
        Self { kind: "request", id: id.to_string(), method, payload }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct ResponseError {
    pub code: String,
    pub message: String,
    #[serde(default)]
    pub status: u16,
}

impl std::fmt::Display for ResponseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} ({}): {}", self.code, self.status, self.message)
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct Response {
    pub id: String,
    #[serde(default)]
    pub seq: u64,
    #[serde(default)]
    pub payload: Option<Value>,
    #[serde(default)]
    pub error: Option<ResponseError>,
}

/// An event with its name split out; `payload` is the event's own fields.
#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct EventBody {
    #[serde(rename = "type")]
    pub name: String,
    #[serde(default)]
    pub payload: Value,
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct EventMessage {
    pub seq: u64,
    pub payload: EventBody,
}

/// Everything the server can send. Unknown `type`s decode to `Other`.
#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    Welcome(Welcome),
    Response(Response),
    Event(EventMessage),
    #[serde(other)]
    Other,
}

impl ServerMessage {
    pub fn parse(text: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_is_small_and_shaped_right() {
        let info = ClientInfo::app("rexc", "0.1.0", Some("pixel".into()));
        let s = serde_json::to_string(&Hello::new(&info, None)).unwrap();
        assert!(s.len() < 512);
        let v: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["type"], "hello");
        assert_eq!(v["api_revision"], 10);
        assert!(v.get("reconnect_token").is_none());
        assert_eq!(v["client"]["kind"], "app");
    }

    #[test]
    fn parses_welcome_response_event_and_unknown() {
        let w = ServerMessage::parse(
            r#"{"type":"welcome","protocol":"rex.control.v1","limits":{"max_message_bytes":1048576},"client":{"id":"client:1","reconnect_token":"t","resumed":false},"api_revision":10}"#,
        )
        .unwrap();
        match w {
            ServerMessage::Welcome(w) => {
                assert_eq!(w.limits.max_message_bytes, 1_048_576);
                assert_eq!(w.client.unwrap().id, "client:1");
            }
            other => panic!("{other:?}"),
        }
        let r = ServerMessage::parse(r#"{"type":"response","id":"2","seq":0,"error":{"code":"not_found","message":"no","status":404}}"#).unwrap();
        match r {
            ServerMessage::Response(r) => assert_eq!(r.error.unwrap().code, "not_found"),
            other => panic!("{other:?}"),
        }
        let e = ServerMessage::parse(r#"{"type":"event","seq":3,"payload":{"type":"session_created","payload":{"session":{"session_id":"session:a","label":"x"}}}}"#).unwrap();
        match e {
            ServerMessage::Event(e) => {
                assert_eq!(e.seq, 3);
                assert_eq!(e.payload.name, "session_created");
            }
            other => panic!("{other:?}"),
        }
        assert_eq!(ServerMessage::parse(r#"{"type":"sparkle"}"#).unwrap(), ServerMessage::Other);
    }
}
