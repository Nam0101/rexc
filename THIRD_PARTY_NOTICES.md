# Third-party notices

## Sheprd

Parts of rexc are adapted from [Sheprd](https://github.com/sheprd-labs/sheprd),
Copyright 2026 Fabien Penso, licensed under the Apache License, Version 2.0
(full text in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)). Adapted
from revision `88a7ecf`; each adapted piece carries an `Adapted from sheprd`
comment naming its source file. The code was rewritten for rexc's own types.

| rexc | Sheprd source |
| --- | --- |
| `crates/rexc-term/src/links.rs`: URL scanning, word and path boundaries | `crates/sheprd-gpui/src/terminal/links.rs`, `links/path.rs`, `selection.rs` |
| `crates/rexc-term/src/themes.rs`: Ghostty theme parsing | `crates/sheprd-gpui/src/config/theme.rs` |
| `crates/rexc-core/src/host.rs`: alert text sanitising | `crates/sheprd-client/src/text.rs` |
| `crates/rexc-core/src/terminal.rs`: bell throttle | `crates/sheprd-gpui/src/bell.rs` |

## iTerm2-Color-Schemes

The terminal colour themes in `crates/rexc-term/themes/` are unmodified copies
from [iTerm2-Color-Schemes](https://github.com/mbadolato/iTerm2-Color-Schemes),
Copyright (c) 2011 to Present Mark Badolato, MIT License; each theme's
copyright belongs to its author. [`crates/rexc-term/themes/NOTICE.md`](crates/rexc-term/themes/NOTICE.md)
has the revision and the full license text.
