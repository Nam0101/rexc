//! What the app remembers between runs: hosts and where you were in each.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct LastPosition {
    pub session_id: Option<String>,
    pub window_id: Option<String>,
    pub block_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HostRecord {
    pub id: String,
    pub name: String,
    pub address: String,
    #[serde(default)]
    pub last: LastPosition,
    /// Keep the connection while the app is in the background, for alerts.
    #[serde(default)]
    pub background_alerts: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Store {
    #[serde(default)]
    pub hosts: Vec<HostRecord>,
}

impl Store {
    pub fn path(dir: &Path) -> PathBuf {
        dir.join("hosts.json")
    }

    pub fn load(dir: &Path) -> Self {
        match std::fs::read(Self::path(dir)) {
            Ok(bytes) => serde_json::from_slice(&bytes).unwrap_or_else(|e| {
                tracing::warn!("hosts.json unreadable, starting empty: {e}");
                Self::default()
            }),
            Err(_) => Self::default(),
        }
    }

    /// Write atomically, so a crash never leaves a half-written file.
    pub fn save(&self, dir: &Path) -> std::io::Result<()> {
        std::fs::create_dir_all(dir)?;
        let path = Self::path(dir);
        let tmp = path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(self).map_err(std::io::Error::other)?)?;
        std::fs::rename(tmp, path)
    }

    pub fn host(&self, id: &str) -> Option<&HostRecord> {
        self.hosts.iter().find(|h| h.id == id)
    }

    pub fn host_mut(&mut self, id: &str) -> Option<&mut HostRecord> {
        self.hosts.iter_mut().find(|h| h.id == id)
    }
}

/// A short random id that is safe in file names and JSON.
pub fn new_id(prefix: &str) -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let salt = std::ptr::addr_of!(nanos) as usize as u128;
    format!("{prefix}:{:x}", nanos ^ (salt.rotate_left(17)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_through_disk() {
        let dir = std::env::temp_dir().join(format!("rexc-store-{}", new_id("t")).replace(':', "-"));
        let mut s = Store::default();
        s.hosts.push(HostRecord { id: new_id("host"), name: "mac".into(), address: "mac.tail.ts.net".into(), last: LastPosition::default(), background_alerts: true });
        s.save(&dir).unwrap();
        let back = Store::load(&dir);
        assert_eq!(back, s);
        assert!(!Store::path(&dir).with_extension("json.tmp").exists());
        std::fs::remove_dir_all(dir).ok();
    }
}
