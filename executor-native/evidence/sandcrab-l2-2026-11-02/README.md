# L2 development evidence

This change serves lightweight deployment by replacing native per-operation
systemd transactions with daemon-owned cgroups. These local receipts validate
implementation and measurement paths. All three canonical 5 GB runs retained
capacity 50; the listener/tool-wait candidate misses the cold-start target. Fresh
installation replay completes on five environments. This report establishes
neither a capacity improvement nor the 30 ms cold-start target.

## Sources and scope

The [index](index.json) records the size and SHA-256 of each retained receipt.
Every attempt names its binary/probe hashes. `e0a8b68f6b667673e8025c87884135e8259c151d`
in local source metadata is the base of a dirty development tree, not an assertion
that main already contains L2. The initial static measurement uses
`244a094477681d78c6004c9ab9b16720b0682056db57bd514e268d61d6cb4a2b`.
Earlier test candidates remain separately identified in their receipts.

The native fixture runs in a disposable Ubuntu 24.04 userland with a shared Linux
kernel. Its outer container allows 2 CPUs, 2 GiB and no swap. Measurement smoke
uses a 1 GiB aggregate pool, 512 MiB/2,048-task daemon leaf, and 8,192-task pool.
It does not emulate the canonical 4/5/8 GB cohort protocol. Separate delegated
container checks run Python as PID 1 without a systemd manager; root-direct
checks start the daemon without service-manager calls on an already delegated parent.

## Observations

| Measurement | Local result |
|---|---|
| Static binary | 4,162,464 bytes; no ELF interpreter or dynamic dependencies; L1 was 4,125,600 bytes |
| Prepared generic tools | 9,255,073 bytes, unchanged from L1 |
| Runtime executable inventory | 10; four requirements removed: systemctl, systemd-run, busctl and env |
| Median daemon idle RSS | 3,342,336 bytes in the one-item footprint smoke |
| Idle-session pool delta | 3,063,808 bytes at count one; not a canonical marginal-cost comparison |
| Density smoke | Two agents, 88 judged attempts, 100% OK, p95 39.547478 ms, zero OOM |
| Untraced session cold starts | 65.338100 and 92.846623 ms in that smoke; no 30 ms claim |
| Traced startup | Three complete creation/exec/placement/bwrap/bash timelines; observer overhead included |

The old W0/L1 executable inventories omitted `id`, already used for account
validation. A consistent comparison is L1 14 versus L2 10; original receipts
remain unchanged. Memory and latency observations above are not compared with
the independent canonical L1 runner. Density, footprint, cache and trace smoke
all completed with API and host cleanup.

The [first CI smoke](ci-first-smoke/smoke.json) also completed with cleanup:
88 judged attempts passed, while its two untraced session starts took 161.649067
and 202.289373 ms. Its [kernel trace](ci-first-smoke/cold-start-smoke.json)
records approximately 20 ms gaps after some mount processes exit. The bounded
host-helper wait polled without a child-exit descriptor; the delegated-cgroup
decision owns the corresponding optimization. These observations do not replace
the pending canonical measurements.

The subsequent helper-wait candidate is
`ad7cbf7218b096ad14429ceda142ddd8d075b18bfd2d44448b14f4f77a6cae9a`:
4,170,656 static bytes with the same tools and executable inventory. Rust checks,
176 service tests and [43 native checks](local/native5.json) passed. Its local
[density smoke](local/density-smoke2.json) passed 88 judged attempts with cleanup,
with cold starts of 47.198925 and 58.258623 ms. That short run overlapped consumer
tests elsewhere on the development host and is not a stable before/after estimate.
The later [trace](local/cold-smoke2.json) has sub-millisecond gaps from mount exits
to the next step in all three trials; its launcher-placement interval remains
17–20 ms. The [footprint smoke](local/footprint-smoke2.json) measured 3,338,240 bytes
median daemon RSS. Both completed with cleanup. The 30 ms target remains unproven.

