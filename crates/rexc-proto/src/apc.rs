//! Splits program output from Rex's in-band stream messages.
//!
//! A Rex message is `ESC _ rex1;<nonce>;<json> ESC \`. Only a nonce equal to
//! the data WELCOME's is genuine; a look-alike a program printed stays in the
//! output so the emulator handles it like any other APC.

use serde::Deserialize;

const PREFIX: &[u8] = b"\x1b_rex1;";
const ST: &[u8] = b"\x1b\\";
/// Longer than this and it's not ours; pass it through.
const MAX_MESSAGE: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum StreamMessage {
    Resize {
        cols: u32,
        rows: u32,
        #[serde(default)]
        cell_width_px: u32,
        #[serde(default)]
        cell_height_px: u32,
        #[serde(default)]
        owner: Option<String>,
    },
    Theme {
        #[serde(default)]
        foreground: Option<String>,
        #[serde(default)]
        background: Option<String>,
        #[serde(default)]
        cursor: Option<String>,
        #[serde(default)]
        palette: Option<Vec<String>>,
        #[serde(default)]
        scheme: Option<String>,
    },
    ChildExited {
        exit_code: i32,
    },
    #[serde(other)]
    Unknown,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Chunk {
    Output(Vec<u8>),
    Message(StreamMessage),
}

#[derive(Debug)]
pub struct StreamSplitter {
    nonce: String,
    pending: Vec<u8>,
}

impl StreamSplitter {
    pub fn new(nonce: u64) -> Self {
        Self { nonce: nonce.to_string(), pending: Vec::new() }
    }

    pub fn feed(&mut self, input: &[u8]) -> Vec<Chunk> {
        let mut buf = std::mem::take(&mut self.pending);
        buf.extend_from_slice(input);
        let mut out = Vec::new();
        let mut text_start = 0;
        let mut i = 0;
        while i < buf.len() {
            if buf[i] != 0x1b {
                i += 1;
                continue;
            }
            let rest = &buf[i..];
            let n = rest.len().min(PREFIX.len());
            if rest[..n] != PREFIX[..n] {
                i += 1;
                continue;
            }
            if rest.len() < PREFIX.len() {
                push_output(&mut out, &buf[text_start..i]);
                self.pending = rest.to_vec();
                return out;
            }
            let body_start = PREFIX.len();
            match rest[body_start..].windows(2).position(|w| w == ST) {
                Some(end) => {
                    let total = body_start + end + 2;
                    match self.parse(&rest[body_start..body_start + end]) {
                        Some(msg) => {
                            push_output(&mut out, &buf[text_start..i]);
                            out.push(Chunk::Message(msg));
                            i += total;
                            text_start = i;
                        }
                        None => i += total,
                    }
                }
                None if rest.len() > MAX_MESSAGE => i += 1,
                None => {
                    push_output(&mut out, &buf[text_start..i]);
                    self.pending = rest.to_vec();
                    return out;
                }
            }
        }
        push_output(&mut out, &buf[text_start..]);
        out
    }

    fn parse(&self, body: &[u8]) -> Option<StreamMessage> {
        let s = std::str::from_utf8(body).ok()?;
        let (nonce, json) = s.split_once(';')?;
        (nonce == self.nonce).then(|| serde_json::from_str(json).unwrap_or(StreamMessage::Unknown))
    }
}

fn push_output(out: &mut Vec<Chunk>, bytes: &[u8]) {
    if bytes.is_empty() {
        return;
    }
    match out.last_mut() {
        Some(Chunk::Output(prev)) => prev.extend_from_slice(bytes),
        _ => out.push(Chunk::Output(bytes.to_vec())),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(nonce: u64, json: &str) -> Vec<u8> {
        format!("\x1b_rex1;{nonce};{json}\x1b\\").into_bytes()
    }

    #[test]
    fn genuine_messages_are_split_and_foreign_ones_stay() {
        let mut s = StreamSplitter::new(42);
        let mut input = b"hi ".to_vec();
        input.extend(msg(42, r#"{"type":"resize","cols":80,"rows":24,"owner":"client:x"}"#));
        input.extend(b"there");
        input.extend(msg(7, r#"{"type":"child_exited","exit_code":1}"#));
        let out = s.feed(&input);
        assert_eq!(out[0], Chunk::Output(b"hi ".to_vec()));
        assert!(matches!(&out[1], Chunk::Message(StreamMessage::Resize { cols: 80, rows: 24, owner: Some(o), .. }) if o == "client:x"));
        assert!(matches!(&out[2], Chunk::Output(b) if b.starts_with(b"there\x1b_rex1;7;")));
        assert_eq!(out.len(), 3);
    }

    #[test]
    fn any_split_point_yields_the_same_stream() {
        let mut full = b"ab".to_vec();
        full.extend(msg(9, r##"{"type":"theme","foreground":"#ffffff","palette":["0=000000"]}"##));
        full.extend(b"cd\x1b[0m\x1b_other\x1b\\");
        for cut in 0..=full.len() {
            let mut s = StreamSplitter::new(9);
            let mut out = s.feed(&full[..cut]);
            out.extend(s.feed(&full[cut..]));
            let text: Vec<u8> = out.iter().filter_map(|c| if let Chunk::Output(b) = c { Some(b.clone()) } else { None }).flatten().collect();
            assert_eq!(text, b"abcd\x1b[0m\x1b_other\x1b\\", "cut {cut}");
            let themes = out.iter().filter(|c| matches!(c, Chunk::Message(StreamMessage::Theme { .. }))).count();
            assert_eq!(themes, 1, "cut {cut}");
        }
    }

    #[test]
    fn unknown_type_decodes_to_unknown() {
        let mut s = StreamSplitter::new(1);
        assert_eq!(s.feed(&msg(1, r#"{"type":"later","x":1}"#)), vec![Chunk::Message(StreamMessage::Unknown)]);
    }
}
