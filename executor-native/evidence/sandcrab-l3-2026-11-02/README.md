# Sandcrab L3 storage evidence

These local development receipts cover ordinary storage, disk session directories
and periodic accounting. They do not establish canonical density, installation
readiness or final-head acceptance. [The index](index.json) hashes the committed
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

## First CI acceptance

[CI 41461303539](https://github.com/core607/poketto/actions/runs/41461303539)
passes at PR head `28eb726c`, including the authoritative Rust checks and consumer
lanes. Its tested merge ref `6127c52b` has the same tree, `da4327b2`.
The static CI binary is `57169e02cc2c0d431c7c354f9a670e9254ae1ac09f1d4774e7e5284c58860b36`,
4,240,288 bytes; the local candidates above have separate identities.

[Native](41461303539/13150695523/native.json),
[ordinary storage](41461303539/13150695523/plain-storage.json),
[quota volumes](41461303539/13150695523/volumes.json),
[files](41461303539/13150695523/files.json) and
[published SDK conformance](41461303539/13150695523/sdk.json) pass with cleanup.
[Inspect](41461303539/13150695523/summary.json) passes 66 scripted cases using
real sandboxes, `mockllm/model` and zero model tokens. Context, delegation, event,
TCP, host-call and resource receipts are retained alongside them.

The [two-agent XFS smoke](41461303652/13149990950/smoke.json) passes 99 judged
attempts, 100% OK and zero OOM; p95 is 38.602757 ms. Its two cold starts take
22.627549 and 22.753766 ms. The [footprint smoke](41461303652/13149990950/footprint-smoke.json)
records 3,284,992 bytes median daemon RSS. These are small entry-path checks,
not canonical capacity or marginal-cost comparisons. The dependency inventory in
that receipt still includes the removed `xfs_spaceman`; the current collector
corrects that inventory and separates optional quota tools.

[Installation replay](https://github.com/core607/poketto/actions/runs/41461303561)
completes all five environments with cleanup. It still requires 12–13 commands
and 2–3 manual edits, so L5/L10's release readiness targets remain unmet.
The clock excludes compilation, preparation of the controller and images, and
human editing; distribution containers share the runner kernel.

[Measurement component checks](local/measurement-tests1.json) pass 33 Linux tests,
including observation placement outside the command window. The plain-storage
variant and canonical 5 GB runs still require native measurement.
