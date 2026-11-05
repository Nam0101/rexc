//! Delivers engine events to the UI, folding bursts of the same event into one.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};

use parking_lot::Mutex;

use crate::{Event, Listener};

const COALESCE: Duration = Duration::from_millis(24);
/// Screen updates go out at once when the terminal has been quiet, then at most this often:
/// one per display frame at 120 Hz, so echoes aren't held back and a flood can't outrun the drawing.
const TERMINAL_GAP: Duration = Duration::from_millis(8);

/// When a terminal last notified, and whether a closing notification is already scheduled.
#[derive(Default)]
struct Gate {
    last: Option<Instant>,
    scheduled: bool,
}

#[derive(Clone)]
pub struct Notifier {
    listener: Arc<dyn Listener>,
    pending: Arc<Mutex<HashSet<Coalesced>>>,
    gates: Arc<Mutex<HashMap<u64, Gate>>>,
    handle: tokio::runtime::Handle,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum Coalesced {
    Hosts,
    Host(String),
    TerminalState(u64),
}

impl Notifier {
    pub fn new(listener: Arc<dyn Listener>, handle: tokio::runtime::Handle) -> Self {
        Self { listener, pending: Arc::default(), gates: Arc::default(), handle }
    }

    /// Send now, uncoalesced (alerts, clipboard, URLs).
    pub fn emit(&self, event: Event) {
        self.listener.on_event(event);
    }

    pub fn hosts(&self) {
        self.coalesce(Coalesced::Hosts);
    }

    pub fn host(&self, host_id: &str) {
        self.coalesce(Coalesced::Host(host_id.to_string()));
    }

    /// A terminal's screen changed: notify now if it's been quiet, else once the gap has passed.
    pub fn terminal(&self, id: u64) {
        let wait = {
            let mut gates = self.gates.lock();
            let gate = gates.entry(id).or_default();
            if gate.scheduled {
                return;
            }
            let wait = gate.last.map_or(Duration::ZERO, |t| TERMINAL_GAP.saturating_sub(t.elapsed()));
            if wait.is_zero() {
                gate.last = Some(Instant::now());
            } else {
                gate.scheduled = true;
            }
            wait
        };
        if wait.is_zero() {
            self.listener.on_event(Event::Terminal { terminal_id: id });
            return;
        }
        let this = self.clone();
        self.handle.spawn(async move {
            tokio::time::sleep(wait).await;
            if let Some(gate) = this.gates.lock().get_mut(&id) {
                gate.scheduled = false;
                gate.last = Some(Instant::now());
            }
            this.listener.on_event(Event::Terminal { terminal_id: id });
        });
    }

    pub fn terminal_state(&self, id: u64) {
        self.coalesce(Coalesced::TerminalState(id));
    }

    fn coalesce(&self, key: Coalesced) {
        let first = {
            let mut p = self.pending.lock();
            let was_empty = p.is_empty();
            p.insert(key);
            was_empty
        };
        if !first {
            return;
        }
        let this = self.clone();
        self.handle.spawn(async move {
            tokio::time::sleep(COALESCE).await;
            let keys: Vec<Coalesced> = this.pending.lock().drain().collect();
            for k in keys {
                let ev = match k {
                    Coalesced::Hosts => Event::Hosts,
                    Coalesced::Host(id) => Event::Host { host_id: id },
                    Coalesced::TerminalState(id) => Event::TerminalState { terminal_id: id },
                };
                this.listener.on_event(ev);
            }
        });
    }
}
