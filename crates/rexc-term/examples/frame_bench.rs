//! Render-path numbers without a phone: feed a byte stream through the emulator in
//! read-sized chunks and time `Terminal::frame` after every `every`-th chunk.
//!
//!   cargo run --release -p rexc-term --example frame_bench -- FILE|tui [every] [cols] [rows]
//!
//! `every` = 1 builds a frame per data event (the pre-pacing worst case); 8 is roughly
//! one frame per display frame under a flood. `DELTA=1` builds with `frame_since` (rows the
//! caller already holds come back empty), as the app does.
use std::time::Instant;

use rexc_term::{Frame, Terminal};

const CHUNK: usize = 4096;

/// A full-screen, htop-like redraw: home, 256-colour cells, a reverse-video header.
fn tui(cols: usize, rows: usize, screens: usize) -> Vec<u8> {
    let mut out = Vec::new();
    for s in 0..screens {
        out.extend_from_slice(b"\x1b[H\x1b[7m");
        out.extend(format!("{:<w$}", format!(" PID USER CPU% MEM% frame {s}"), w = cols).bytes());
        out.extend_from_slice(b"\x1b[0m");
        for r in 1..rows {
            out.extend(format!("\x1b[{};1H", r + 1).bytes());
            for c in 0..cols / 6 {
                let col = 16 + ((r * 7 + c * 13 + s) % 216);
                out.extend(format!("\x1b[38;5;{col}m{:>5}\x1b[0m ", (r * 31 + c + s) % 99999).bytes());
            }
        }
    }
    out
}

/// Typing at a shell prompt over a screenful of earlier output, one character per event.
fn typing(cols: usize, rows: usize) -> Vec<u8> {
    let mut out = Vec::new();
    for r in 0..rows - 1 {
        out.extend(format!("\x1b[32muser@host\x1b[0m:\x1b[34m~/src/project{r}\x1b[0m$ ls\r\n").bytes());
    }
    out.extend_from_slice(b"$ ");
    for i in 0..2000 {
        out.push(b'a' + (i % 26) as u8);
        if i % (cols - 6) == cols - 7 {
            out.extend_from_slice(b"\r\n$ ");
        }
    }
    out
}

fn pct(v: &mut [u128], p: f64) -> u128 {
    v.sort_unstable();
    v[((v.len() - 1) as f64 * p) as usize]
}

/// Strings and vectors a frame hands to UniFFI, which lowers each one separately.
fn weight(f: &Frame) -> (usize, usize, usize) {
    let runs: usize = f.lines.iter().map(|l| l.runs.len()).sum();
    let bytes: usize = f.lines.iter().flat_map(|l| &l.runs).map(|r| r.text.len() + r.widths.len() + 24).sum();
    (f.lines.len(), runs, bytes)
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let src = a.get(1).expect("FILE|tui");
    let every: usize = a.get(2).and_then(|s| s.parse().ok()).unwrap_or(1);
    let cols: u16 = a.get(3).and_then(|s| s.parse().ok()).unwrap_or(52);
    let rows: u16 = a.get(4).and_then(|s| s.parse().ok()).unwrap_or(34);
    let data = if src == "tui" { tui(cols as usize, rows as usize, 400) } else if src == "typing" { typing(cols as usize, rows as usize) } else { std::fs::read(src).expect("read") };
    let mut t = Terminal::new(cols, rows);
    t.resize(cols, rows, 8, 16);
    let (mut write_us, mut frame_us, mut changed, mut dirty_rows) = (0u128, Vec::new(), 0usize, 0usize);
    let (mut runs, mut bytes) = (0usize, 0usize);
    let delta = std::env::var_os("DELTA").is_some();
    let mut rev = 0;
    let start = Instant::now();
    let chunk_len = if src == "typing" { 1 } else { CHUNK };
    for (i, chunk) in data.chunks(chunk_len).enumerate() {
        let w = Instant::now();
        t.write(chunk);
        write_us += w.elapsed().as_micros();
        if (i + 1) % every != 0 {
            continue;
        }
        let f = Instant::now();
        let fr = if delta { t.frame_since(rev) } else { t.frame() };
        rev = fr.rev;
        frame_us.push(f.elapsed().as_micros());
        changed += fr.changed as usize;
        dirty_rows += fr.lines.iter().filter(|l| l.dirty).count();
        let (_, r, b) = weight(&fr);
        runs += r;
        bytes += b;
    }
    let total = start.elapsed();
    let n = frame_us.len().max(1);
    let mean = frame_us.iter().sum::<u128>() / n as u128;
    println!(
        "{src} {}KB every={every} delta={delta} grid={cols}x{rows}: write {}us total, {n} frames ({changed} changed), frame mean {mean}us p50 {}us p99 {}us, dirty rows/frame {:.1}, runs/frame {}, payload {}B/frame, wall {:?}",
        data.len() / 1024,
        write_us,
        pct(&mut frame_us.clone(), 0.5),
        pct(&mut frame_us, 0.99),
        dirty_rows as f64 / n as f64,
        runs / n,
        bytes / n,
        total
    );
}