The listener-only candidate's [control measurement](local/control-latency2.json)
reduces median latency for 50 new Unix connections from 10.230086 to 0.261476 ms
in the same local container. This read-only control request is not a cold start.
The final local listener/tool-wait candidate is `9f4f93bb…`, with 4,166,560 static
bytes and the same dependencies. Its clean [smoke](local/density-smoke5.json)
passes 103 judged attempts, p95 29.054405 ms and zero OOM; its two cold starts
are 45.149886 and 61.307953 ms. [Footprint](local/footprint-smoke5.json) measures
3,305,472 bytes median daemon RSS; the [trace](local/cold-smoke5.json) retains
all three startup timelines. All three runs completed with API/host cleanup.
These small local measurements establish neither canonical capacity nor 30 ms.
Rust checks, 176 service tests, [43 native checks](local/native7.json),
[Java adapter acceptance](local/java-all3.json) and [games](local/java-games3.json)
passed on this candidate. The latter two include cleanup and process-loss checks.

## Functional evidence

At candidate `59047a9ebbe818a1a8724f7d6cb56f147678836ee8cf67cb0e9d75ec49e2ad43`,
native isolation/lifecycle checks passed 42 checks, contexts passed 10, and direct
and delegated-container modes passed 10 each. A separate closed-output test verifies
timeout, cancellation and successful long-running commands. The later measured
candidate widens bounded PID-list observations to support the configured task ceiling.
The root-fix candidate `d78c28011680253bd1e28b1844774f8940a8e7f5c8036cf8c733fd1150e8061e`
also accepts the real hierarchy root, where the kernel omits `cgroup.type`.
Its [read-only root check](local/hierarchy-root.json), `executorRustCheck` and
[43 native checks](local/native4.json) passed, including cleanup. The binary size
is unchanged; the smoke measurements above retain their earlier candidate identity.

[Published SDK conformance](local/sdk1.json) passed 21 Python/Kotlin checks.
[Inspect attempt 4](local/evals4/summary.json) passed all 22 cases across three
repetitions, using both workspace clients and independent native grading. It uses
`mockllm/model`, real sandboxes and zero model tokens. Its native preflights and
fixture cleanup passed. This is deterministic conformance, not paid-model usability evidence.
[Java adapter acceptance](local/java-all2.json) passed 40 checks and process-loss
expiry; [games](local/java-games1.json) passed with cleanup.

## Retained failures

- `contexts1` exposed a runtime regression: aggregate group OOM killed sibling
  contexts and aggregate counters contaminated later results. Per-leaf OOM
  ownership fixed it; `contexts2` and `native2` passed.
- `volume1` held the launch gate while waiting for unit shutdown. Releasing it
  after daemon loss and before shutdown fixed the fixture ordering; `volume2` passed.
- `files1` used the old owner-field assertion. `files2` incorrectly stopped
  waiting for a traced child after ESRCH. Both fixture fixes precede `files3`.
- `direct1` lacked parent CPU delegation; explicit delegation preceded `direct2`.
- `delegated1` passed its checks after incomplete Windows tool staging. Only
  `delegated2` and `delegated3` use the complete tool tree transferred as a native tar stream.
- Inspect attempt 1 lacked the caller example; attempt 2 hit the disposable
  unit's start-burst limit during deliberate configuration reloads; attempt 3
  lacked Git metadata. Attempt 4 uses the complete working tree and resets the
  fixture's start counter for each deliberate reload. All created fixtures were cleaned.
- The first Java attempt failed importing the controller's cryptography dependency
  before creating a fixture. Selecting the prepared consumer Python directory
  preceded the successful adapter and game runs.
- `native3` supplied a nonexistent optional example path and failed after the
  lifecycle checks. `native4` uses the CI command and passes; both retain cleanup evidence.
- The first CI readiness run completed on native Ubuntu 22.04/24.04 but failed
  in all three containers: copying the recipe to `/root` broke its source-relative
  import. Container cleanup passed. Invoking the recipe inside the unpacked source
  fixes that path; a fresh local Ubuntu 24.04 replay completed with cleanup.
  Its 12 commands and two configuration edits still miss the eventual readiness target.
