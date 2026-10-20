# Rust Sandbox Programs

Date: 2026-10-20
Status: Proposed

## Problem

The [native CLI and shell driver](../implemented/2026-10-20-rust-sandbox-programs.md) remove their interpreter startup and resident polling. The remaining [launcher.py](../../executor-service/launcher.py) starts Python once per fresh sandbox and replaces itself with bubblewrap. A 2026-10-20 development-container measurement used 40.8 ms CPU for interpreter startup with its imports; that is not evidence that launcher startup limits production capacity.

## Proposal

### Phase 3: the launcher

Phase 3 starts only if fresh starts still limit capacity after phase 2. The evidence is a load round in which, at the tier where goodput falls, startup admission refusals or `workerReadyMs` dominate. Otherwise phases 1 and 2 are recorded as implemented, and this condition is recorded as the reason to reconsider the launcher.

The binary keeps tool verification:

- root-owned, unwritable binaries and ancestors;
- the `apply-seccomp` SHA-256 pin;
- bubblewrap 0.6.1 or newer.

It also keeps policy validation and translation, the descriptor-based entry into the bootstrap, the environment and each mode's command, and then executes `bwrap`. The baseline installer stays a Python program that runs inside the sandbox. The repository and game supervisors' startup checks run the launcher binary's verification instead of importing launcher.py, so one implementation decides every check. The unit then runs the binary directly instead of `python3 launcher.py`.

### Out of scope

The supervisor (`worker.py` and its modules), the game worker and the host end of the bridge stay in Python. They are long-lived processes that pay for each request, not for each start, and they hold the privileged lifecycle logic. Rewriting them requires a profile, taken after phase 2, that shows supervisor CPU limiting capacity. Python stays available inside sandboxes for commands and the [toolkit](../implemented/2026-09-05-local-execution-supervisor.md#sandbox-toolkit).

## Acceptance criteria

- Phase 3:
  - The cases of [test_launcher.py](../../executor-service/test_launcher.py) pass against the binary.
  - The [launcher comparison](../../executor-native/README.md#launcher-comparison) accepts a binary launcher and finds identical containment for the Python and Rust launchers. It reports their resource ratio without the one-third requirement.
- Every phase:
  - The native adapter probe, the toolkit acceptance, the game worker's tests and the `load` lane pass.
  - A 50-agent round on one host with unchanged bounds, before and after the change, reports goodput, p95 latency, refusals and CPU split by the application container, the supervisor and the sandbox units, and none of them gets worse.

## Alternatives

Keep the Python launcher while fresh starts do not limit measured capacity. That avoids another implementation of privileged-boundary validation. Interpreter flags alone reduce imports but retain startup cost. Rewriting the long-lived supervisors is outside this proposal and requires the separate profile described above.

## Consequences

The [implemented sandbox-program decision](../implemented/2026-10-20-rust-sandbox-programs.md) owns the Rust toolchain, shared program delivery and language alternatives. A native launcher updates the executor README's [runtime](../../executor-service/README.md#runtime) file list, acceptance staging and direct sandbox launch's description of validation. Both supervisors must use the same executable verification path; host configuration and tools remain operator-owned.
