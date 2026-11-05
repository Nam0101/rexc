//! The binary handshake of a data connection (`GET /api/data`).
//!
//! HELLO   = 0x01 + ticket[16]
//! RESUME  = 0x02 + token[16] + received:u64le
//! WELCOME = 0x81 + token[16] + 0,0,0 + keep_ms:u32le + nonce:u64le (32 bytes)
//! REJECT  = 0x82 + reason

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

pub const HELLO: u8 = 0x01;
pub const RESUME: u8 = 0x02;
pub const WELCOME: u8 = 0x81;
pub const REJECT: u8 = 0x82;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DataWelcome {
    pub reconnect_token: [u8; 16],
    /// How long the server keeps the stream for a resume.
    pub keep_ms: u32,
    /// Marks Rex's in-band APC messages; never zero.
    pub nonce: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    UnknownTicket,
    AlreadyConnected,
    Unavailable,
    Limit,
    Malformed,
    ServerFailed,
    WrongSubprotocol,
    Other(u8),
}

impl RejectReason {
    pub fn from_byte(b: u8) -> Self {
        match b {
            0x01 => Self::UnknownTicket,
            0x02 => Self::AlreadyConnected,
            0x03 => Self::Unavailable,
            0x04 => Self::Limit,
            0x05 => Self::Malformed,
            0x06 => Self::ServerFailed,
            0x07 => Self::WrongSubprotocol,
            other => Self::Other(other),
        }
    }
}

impl std::fmt::Display for RejectReason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            Self::UnknownTicket => "ticket or token unknown, used or expired",
            Self::AlreadyConnected => "this client already streams that block",
            Self::Unavailable => "stream not available",
            Self::Limit => "server limit reached",
            Self::Malformed => "handshake malformed or late",
            Self::ServerFailed => "server failed",
            Self::WrongSubprotocol => "ticket was issued for another subprotocol",
            Self::Other(b) => return write!(f, "reject reason {b:#x}"),
        };
        f.write_str(s)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum HandshakeError {
    #[error("data handshake rejected: {0}")]
    Rejected(RejectReason),
    #[error("ticket is not 22 base64url characters")]
    BadTicket,
    #[error("unexpected handshake reply")]
    Malformed,
}

pub fn decode_ticket(ticket: &str) -> Result<[u8; 16], HandshakeError> {
    let bytes = URL_SAFE_NO_PAD.decode(ticket.trim()).map_err(|_| HandshakeError::BadTicket)?;
    bytes.try_into().map_err(|_| HandshakeError::BadTicket)
}

pub fn hello(ticket: &[u8; 16]) -> Vec<u8> {
    let mut v = Vec::with_capacity(17);
    v.push(HELLO);
    v.extend_from_slice(ticket);
    v
}

pub fn resume(token: &[u8; 16], received: u64) -> Vec<u8> {
    let mut v = Vec::with_capacity(25);
    v.push(RESUME);
    v.extend_from_slice(token);
    v.extend_from_slice(&received.to_le_bytes());
    v
}

pub fn parse_reply(b: &[u8]) -> Result<DataWelcome, HandshakeError> {
    match b.first() {
        Some(&WELCOME) if b.len() >= 32 => {
            let mut token = [0u8; 16];
            token.copy_from_slice(&b[1..17]);
            let keep_ms = u32::from_le_bytes([b[20], b[21], b[22], b[23]]);
            let nonce = u64::from_le_bytes(b[24..32].try_into().unwrap());
            Ok(DataWelcome { reconnect_token: token, keep_ms, nonce })
        }
        Some(&REJECT) if b.len() >= 2 => Err(HandshakeError::Rejected(RejectReason::from_byte(b[1]))),
        _ => Err(HandshakeError::Malformed),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_round_trip() {
        let t = decode_ticket("AAECAwQFBgcICQoLDA0ODw").unwrap();
        assert_eq!(t, [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]);
        let h = hello(&t);
        assert_eq!(h.len(), 17);
        assert_eq!(h[0], HELLO);
        let r = resume(&t, 0x0102);
        assert_eq!(r.len(), 25);
        assert_eq!(&r[17..], &[0x02, 0x01, 0, 0, 0, 0, 0, 0]);

        let mut w = vec![WELCOME];
        w.extend([9u8; 16]);
        w.extend([0, 0, 0]);
        w.extend(60_000u32.to_le_bytes());
        w.extend(77u64.to_le_bytes());
        let parsed = parse_reply(&w).unwrap();
        assert_eq!(parsed.keep_ms, 60_000);
        assert_eq!(parsed.nonce, 77);
        assert_eq!(parse_reply(&[REJECT, 0x02]), Err(HandshakeError::Rejected(RejectReason::AlreadyConnected)));
        assert!(decode_ticket("short").is_err());
    }
}
