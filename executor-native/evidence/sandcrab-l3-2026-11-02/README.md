# Sandcrab L3 storage evidence

These receipts cover ordinary storage, disk session directories, periodic
accounting and native consumers. Canonical XFS and ordinary-storage results are
below. [The index](index.json) hashes the committed
receipt bytes; [local gates](local/local-gates.json) retain each build attempt,
including failures. Original logs and source snapshots remain with the operator.

The local development candidate is `62d18e54f4cf84175e1569f780492e87a98ca597731c04de21f1d43fbbfce00e`
(4,244,384 bytes), built from development source over `c66683df`.
[Iteration 5](local/rust-iteration5.json) passes formatting, both Clippy modes and
141 locked Rust tests in the prepared pinned image. This bounded iteration uses
a separate build directory and is not `executorRustCheck` or `buildExecutorRust`.
An earlier combined local invocation failed after the Docker data disk filled:
a filesystem test returned `ReadOnlyFilesystem`, followed by an RPC EOF.
The authoritative CI results are recorded below.

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
The canonical ordinary-storage measurement below retains its cost at 20,000 files
per workspace, including the failed 50-agent stage.

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
including observation placement outside the command window.

[Plain attempt 1](41546468286/13193146858/smoke.json) stops before the daemon
becomes ready: the fixture mounts ext4's `lost+found` inside the owned data root,
which correctly refuses a nonempty, unowned directory. Its
[journal](41546468286/13193146858/smoke.journal.txt) and
[diagnostic](41546468286/13193146858/smoke.diagnostic.txt) retain the failure;
host cleanup passes. The corrected fixture mounts ext4 at the storage parent
and creates an empty data directory. Its [local smoke](local/density-plain2-smoke.json)
passes 102 judged commands with no refusals or OOM and successful API/host cleanup.
This two-agent check does not measure canonical plain-storage capacity.

## Canonical XFS measurement

[Run 41546372313](https://github.com/core607/poketto/actions/runs/41546372313)
measures clean head `d7a5aa45` with binary `57169e02`. The
[density receipt](41546372313/13192971448/density.json) retains the unchanged W0
protocol: daemon and sandboxes share 5 GB, two CPUs and zero swap. All collectors
and API/host cleanup complete. All three 25- and 50-agent repetitions pass;
capacity remains 50.

| 50-agent repetition | Cold-start p50 | Judged p95 | OK rate | OOM kills |
|---|---:|---:|---:|---:|
| 0 | 32.598359 ms | 561.415632 ms | 1.0 | 0 |
| 1 | 33.857563 ms | 617.818678 ms | 1.0 | 0 |
| 2 | 33.041895 ms | 641.026968 ms | 1.0 | 0 |

Cold-start percentiles use nearest rank. This run misses the 30 ms target.
At the first 100-agent repetition, 1,284 `SESSION_NOT_READY` refusals reduce the
OK rate to 0.2968236583, with zero OOM. Its 168.181324 ms p95 includes fast refusals
and establishes no speed gain. The failed OK-rate criterion stops the search.

| Measurement | L2 canonical | L3 XFS |
|---|---:|---:|
| Static binary bytes | 4,174,752 | 4,240,288 |
| Median daemon idle RSS bytes | 3,215,360 | 3,280,896 |
| Pool delta bytes per idle session at count 50 | 1,562,869.76 | 1,528,053.76 |
| Pool delta bytes per finite run at count 50 | 1,122,631.68 | 1,099,612.16 |

The [footprint receipt](41546372313/13192971448/footprint.json) supplies the L3
values; [L2](../sandcrab-l2-2026-11-02/README.md) owns its baseline. Different
runner instances do not establish a causal speed or memory improvement.
The executable inventory is nine with optional project quotas, six without;
the prepared tools occupy 9,255,073 bytes. The [cache](41546372313/13192971448/cache.json)
and [startup trace](41546372313/13192971448/cold-start.json) retain their separate
measurement scopes and do not replace the missed canonical cold-start target.

## Canonical ordinary-storage measurement

[Run 41554499701](https://github.com/core607/poketto/actions/runs/41554499701)
measures clean head `ebf3022d` with the same `57169e02` binary and W0 protocol.
The [density receipt](41554499701/13194239485/density.json) verifies ext4,
`quotaEnforced = false`, 5 GB, two CPUs and zero swap. All collectors and
API/host cleanup complete. Capacity is 25; the first 50-agent repetition fails.

| 25-agent repetition | Cold-start p50 | Judged p95 | OK rate | OOM kills |
|---|---:|---:|---:|---:|
| 0 | 19.005981 ms | 242.347786 ms | 1.0 | 0 |
| 1 | 19.537474 ms | 234.782458 ms | 1.0 | 0 |
| 2 | 20.145433 ms | 236.964911 ms | 1.0 | 0 |

At 50 agents, 783 `SESSION_NOT_READY` refusals reduce the judged OK rate to
0.1357615894, with zero OOM. The 38.961074 ms p95 includes fast refusals and
establishes no speed gain. Cold-start p50 is 23.055662 ms. Warmup commands time
out before the judged window; the receipt preserves those attempts too.

Across the three 25-agent command windows, daemon CPU consumes
172.467091–173.354668 seconds over 179.474989–179.494933 seconds of sampled wall
time, approximately 0.96 CPU cores. At 50 it consumes 198.107331 CPU seconds over
179.450435 seconds. These are whole-daemon observations, not a scanner-only CPU
profile. Peak pool memory approaches 5 GB in every stage. Ordinary storage at
this corpus size has a measured density cost; no capacity improvement is claimed.

Per-session accounting remains complete and refreshes during the command window.
Pool observation time is the oldest constituent sample, including the inactive
base volume; it is not a new simultaneous measurement of every volume. The
companion footprint, cache and trace receipts use their unchanged XFS fixtures,
so they do not establish ordinary-storage fixed costs or trace timings.

## Additional consumers

The same CI binary passes [Java volume/snapshot acceptance](local/java-volume1.json)
(eight Java checks), [live observer recovery](local/java-recovery2.json) (five checks
and four recoveries), [the game consumer](local/java-games1.json), and
[delegated-container acceptance](local/delegated1.json) (ten checks). All confirm cleanup.
[The gate record](local/consumer-gates1.json) identifies the staged Java runtime.

[Recovery attempt 1](local/java-recovery1.json) fails before fault injection because
its controller searches for obsolete per-job systemd units. Attempt 2 observes
the actual delegated job cgroups and requires their removal before acknowledging
recovery. The failed attempt also confirms cleanup; it is not runtime failure evidence.
