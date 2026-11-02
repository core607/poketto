# Sandcrab L3 storage evidence

These local development receipts cover ordinary storage, disk session directories
and periodic accounting. They do not establish canonical density, installation
readiness or final CI acceptance. [The index](index.json) hashes the committed
receipt bytes; [local gates](local/local-gates.json) retain each build attempt,
including failures. Original logs and source snapshots remain with the operator.

The current candidate is `62d18e54f4cf84175e1569f780492e87a98ca597731c04de21f1d43fbbfce00e`
(4,244,384 bytes), built from development source over `c66683df`.
[Iteration 5](local/rust-iteration5.json) passes formatting, both Clippy modes and
141 locked Rust tests in the prepared pinned image. This bounded iteration uses
a separate build directory and is not `executorRustCheck` or `buildExecutorRust`.
The last local invocation of those tasks failed after the Docker data disk filled:
a filesystem test returned `ReadOnlyFilesystem`, followed by an RPC EOF.
Authoritative CI on a fresh Linux runner remains required.

| Receipt | Candidate | Result |
|---|---|---|
| [Plain storage](local/plain4.json) | `d462db7c` | 12 checks and cleanup pass |
| [Native isolation](local/native1.json) | `d462db7c` | 45 checks and cleanup pass |
| [Contexts](local/contexts2.json) | `62d18e54` | 10 checks and cleanup pass |
| [Files](local/files1.json) | `62d18e54` | 25 checks and cleanup pass |
| [Published SDK clients](local/sdk2.json) | `62d18e54` | 21 checks and cleanup pass |

SDK conformance runs real Python and Kotlin clients, version 1.1.0, against the
native sandbox. No model calls are included. These results do not substitute for
the full scripted Inspect preflight or model usability evaluation.

Plain-storage observations use disposable ext4 images and force ordinary-copy
fallback. They include volume and aggregate pool overage, read/delete recovery,
shared disk sessions and ownership-checked restart cleanup. The pool observation
exceeds its 8 MiB limit before writers freeze; periodic accounting is a soft bound.
Representative accounting cost at 50 sessions of 20,000 files remains unmeasured.

[Contexts attempt 1](local/contexts1.json) exposed the old snapshot staging path
after session workspaces moved to disk. The corrected staging path passes in
attempt 2. [SDK attempt 1](local/sdk1.json) passes both clients but fails a fault
fixture still watching the old staging path; attempt 2 corrects the fixture.
Both failed attempts retain successful cleanup. Earlier plain-storage and Rust
attempts keep their original identities; they do not verify the final source.
