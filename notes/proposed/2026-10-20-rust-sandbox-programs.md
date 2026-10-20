# Rust Sandbox Programs

Date: 2026-10-20
Status: Proposed

## Problem

The [Rust CLI](../implemented/2026-10-20-rust-sandbox-programs.md) removes per-invocation interpreter startup. Two Python programs remain on the unit start path:

- [shell_loop.py](../../executor-service/shell_loop.py) stays resident in every live unit. Its 50 ms selector timeout polls the shell twenty times a second, even while idle.
- [launcher.py](../../executor-service/launcher.py) starts once per fresh unit and replaces itself with bubblewrap.

Measurements on a 4-vCPU development container (Xeon 2.1 GHz, Python 3.13, 2026-10-20) give ratios, not production figures. An idle loop shaped like the shell driver, with a live `bash` child, used 1.9–2.0 ms CPU per second and 4.8 MB anonymous memory. A Rust stand-in blocking on stdin and a pidfd used no measurable CPU and 128 KB. Interpreter startup with the launcher's imports used 40.8 ms CPU; the shell driver's imports used 54.2 ms. At those rates, 100 idle drivers spend about 0.2 core polling; that rate still needs production-host measurement.

## Proposal

Implement the remaining programs as separate changes, using the CLI's existing pinned Cargo workspace, static target and image-bound delivery. Keep protocols, paths, limits and messages unless a phase explicitly changes them. Extend the shared read-only program directory and its exact file allowlist, never the operator-owned tools tree. Each phase ships only after its acceptance criteria hold.

### Phase 2: the shell driver

The binary keeps the frame protocol on stdin and stdout, made of `ready`, `output` and `complete`, along with the execution-identity and size validation. It also keeps:

- per-command FIFOs, each with a sentinel writer that stays open until the shell has opened both of its writers;
- the drain at the completion boundary, which reads only the bytes already in each FIFO;
- the limit of 128 retained readers;
- the `bash` wrapper script and the exit-status mapping.

It blocks on stdin, the shell's reply pipe, the output FIFOs and a pidfd for the shell, and wakes only on one of those events. shell_loop.py leaves the runtime file set in the same change.

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

- Phase 2:
  - [test_shell_loop.py](../../executor-service/test_shell_loop.py) passes against the binary.
  - The [native adapter probe](../../executor-native/README.md)'s lease sandbox scenarios pass.
  - An idle unit uses at most a tenth of the Python driver's CPU over 60 seconds, and its idle memory is reported beside the 8.8 MiB reference.
- Phase 3:
  - The cases of [test_launcher.py](../../executor-service/test_launcher.py) pass against the binary.
  - The [launcher comparison](../../executor-native/README.md#launcher-comparison) accepts a binary launcher and finds identical containment for the Python and Rust launchers. It reports their resource ratio without the one-third requirement.
- Every phase:
  - The native adapter probe, the toolkit acceptance, the game worker's tests and the `load` lane pass.
  - A 50-agent round on one host with unchanged bounds, before and after the change, reports goodput, p95 latency, refusals and CPU split by the application container, the supervisor and the sandbox units, and none of them gets worse.

## Alternatives

- Tuning Python startup: a bare interpreter with `-I -S` still used 14 ms CPU in the same container; the resident driver also keeps its interpreter memory.
- Fixing only the driver's polling in Python: `os.pidfd_open` and a blocking selector remove the idle CPU without a new toolchain, but keep the driver's startup and its 4.8 MB. This is a smaller change that could ship first; phase 2 replaces it.
- Rewriting the supervisor as well: see the out-of-scope section above.

## Consequences

The [implemented CLI decision](../implemented/2026-10-20-rust-sandbox-programs.md) owns the Rust toolchain, shared program delivery and language alternatives. Each phase updates the executor README's [runtime](../../executor-service/README.md#runtime) list and the probes' and acceptance staging's file lists. Phase 2 updates the [per-lease sandbox decision](../implemented/2026-09-16-per-lease-command-sandboxes.md) to describe the native driver. Phase 3 also updates direct sandbox launch and makes both supervisors use the same executable verification path. The privileged supervisor remains Python unless the required later profile justifies a separate proposal.
