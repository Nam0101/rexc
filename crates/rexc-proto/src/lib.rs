//! The Rex wire protocol (control API revision 10), as a client sees it.
//!
//! Everything here is plain data: JSON shapes for the control connection,
//! the binary handshake of a data connection, and the two framers that split
//! a terminal stream into a Ghostty snapshot, program output, and Rex's own
//! in-band messages. No I/O lives in this crate, so every piece is unit-tested
//! without a server.

pub mod apc;
pub mod control;
pub mod data;
pub mod key;
pub mod model;
pub mod snapshot;

/// The control API revision this crate was written for.
pub const API_REVISION: u32 = 10;
/// Oldest control API revision the client can live with.
pub const API_MIN_REVISION: u32 = 8;
pub const CONTROL_SUBPROTOCOL: &str = "rex.control.v1";
pub const TERMINAL_SUBPROTOCOL: &str = "rex.data.terminal.v1";
pub const TERMINAL_DATA_REF: &str = "terminal";
pub const TERMINAL_CREATOR: &str = "com.superlogical.terminal";
pub const SHELL_FLAVOR: &str = "com.superlogical.terminal.shell";

/// Name of a terminal block method, as `client.request` wants it.
pub fn terminal_method(short: &str) -> String {
    format!("{TERMINAL_CREATOR}.{short}")
}
