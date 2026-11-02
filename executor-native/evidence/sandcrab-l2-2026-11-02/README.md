# L2 development evidence

This change serves lightweight deployment by replacing native per-operation
systemd transactions with daemon-owned cgroups. These local receipts validate
implementation and measurement paths. Canonical 5 GB measurements and fresh
installation results remain pending; this report establishes neither capacity
improvement nor the 30 ms cold-start target.

## Sources and scope

The [index](index.json) records the size and SHA-256 of each retained receipt.
Every attempt names its binary/probe hashes. `e0a8b68f6b667673e8025c87884135e8259c151d`
in local source metadata is the base of a dirty development tree, not an assertion
that main already contains L2. The measured static candidate is
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
host-helper wait polls without a child-exit descriptor; the proposed performance
workstream owns the corresponding optimization. These observations do not replace
the pending canonical measurements.

## Functional evidence

At candidate `59047a9ebbe818a1a8724f7d6cb56f147678836ee8cf67cb0e9d75ec49e2ad43`,
native isolation/lifecycle checks passed 42 checks, contexts passed 10, and direct
and delegated-container modes passed 10 each. A separate closed-output test verifies
timeout, cancellation and successful long-running commands. The later measured
candidate widens bounded PID-list observations to support the configured task ceiling.
The final local candidate `d78c28011680253bd1e28b1844774f8940a8e7f5c8036cf8c733fd1150e8061e`
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

Original logs, source manifests, Inspect logs/viewer and all attempts are retained
with the operator's development artifacts. The indexed JSON and measurement ledgers
are the portable evidence; CI must still verify the final committed source.
