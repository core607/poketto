# Sandcrab Finite Jobs

Date: 2026-10-28

## Problem

Phase 2 of the [sandcrab proposal](../proposed/2026-10-26-sandcrab-sandbox-service.md): games ran on a Python service built from the repository worker's classes, so admission, revocation, replay and unit cleanup existed twice, and each new finite consumer would add another copy.

## Decision

[Finite jobs](../../executor-service/rust/sandcrab-daemon/src/job.rs) are a daemon primitive. A job is admitted under its request's lease beside sessions, so revocation, expiry, shutdown and replay treat it as a lease. It runs one launcher unit with bounded output and a timeout, then confirms the unit stopped with an empty cgroup. **A job without that confirmation keeps its admission slot and reports `CLOSING` to revocation until a restart cleans up.** A cancelled or expired job returns its expiry code whatever it printed. An extension narrows the daemon's own operations to `REVOKE`.

`poketto-games` serves the unchanged game protocol on it: each request gets a fresh tmpfs, a game-mode `poketto-launch` unit and an unmount before admission is released. Its runtime root, account, slice and unit prefix differ from the repository supervisor's ([pocket games](2026-10-09-pocket-games.md)). Both supervisors share `sandcrab_core::unit::Profile` and `sandcrab_daemon::install`.

## Alternatives

- **A games mode in the repository supervisor binary.** One binary fewer, but the games process would carry copy provisioning it must never run.
- **Retrying a leaked job's cleanup on revocation, as the Python service did.** Revocation could report `CLOSED` sooner, but the daemon would keep each failed job's unit and mount; a restart runs the same cleanup.

## Consequences

Production runs no Python supervisor; [worker.py](../../executor-service/worker.py) remains the test oracle. Output that is not one JSON step returns `INVALID_GAME_OUTPUT` instead of an executor failure. Installing `poketto-games` stays manual, outside the repository worker update.

## Verification

`executorRustCheck` runs the job admission tests and the `poketto-games` bounds, configuration and policy tests. The [native probe](../../executor-native/README.md) `games` scenario covers real containment, revocation, contention and supervisor loss and passed on production ([receipt](../../executor-native/evidence/2026-10-28-sandcrab-finite-jobs-native-probe.json)). Three [50-agent rounds](../../acceptance/evidence/2026-10-28-sandcrab-finite-jobs-50-agent-rounds.json) matched phase 1 goodput, refusals, CPU and server latency; client p95 ranged 0.45–0.75 s across identical runs.
