# rexc

An Android client for the [Rex](https://rex.app) terminal multiplexer: your desktop sessions, tabs and panes on the phone, with alerts when a program (Claude Code, Codex, a build) needs you.

- **Rust core** (`crates/`): Rex protocol, tokio + rustls client with MagicDNS fallback, terminal emulation on libghostty-vt, an engine that owns reconnects, ownership (Fit/Mirror) and alerts, exposed to Kotlin through UniFFI.
- **Compose UI** (`android/`): Hilt, Navigation 3, MVI; a Canvas renderer for the grid, a key dock, an inbox with screen previews and quick replies, background alerts through a foreground service, inline reply from the notification.
- **CLI** (`crates/rexc-cli`): pokes a server with the same crates (`ls`, `watch`, `screen`, `preview`, `reply`).

## Build

Rust for Android needs zig (libghostty) and the NDK: `scripts/build-android.sh` produces `librexc_ffi.so` and the Kotlin bindings, then `cd android && ./gradlew assembleDebug`. `scripts/m4.sh` runs the same on a remote build machine.

Quality gates: `cargo test --workspace`, `./gradlew spotlessCheck detekt testDebugUnitTest`.
