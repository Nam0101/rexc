//! `rexc`: poke a Rex server with the same crates the app uses.
//!
//!   rexc ls [ADDR]                      sessions, windows, blocks
//!   rexc watch [ADDR]                   print events as they arrive
//!   rexc stream ADDR SESSION BLOCK      open a data stream, report snapshot and output
//!   rexc screen ADDR SESSION BLOCK      run the engine, open the terminal, print its screen
//!   rexc take ADDR SESSION BLOCK        same, taking ownership (the pane reflows to 100x30)
//!   rexc preview ADDR SESSION BLOCK     the pane's last lines without opening it (the inbox card)
//!   rexc reply ADDR SESSION BLOCK TEXT  type TEXT and Enter into the pane on a connection made for it
//!
//! ADDR defaults to $REX_SERVER, then the local Rex socket.

use std::time::Duration;

use rexc_client::{Control, ControlEvent, DataEvent, DataStream, Endpoint};
use rexc_proto::control::ClientInfo;
use rexc_proto::model::{BootstrapResult, ServerEvent};
use rexc_proto::{TERMINAL_CREATOR, TERMINAL_DATA_REF, TERMINAL_SUBPROTOCOL};
use serde_json::json;

fn default_addr() -> String {
    if let Ok(s) = std::env::var("REX_SERVER") {
        return s;
    }
    let home = std::env::var("HOME").unwrap_or_default();
    format!("{home}/Library/Application Support/rex/server.sock")
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::from_default_env()).with_writer(std::io::stderr).init();
    let args: Vec<String> = std::env::args().skip(1).collect();
    let cmd = args.first().map(String::as_str).unwrap_or("ls");
    let addr = args.get(1).cloned().unwrap_or_else(default_addr);
    let result = match cmd {
        "ls" => ls(&addr).await,
        "watch" => watch(&addr).await,
        "stream" if args.len() >= 4 => stream(&addr, &args[2], &args[3]).await,
        "screen" | "take" if args.len() >= 4 => {
            let (addr, s, b, take) = (addr.clone(), args[2].clone(), args[3].clone(), cmd == "take");
            tokio::task::spawn_blocking(move || screen(&addr, &s, &b, take).map_err(|e| e.to_string())).await.unwrap_or_else(|e| Err(e.to_string())).map_err(Into::into)
        }
        "preview" if args.len() >= 4 => {
            let (addr, s, b) = (addr.clone(), args[2].clone(), args[3].clone());
            tokio::task::spawn_blocking(move || with_engine(&addr, true, |e, h| Ok(println!("{}", e.block_preview(h, &s, &b, 4)?))).map_err(|e| e.to_string())).await.unwrap_or_else(|e| Err(e.to_string())).map_err(Into::into)
        }
        "reply" if args.len() >= 5 => {
            let (addr, s, b, t) = (addr.clone(), args[2].clone(), args[3].clone(), args[4].clone());
            // No connect first: the engine must bring the host up for this one call, as from a notification.
            tokio::task::spawn_blocking(move || with_engine(&addr, false, |e, h| Ok(e.quick_reply(h, &s, &b, &t)?)).map_err(|e| e.to_string())).await.unwrap_or_else(|e| Err(e.to_string())).map_err(Into::into)
        }
        _ => {
            eprintln!("usage: rexc ls|watch [ADDR] | rexc stream|screen|take|preview ADDR SESSION BLOCK | rexc reply ADDR SESSION BLOCK TEXT");
            std::process::exit(2);
        }
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

async fn connect(addr: &str) -> Result<(Endpoint, Control, tokio::sync::mpsc::UnboundedReceiver<ControlEvent>, BootstrapResult), Box<dyn std::error::Error>> {
    let endpoint = Endpoint::parse(addr)?;
    let info = ClientInfo::app("rexc-cli", env!("CARGO_PKG_VERSION"), None);
    let (control, events) = Control::connect(&endpoint, &info, None).await?;
    let w = control.welcome();
    eprintln!("connected to {} (api {}, client {})", endpoint.display(), w.api_revision, control.client_id().unwrap_or("?"));
    let (_, boot): (u64, BootstrapResult) = control.call_as("session.bootstrap", json!({"event_scope": "all", "attach_session_ids": []})).await?;
    Ok((endpoint, control, events, boot))
}

async fn ls(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    let (_, control, _events, boot) = connect(addr).await?;
    println!("server pid {} host {} sessions {}", boot.server_status.pid, boot.server_status.host_id.as_deref().unwrap_or("-"), boot.attach_all.sessions.len());
    for s in &boot.attach_all.sessions {
        let (_, att): (u64, serde_json::Value) = control.call_as("session.attach", json!({"session_id": s.session_id})).await?;
        let view: rexc_proto::model::SessionView = serde_json::from_value(att["view"].clone())?;
        println!("{}  {}  rev {}", s.session_id, s.label, view.revision);
        for w in &view.windows {
            let active = if view.active_window_id == w.window_id { "*" } else { " " };
            println!("  {active} {}  {}", w.window_id.as_deref().unwrap_or("-"), w.label);
            for b in w.tiled_blocks() {
                let id = b.block_id.as_deref().unwrap_or("-");
                let focused = if w.focused_block_id.as_deref() == Some(id) { ">" } else { " " };
                let title = control.call_block(&s.session_id, id, "title", json!({})).await.ok().and_then(|v| v["title"].as_str().map(str::to_owned)).unwrap_or_default();
                let status = control.call_block(&s.session_id, id, "program_status", json!({})).await.ok();
                let states: Vec<String> = status
                    .and_then(|v| v["records"].as_array().cloned())
                    .unwrap_or_default()
                    .iter()
                    .map(|r| format!("{}:{}", r["effective_app"].as_str().or(r["app"].as_str()).unwrap_or("?"), r["state"].as_str().unwrap_or("?")))
                    .collect();
                println!("     {focused} {id}  {:<10} owner {:<40} {title}  {}", b.flavor.rsplit('.').next().unwrap_or(""), b.owner.as_deref().unwrap_or("-"), states.join(" "));
            }
        }
    }
    Ok(())
}

async fn watch(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    let (_, control, mut events, boot) = connect(addr).await?;
    for s in &boot.attach_all.sessions {
        control.call("session.attach", json!({"session_id": s.session_id})).await?;
    }
    println!("watching {} sessions; ctrl-c to stop", boot.attach_all.sessions.len());
    while let Some(ev) = events.recv().await {
        match ev {
            ControlEvent::Event { seq, name, payload } => println!("#{seq} {:?}", ServerEvent::decode(&name, &payload)),
            ControlEvent::Closed { code, reason } => {
                println!("closed {code} {reason}");
                break;
            }
        }
    }
    Ok(())
}

async fn stream(addr: &str, session_id: &str, block_id: &str) -> Result<(), Box<dyn std::error::Error>> {
    let (endpoint, control, _events, _boot) = connect(addr).await?;
    control.call("session.attach", json!({"session_id": session_id})).await?;
    let ticket = control
        .call("data.connect", json!({"session_id": session_id, "block_id": block_id, "data_ref_id": TERMINAL_DATA_REF, "subprotocol": TERMINAL_SUBPROTOCOL}))
        .await?;
    let ticket = ticket["ticket"].as_str().ok_or("no ticket")?.to_string();
    let (_stream, mut rx) = DataStream::open(endpoint, ticket);
    let deadline = tokio::time::Instant::now() + Duration::from_millis(4000);
    let mut output = 0usize;
    loop {
        let ev = tokio::select! {
            ev = rx.recv() => match ev { Some(ev) => ev, None => break },
            _ = tokio::time::sleep_until(deadline) => break,
        };
        match &ev {
            DataEvent::Snapshot(s) => eprintln!("snapshot {} bytes", s.len()),
            DataEvent::Output(o) => {
                output += o.len();
                print!("{}", String::from_utf8_lossy(o));
            }
            DataEvent::Resize { cols, rows, owner, .. } => eprintln!("resize {cols}x{rows} owner {owner:?}"),
            DataEvent::Theme { background, scheme, .. } => eprintln!("theme bg {background:?} scheme {scheme:?}"),
            other => eprintln!("{other:?}"),
        }
    }
    eprintln!("{output} output bytes; creator {TERMINAL_CREATOR}");
    Ok(())
}

/// Drive the whole engine the way the app does: add a host, connect, open a
/// terminal, wait for frames, print the screen.
fn screen(addr: &str, session_id: &str, block_id: &str, take: bool) -> Result<(), Box<dyn std::error::Error>> {
    use rexc_core::{Engine, EngineConfig, Event, Listener};
    struct Tx(std::sync::mpsc::Sender<Event>);
    impl Listener for Tx {
        fn on_event(&self, event: Event) {
            let _ = self.0.send(event);
        }
    }
    let (tx, rx) = std::sync::mpsc::channel();
    let dir = std::env::temp_dir().join(format!("rexc-cli-{}", std::process::id()));
    let engine = Engine::new(EngineConfig { data_dir: dir.clone(), app_name: "rexc-cli".into(), app_version: env!("CARGO_PKG_VERSION").into(), device_label: Some("cli".into()) }, std::sync::Arc::new(Tx(tx)));
    let host_id = engine.add_host("cli", addr)?;
    engine.connect(&host_id)?;
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    let wait = |rx: &std::sync::mpsc::Receiver<Event>, mut done: Box<dyn FnMut(&Event) -> bool>| -> bool {
        while let Ok(ev) = rx.recv_timeout(deadline.saturating_duration_since(std::time::Instant::now())) {
            if done(&ev) {
                return true;
            }
        }
        false
    };
    let e = engine.clone();
    let hid = host_id.clone();
    if !wait(&rx, Box::new(move |ev| matches!(ev, Event::Host { .. }) && e.host_state(&hid).map(|h| h.status == rexc_core::HostStatus::Connected && h.sessions.iter().any(|s| s.loaded)).unwrap_or(false))) {
        return Err(format!("host did not connect: {:?}", engine.host_state(&host_id)?.error).into());
    }
    let state = engine.host_state(&host_id)?;
    eprintln!("connected as {:?}; {} sessions, inbox {}", state.client_id, state.sessions.len(), engine.inbox().len());
    for s in &state.sessions {
        for w in &s.windows {
            eprintln!("  {} / {} : {} blocks, busy {}", s.label, w.display_name(), w.blocks.len(), w.busy());
        }
    }
    let tid = engine.open_terminal(&host_id, session_id, block_id, take)?;
    engine.terminal_set_viewport(tid, 100, 30, 9, 18)?;
    let e = engine.clone();
    wait(&rx, Box::new(move |ev| matches!(ev, Event::Terminal { .. }) && e.terminal_info(tid).map(|i| i.has_snapshot).unwrap_or(false)));
    // Let a little output land, then print.
    std::thread::sleep(Duration::from_millis(400));
    let info = engine.terminal_info(tid)?;
    let frame = engine.terminal_frame(tid)?;
    eprintln!("stream {:?} owner {:?} mine {} mode {:?} size {}x{} cursor {:?}", info.stream, info.owner, info.owned_by_me, info.mode, frame.cols, frame.rows, frame.cursor.map(|c| (c.col, c.row)));
    for (i, row) in frame.lines.iter().enumerate() {
        println!("{i:3}|{}", row.text().trim_end());
    }
    engine.close_terminal(tid);
    engine.remove_host(&host_id)?;
    let _ = std::fs::remove_dir_all(dir);
    Ok(())
}

/// An engine with one host, optionally connected and loaded, for one call.
fn with_engine(addr: &str, connect: bool, f: impl FnOnce(&rexc_core::Engine, &str) -> Result<(), Box<dyn std::error::Error>>) -> Result<(), Box<dyn std::error::Error>> {
    use rexc_core::{Engine, EngineConfig, Event, HostStatus, Listener};
    struct Tx(std::sync::mpsc::Sender<Event>);
    impl Listener for Tx {
        fn on_event(&self, event: Event) {
            let _ = self.0.send(event);
        }
    }
    let (tx, rx) = std::sync::mpsc::channel();
    let dir = std::env::temp_dir().join(format!("rexc-cli-{}", std::process::id()));
    let engine = Engine::new(EngineConfig { data_dir: dir.clone(), app_name: "rexc-cli".into(), app_version: env!("CARGO_PKG_VERSION").into(), device_label: Some("cli".into()) }, std::sync::Arc::new(Tx(tx)));
    let host_id = engine.add_host("cli", addr)?;
    if connect {
        engine.connect(&host_id)?;
        let deadline = std::time::Instant::now() + Duration::from_secs(10);
        loop {
            let h = engine.host_state(&host_id)?;
            if h.status == HostStatus::Connected && h.sessions.iter().any(|s| s.loaded) {
                break;
            }
            if rx.recv_timeout(deadline.saturating_duration_since(std::time::Instant::now())).is_err() {
                return Err(format!("host did not connect: {:?}", h.error).into());
            }
        }
    }
    let started = std::time::Instant::now();
    let result = f(&engine, &host_id);
    eprintln!("done in {:?}; host now {:?}", started.elapsed(), engine.host_state(&host_id)?.status);
    engine.remove_host(&host_id)?;
    let _ = std::fs::remove_dir_all(dir);
    result
}
