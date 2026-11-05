//! Delivers engine events to the UI, folding bursts of the same event into one.

use std::collections::HashSet;
use std::sync::Arc;
use std::time::Duration;

use parking_lot::Mutex;

use crate::{Event, Listener};

const COALESCE: Duration = Duration::from_millis(24);

#[derive(Clone)]
pub struct Notifier {
    listener: Arc<dyn Listener>,
    pending: Arc<Mutex<HashSet<Coalesced>>>,
    handle: tokio::runtime::Handle,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
enum Coalesced {
    Hosts,
    Host(String),
    Terminal(u64),
    TerminalState(u64),
}

impl Notifier {
    pub fn new(listener: Arc<dyn Listener>, handle: tokio::runtime::Handle) -> Self {
        Self { listener, pending: Arc::default(), handle }
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

    pub fn terminal(&self, id: u64) {
        self.coalesce(Coalesced::Terminal(id));
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
                    Coalesced::Terminal(id) => Event::Terminal { terminal_id: id },
                    Coalesced::TerminalState(id) => Event::TerminalState { terminal_id: id },
                };
                this.listener.on_event(ev);
            }
        });
    }
}
