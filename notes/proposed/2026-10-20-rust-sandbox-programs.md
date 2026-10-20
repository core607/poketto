# Rust Sandbox Programs

Date: 2026-10-20
Status: Proposed

## Problem

[Direct sandbox launch](../implemented/2026-10-18-direct-sandbox-launch.md#resource-evidence) removed Node.js from the start path. A fresh unit now costs 149 ms of CPU, 8.8 MiB of idle memory and 6 tasks on the production host. Three Python programs remain on the command path, and their cost comes from starting and keeping a Python interpreter, not from the work they do:

- The `poketto` CLI, [cli.py](../../executor-service/cli.py), starts a new interpreter for every invocation inside the sandbox.
- The shell driver, [shell_loop.py](../../executor-service/shell_loop.py), stays resident in every live unit. It waits with a 50 ms selector timeout and polls its shell after each wake, so an idle unit wakes 20 times a second.
- [launcher.py](../../executor-service/launcher.py) starts once per fresh unit and then replaces itself with `bwrap`.

Measurements on a 4-vCPU development container (Xeon 2.1 GHz, Python 3.13, 2026-10-20) give ratios, not production figures:

- `cli.py status` with no bridge parses its arguments and exits before any request: 38.6 ms CPU, median of 15. An executable that only starts and exits used 1.4 ms, and a Rust stand-in doing the same parsing 2.0 ms.
- The interpreter with the launcher's imports used 40.8 ms, and with the shell driver's imports 54.2 ms.
- An idle loop shaped like the shell driver, with a live `bash` child, used 1.9–2.0 ms of CPU per second and held 4.8 MB of anonymous memory. A Rust stand-in that blocks on stdin and a pidfd for the child used no measurable CPU and 128 KB.

At those rates, 100 idle units spend about 0.2 core on polling, and 10 `poketto` calls per second spend about 0.4 core on interpreter startup. On the 2-vCPU production host that would be a tenth and a fifth of its CPU. Neither figure has been measured under load there.

## Proposal

Replace the three programs with Rust executables in three phases. Each phase is a separate change and ships only when its acceptance criteria hold. Protocols, paths, limits and messages stay as they are, except where a phase names a change.

### Build and delivery

- A Cargo workspace at `executor-service/rust/` pins an exact stable toolchain in `rust-toolchain.toml` and commits `Cargo.lock`; every build uses `--locked`. Release binaries are static `x86_64-unknown-linux-musl` executables, so they do not depend on the host's C library version. The executor already requires an x64 host, and the `apply-seccomp` pin is x64.
- Dependencies stay few: JSON, SHA-256 and system-call wrappers. A pull request that adds a crate states why. `unsafe` appears only for system calls that the wrappers lack.
- The application image builds the binaries in a digest-pinned Rust stage and places them beside the worker sources in `/opt/poketto-worker/`. `SHA256SUMS`, the file set in [worker_release.py](../../deploy/worker_release.py) and `appImageIdentityCheck` cover them. The updater requires them to be root-owned, executable and not writable by other accounts, like the sources. [Coordinated worker delivery](../implemented/2026-10-19-coordinated-worker-delivery.md) then delivers them with every release, and the operator-owned tools directory stays unchanged.
- Units run the binaries from one directory that holds only them and is bound read-only into every sandbox, so all units share their file pages. The worker copies cli.py and shell_loop.py into each lease's bootstrap today; doing the same with binaries would charge every lease its own copy. That directory joins the readable list of the containment contract.
- A Gradle task runs `cargo fmt --check`, `cargo clippy -- -D warnings` and `cargo test --locked`. `executorServiceTests` drives the built binaries, and both tasks belong to `check` and the `image` lane. Windows contributors build through the executor test image. AGENTS.md's command requirements name the toolchain.

### Phase 1: the `poketto` CLI

The binary keeps every subcommand, option and default, and the request envelope of each operation. It also keeps the 512 KiB input and frame bounds and the bridge protocol:

- the exclusive `lock`;
- length-prefixed frames written to `requests`;
- the type, mode and size checks on each response file;
- the `ack`;
- the active-execution state check before the request and while waiting;
- the 55-second reply wait that [SessionRepositoryCommands](../../src/main/java/io/github/core607/poketto/executor/internal/SessionRepositoryCommands.java) relies on.

Results stay one JSON line on stdout. Exit status stays 0 for an `ok` result, 1 for a failed result or an unavailable bridge, and 2 for a usage error. The bridge-failure message keeps its wording, and its code token still names the failure kind and errno. Help keeps its content, including the workflow epilog that agents read; argparse's exact layout is not part of the contract. The reply wait may use inotify instead of 20 ms sleeps if the state checks and the deadline keep their meaning. cli.py leaves the runtime file set in the same change.

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

- Phase 1:
  - A parity test runs each subcommand, a usage error and each bridge failure through cli.py and the binary against the same fake bridge. Request envelopes must match apart from `requestId`, and stdout, exit status and the bridge-failure message must match apart from the code token.
  - The bridge and worker tests that use cli.py as the sandbox client drive the binary instead.
  - On the same host, `poketto status` without a bridge uses at most a tenth of cli.py's CPU.
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

- Tuning Python startup: a bare interpreter with `-I -S` still used 14 ms in the same container, ten times a native start, and the resident driver keeps its interpreter memory.
- Fixing only the driver's polling in Python: `os.pidfd_open` and a blocking selector remove the idle CPU without a new toolchain, but keep the driver's startup and its 4.8 MB. This is a smaller change that could ship first; phase 2 replaces it.
- Serving `poketto` from the resident driver: this avoids per-call startup, but scripts and subprocesses that run `poketto` need an executable on `PATH`. The driver would also become a bridge client; today its frames report execution and never carry host authority.
- Go: startup is comparable, but its runtime gives each resident process several threads and a few megabytes. Every idle unit would carry them against `TasksMax`, and one language across the three programs favors Rust.
- C: it is as fast, but the launcher runs outside the boundary before seccomp is installed, where a memory-safety defect matters, and the CLI needs JSON parsing.
- Rewriting the supervisor as well: see the out-of-scope section above.

## Consequences

The build gains a second compiled toolchain in CI, the image and contributor setup, and Rust updates are pinned deliberately. The bridge protocol gets a Python host end and a Rust client end, so a wire change updates both ends and the parity test. When each phase is implemented, the same change updates the executor README's [runtime](../../executor-service/README.md#runtime) file list and launch description, and the probes' and acceptance staging's file lists. It also updates coordinated worker delivery's statement that the image carries Python sources, and the [per-lease sandboxes](../implemented/2026-09-16-per-lease-command-sandboxes.md) note's description of the driver. Phase 3 also updates direct sandbox launch's description of the launcher. The development baseline's toolchain list and AGENTS.md change with the first phase.
