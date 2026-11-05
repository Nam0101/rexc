//! Finds where the Ghostty snapshot at the head of a terminal stream ends.
//!
//! `"GHOSTSNP"` + u16 version, then records of `tag:u16 len:u32 crc:u32 payload`
//! up to and including the FINISH record (tag 6). libghostty-vt verifies the
//! checksums when it decodes; this only needs the boundary so the rest of the
//! stream can go through the VT parser.

const MAGIC: &[u8; 8] = b"GHOSTSNP";
const ENVELOPE: usize = 10;
const HEADER: usize = 10;
const TAG_FINISH: u16 = 6;
/// A phone will never want more than this; refuse instead of buffering forever.
const MAX_SNAPSHOT: usize = 256 << 20;

#[derive(Debug, PartialEq, Eq)]
pub enum Framed {
    Pending,
    Complete { snapshot: Vec<u8>, rest: Vec<u8> },
    /// The stream doesn't begin with a snapshot; treat the bytes as output.
    NotSnapshot(Vec<u8>),
    Invalid(String),
}

#[derive(Debug, Default)]
pub struct SnapshotFramer {
    buf: Vec<u8>,
    cursor: usize,
}

impl SnapshotFramer {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn buffered(&self) -> usize {
        self.buf.len()
    }

    pub fn push(&mut self, bytes: &[u8]) -> Framed {
        self.buf.extend_from_slice(bytes);
        if self.cursor == 0 {
            let n = self.buf.len().min(MAGIC.len());
            if self.buf[..n] != MAGIC[..n] {
                return Framed::NotSnapshot(std::mem::take(&mut self.buf));
            }
            if self.buf.len() < ENVELOPE {
                return Framed::Pending;
            }
            self.cursor = ENVELOPE;
        }
        loop {
            if self.buf.len() < self.cursor + HEADER {
                return self.pending();
            }
            let h = &self.buf[self.cursor..self.cursor + HEADER];
            let tag = u16::from_le_bytes([h[0], h[1]]);
            let len = u32::from_le_bytes([h[2], h[3], h[4], h[5]]) as usize;
            let end = self.cursor + HEADER + len;
            if end > MAX_SNAPSHOT {
                return Framed::Invalid(format!("snapshot record ends past {MAX_SNAPSHOT} bytes"));
            }
            if self.buf.len() < end {
                return self.pending();
            }
            self.cursor = end;
            if tag == TAG_FINISH {
                let rest = self.buf.split_off(end);
                self.cursor = 0;
                return Framed::Complete { snapshot: std::mem::take(&mut self.buf), rest };
            }
        }
    }

    fn pending(&self) -> Framed {
        if self.buf.len() > MAX_SNAPSHOT { Framed::Invalid("snapshot too large".into()) } else { Framed::Pending }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(tag: u16, payload: &[u8]) -> Vec<u8> {
        let mut v = tag.to_le_bytes().to_vec();
        v.extend((payload.len() as u32).to_le_bytes());
        v.extend([0u8; 4]);
        v.extend(payload);
        v
    }

    fn sample() -> Vec<u8> {
        let mut snap = b"GHOSTSNP\x01\x00".to_vec();
        snap.extend(record(1, b"terminal"));
        snap.extend(record(2, b""));
        snap.extend(record(5, b""));
        snap.extend(record(6, b""));
        snap
    }

    #[test]
    fn every_cut_point_frames_the_same() {
        let snap = sample();
        let mut stream = snap.clone();
        stream.extend(b"after");
        for cut in 1..stream.len() {
            let mut f = SnapshotFramer::new();
            let done = match f.push(&stream[..cut]) {
                Framed::Complete { snapshot, mut rest } => {
                    rest.extend_from_slice(&stream[cut..]);
                    Framed::Complete { snapshot, rest }
                }
                Framed::Pending => f.push(&stream[cut..]),
                other => panic!("cut {cut}: {other:?}"),
            };
            match done {
                Framed::Complete { snapshot, rest } => {
                    assert_eq!(snapshot, snap, "cut {cut}");
                    assert_eq!(rest, b"after", "cut {cut}");
                }
                other => panic!("cut {cut}: {other:?}"),
            }
        }
    }

    #[test]
    fn plain_output_passes_through() {
        let mut f = SnapshotFramer::new();
        assert_eq!(f.push(b"GHOST"), Framed::Pending);
        assert_eq!(f.push(b"X"), Framed::NotSnapshot(b"GHOSTX".to_vec()));
    }
}
