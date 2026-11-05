//! Each host's last live session tree on disk, so Home has something to show at launch
//! before the connection is back. Terminal screens are not kept.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::model::SessionState;

/// Bumped when the shape changes; a file of another version is ignored.
const VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HostCache {
    pub version: u32,
    /// Guards against a file name collision: a file for another host is ignored.
    pub host_id: String,
    /// Unix ms when this state was last known live.
    pub as_of_ms: i64,
    pub sessions: Vec<SessionState>,
}

impl HostCache {
    pub fn new(host_id: &str, as_of_ms: i64, sessions: Vec<SessionState>) -> Self {
        Self { version: VERSION, host_id: host_id.to_string(), as_of_ms, sessions }
    }
}

/// `<data dir>/cache/<hash of host id>.json`: the name is hex, so no id can leave the folder.
pub fn path(data_dir: &Path, host_id: &str) -> PathBuf {
    data_dir.join("cache").join(format!("{:016x}.json", fnv1a(host_id.as_bytes())))
}

/// FNV-1a, 64 bit: stable across Rust versions, unlike `DefaultHasher`. Not a security
/// boundary; [`HostCache::host_id`] catches the unlikely collision.
fn fnv1a(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |h, b| (h ^ u64::from(*b)).wrapping_mul(0x0100_0000_01b3))
}

/// The cache for `host_id`, or `None` when missing, unreadable, of another version or another host.
pub fn load(file: &Path, host_id: &str) -> Option<HostCache> {
    let bytes = std::fs::read(file).ok()?;
    match serde_json::from_slice::<HostCache>(&bytes) {
        Ok(c) if c.version == VERSION && c.host_id == host_id => Some(c),
        Ok(_) => {
            tracing::info!("{}: cache of another version or host, ignored", file.display());
            None
        }
        Err(e) => {
            tracing::warn!("{}: cache unreadable, ignored: {e}", file.display());
            None
        }
    }
}

/// Write atomically, so a crash never leaves a half-written file.
pub fn save(file: &Path, cache: &HostCache) -> std::io::Result<()> {
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = file.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(cache).map_err(std::io::Error::other)?)?;
    std::fs::rename(tmp, file)
}

pub fn now_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{BlockState, WindowState};
    use crate::store::new_id;
    use rexc_proto::model::{ProgramState, ProgramStatus};

    fn temp_dir() -> PathBuf {
        std::env::temp_dir().join(new_id("rexc-cache").replace(':', "-"))
    }

    fn sessions() -> Vec<SessionState> {
        let block = BlockState {
            block_id: "block:1".into(),
            title: Some("π".into()),
            rect: [0.0, 0.5, 1.0, 0.5],
            programs: vec![ProgramStatus { state: ProgramState::Blocked, app: Some("claude".into()), msg: Some("Allow?".into()), ..Default::default() }],
            ..Default::default()
        };
        let window = WindowState { window_id: "window:1".into(), label: "tab".into(), blocks: vec![block], ..Default::default() };
        vec![SessionState { session_id: "session:1".into(), label: "work".into(), revision: 7, loaded: true, windows: vec![window], ..Default::default() }]
    }

    #[test]
    fn round_trips_through_disk_without_the_loaded_flag() {
        let dir = temp_dir();
        let file = path(&dir, "host:1");
        let cache = HostCache::new("host:1", 1_700_000_000_000, sessions());
        save(&file, &cache).unwrap();
        let back = load(&file, "host:1").unwrap();
        assert!(!back.sessions[0].loaded, "a cached session is not a loaded view");
        let mut expected = cache;
        expected.sessions[0].loaded = false;
        assert_eq!(back, expected);
        assert!(!file.with_extension("json.tmp").exists());
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn corrupt_old_or_foreign_files_are_ignored() {
        let dir = temp_dir();
        let file = path(&dir, "host:1");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, b"{not json").unwrap();
        assert_eq!(load(&file, "host:1"), None);
        let mut old = HostCache::new("host:1", 1, sessions());
        old.version = 0;
        save(&file, &old).unwrap();
        assert_eq!(load(&file, "host:1"), None);
        save(&file, &HostCache::new("host:2", 1, sessions())).unwrap();
        assert_eq!(load(&file, "host:1"), None);
        assert_eq!(load(&dir.join("cache/missing.json"), "host:1"), None);
        std::fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn file_names_are_hashed_inside_the_cache_folder() {
        let dir = Path::new("/data");
        let p = path(dir, "../../etc/passwd");
        assert_eq!(p.parent(), Some(dir.join("cache").as_path()));
        let name = p.file_name().unwrap().to_str().unwrap();
        assert_eq!(name.len(), "0123456789abcdef.json".len());
        assert!(name.trim_end_matches(".json").chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(path(dir, "host:1"), path(dir, "host:1"));
        assert_ne!(path(dir, "host:1"), path(dir, "host:2"));
    }
}