- [Games attempt 2](local/java-games2.json) passed the gameplay/isolation checks
  but failed when systemd collected a killed unit before the fixture stopped it.
  Cleanup then rejected the new nested job `/tmp` mount. The [raw log](local/java-games2.log.txt)
  and [owned-fixture remediation](local/games2-remediation.json) retain both failures
  and verified cleanup. `density-smoke4`, `cold-smoke4` and `footprint-smoke4`
  ran before that remediation; their results are contaminated diagnostic attempts,
  not clean comparisons. The [preflight](local/residue-preflight1.json) now refuses
  an existing native fixture directory before allocating resources. Attempt 3
  passes game restart and cleanup; the clean measurement retry is smoke 5.

Original logs, source manifests, Inspect logs/viewer and all attempts are retained
with the operator's development artifacts. The indexed JSON and measurement ledgers
are the portable evidence; CI must still verify the final committed source.

## First canonical 5 GB run

[Run 41391010940](https://github.com/core607/poketto/actions/runs/41391010940)
measured head `a105f6c97fae1cb094584136f6e3fd95a04b66b9`, binary `d78c2801…`,
before the helper-wait optimization. The [density receipt](41391010940/13119127073/density.json)
uses the unchanged W0 protocol, with daemon and sandboxes sharing 5 GB, two CPUs
and zero swap. Density, footprint, cache and trace collectors completed with cleanup.

| Measurement | L1 canonical | L2 before helper-wait change |
|---|---:|---:|
| Largest passing stage | 50 | 50 |
| First failed stage | 100 | 100 |
| Failed-stage judged p95 | 3.712206361 s | 9.085206797 s |
| Failed-stage OK rate | 1.0 | 0.9592134013 |
| Failed-stage refusals | 0 | 56 SESSION_NOT_READY |
| Failed-stage OOM kills | 0 | 0 |
| Median daemon idle RSS | 3,084,288 bytes | 3,153,920 bytes |
| Pool memory delta per idle session at count 50 | 2,672,476.16 bytes | 1,537,884.16 bytes |
| Pool memory delta per finite run at count 50 | 2,194,227.20 bytes | 1,075,281.92 bytes |

The [footprint receipt](41391010940/13119127073/footprint.json) owns these fixed-cost
observations; independent CI runners do not establish a stable causal latency gain.
At 100 agents, three rg commands timed out during warm-up and closed their sessions;
56 later refusals entered the judged window. Both failure reasons, OK_RATE and P95,
remain recorded. Cold-start p50 at the three 50-agent repetitions was 182.978427,
172.652866 and 172.973170 ms, versus L1's 111.575018, 111.568136 and 121.118090 ms.
The subsequently optimized candidate requires its own measurement.

## Helper-wait canonical run

[Run 41392806548](https://github.com/core607/poketto/actions/runs/41392806548)
measured `5d390024554084d4712a4d23950c57d3ac74af58`, binary `ad7cbf72…`, after
pidfd helper waits and before listener/tool-verification waits changed.
All collectors and API/host cleanup completed under the same protocol.

The [density result](41392806548/13120988558/density.json) again passes all three
25- and 50-agent repetitions. Cold-start p50 at 50 agents is 50.747394,
50.736792 and 50.783314 ms: substantially below the first L2 attempt, still above
30 ms. Judged p95 at 50 is 0.556153874, 0.567255108 and 0.640679597 seconds.
These independent runner observations do not prove a stable latency improvement.

At 100, OK rate is 0.2868131868 with 1,298 SESSION_NOT_READY refusals and zero OOM.
The 0.174084905-second judged p95 includes those refusals; it cannot establish a
capacity or command-speed gain. The failed OK_RATE criterion stops the protocol.

The [footprint result](41392806548/13120988558/footprint.json) measures 3,215,360 bytes
median daemon RSS, 1,530,920.96 bytes pool delta per idle session at count 50,
and 1,070,612.48 bytes per finite run at count 50. The static binary remains
4,170,656 bytes and requires the same ten executable surfaces.

## Current installation replay

[Run 41392761102](https://github.com/core607/poketto/actions/runs/41392761102)
completed all five environments with cleanup using the helper-wait candidate.
The automatic installation clock excludes compilation, controller/image preparation
and human editing. Containers share their runner's kernel.

| Environment | Commands | Manual edits | Automated seconds | Receipt |
|---|---:|---:|---:|---|
| Native Ubuntu 22.04 | 12 | 2 | 15.068 | [JSON](41392761102/13118530488/readiness-native.json) |
| Native Ubuntu 24.04 | 13 | 3 | 8.582 | [JSON](41392761102/13118585252/readiness-native.json) |
| Ubuntu 22.04 container | 13 | 3 | 9.028 | [JSON](41392761102/13118540507/readiness-ubuntu22.json) |
| Ubuntu 24.04 container | 13 | 3 | 9.336 | [JSON](41392761102/13117498837/readiness-ubuntu24.json) |
| Debian 12 container | 13 | 3 | 4.483 | [JSON](41392761102/13118306531/readiness-debian12.json) |

The first sandboxed command passes; command/edit counts still miss the release
readiness target. L5/L10 own the installer and released quickstart.

## Listener/tool-wait canonical run and placement diagnosis

[Run 41403851267](https://github.com/core607/poketto/actions/runs/41403851267)
measured clean head `d5c07b018e800678a3320abd300f127397db7f9f`, binary `9f4f93bb…`.
Every collector and API/host cleanup completed. The [density receipt](41403851267/13125285147/density.json)
passes all three 25- and 50-agent repetitions. At 50, cold-start p50 is 39.047217,
36.670389 and 38.881163 ms; judged p95 is 0.606127653, 0.577810979 and 0.640154951 s.
The 30 ms target remains unmet. At 100, OK rate is 0.3041804180 with 1,265
SESSION_NOT_READY refusals and zero OOM. Its 0.169809532-second p95 includes refusals
and does not establish a speed or capacity gain.

The [footprint receipt](41403851267/13125285147/footprint.json) reports 3,219,456 bytes
median daemon RSS, 1,504,542.72 bytes pool delta per idle session at count 50,
and 1,096,417.28 bytes per finite run at count 50. Binary size is 4,166,560 bytes;
dependencies are unchanged. [CI](https://github.com/core607/poketto/actions/runs/41403832502)
passed at this head, including native 43, contexts 10, direct-root 10, SDK 21 and
[66 scripted Inspect cases](41403832502/13122663389/summary.json), with real sandboxes,
zero model tokens and cleanup. The PR merge tree is `e2228929f7e5a208a0fbc6b3ff354918bdabeed1`.
Five fresh [installation replays](https://github.com/core607/poketto/actions/runs/41403832450)
completed with cleanup but retain 12–13 commands and 2–3 manual edits.

The CI [smoke](41403832544/13123085136/smoke.json) has two cold starts of 22.688285
and 22.286267 ms; these do not replace the failed canonical metric. A local
[volume attachment trace](local/cold-volume1.json) observes roughly 6–7 ms in
validation helpers and 18–31 ms between trusted launcher exec and cgroup placement.
[Syscall tracing](local/cold-volume-syscalls2.json) attributes almost all that latter
interval to a one-byte `write`: 35.653506, 24.902773 and 12.871320 ms. It records
only syscall numbers, durations and results, not arguments or file contents.
The first syscall trace failed BPF compilation because it contained two END probes;
[that failure](local/cold-volume-syscalls1.json) retains successful cleanup.

Two bounded, single-threaded C prototypes compare `fork` plus migration with
`clone3(CLONE_INTO_CGROUP)`. [Burst trials](local/clone-placement1.json) show medians
of 0.087303 and 0.067796 ms. [Trials spaced 50 ms apart](local/clone-placement2.json)
show 21.878773 and 0.190946 ms, respectively. Each mode reaps all 50 children;
fixture cleanup passes. [Burst source](local/clone-placement1.source.c) and
[spaced source](local/clone-placement2.source.c) are retained. These are placement
measurements, not multithreaded spawn-safety or full sandbox-latency evidence.
The lightweight proposal owns the resulting direct-placement implementation target.
