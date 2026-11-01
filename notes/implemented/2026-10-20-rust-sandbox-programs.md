# Rust Sandbox Programs

Date: 2026-10-20
Partially superseded by: [Native repository consumer](2026-11-01-native-repository-consumer.md).
Status: Implemented

## Problem

The sandbox's `poketto` client starts a process for every host operation, and its
resident shell driver waits between commands. Python adds startup and resident
memory to both; a 50 ms shell-polling timeout also wakes every idle unit twenty
times a second. The
[direct launcher](2026-10-18-direct-sandbox-launch.md) removes SRT from unit startup
but does not remove this per-command cost.

## Decision

The resident shell driver and generic service are static x64 Rust programs. `serde_json` owns JSON; `rustix` supplies safe Unix, pipe and pidfd interfaces; `base64` encodes binary output frames without a custom codec. The implementations forbid unsafe Rust. The pinned toolchain, Cargo lockfile and image digest make dependency updates deliberate; every delivered Cargo build uses `--locked`.

The [native consumer decision](2026-11-01-native-repository-consumer.md) owns retirement of the signed worker, its compiled application CLI and filesystem-sync helper. The application supplies its host-call client without adding a product extension to the generic binary. This record retains the resident driver's state, output and resource rules.

The [independent delivery decision](2026-10-31-independent-sandbox-deployment.md) keeps installation and recovery operator-owned. The native service invokes its internal helpers from its own protected binary. Shared executable pages avoid a separate binary copy per sandbox; operator profiles and tool validation own the readable program paths.

The driver blocks on command input, shell replies, output FIFOs and a pidfd; it
has no periodic idle timer. A sentinel holds each FIFO open until bash opens both
writers. Completion drains only the bytes already present, so a background writer
cannot delay it indefinitely. Finished-command output is discarded, and retaining
at most 128 readers bounds descriptor use. The shell wrapper, execution identity,
frame bounds and exit-status mapping remain part of the protocol.

The [in-repository split](2026-10-27-sandcrab-in-repository-split.md)
owns the launcher; [pocket games](2026-10-09-pocket-games.md) owns its use of the generic service. The
[native consumer decision](2026-11-01-native-repository-consumer.md) owns application integration.

## Alternatives

For the resident driver, Python startup flags remove some imports but retain the interpreter cost.
An event-driven Python driver removes idle polling but retains interpreter
startup and resident memory. A native driver addresses both costs.
Embedding the bridge client in the shell driver cannot replace the executable
that arbitrary scripts and subprocesses invoke through `PATH`; it also mixes
execution framing with host operations. Go brings a runtime and additional
resident resources for the proposed driver. C avoids those costs but adds
memory-safety work around JSON and protocol validation. Rust permits a shared
toolchain across the small programs while retaining memory safety.

## Consequences

The build gains a compiled toolchain, provided by Docker on contributor machines. Shell framing changes update the native transport tests. Python remains available to sandbox commands. No unsupported isolation configuration gains an unsandboxed fallback.

`executorRustCheck`, `executorServiceTests`, `existingDeploymentTests` and `appImageIdentityCheck` cover the compiled programs, shell contract and delivery. `test_shell_loop.py` drives the actual native binary, including retained shell state and bounded output writers. The [native adapter and toolkit](../../executor-native/README.md), game probes and `load` lane exercise real containment. Historical startup and idle-driver measurements retain their original scope; they do not establish current HTTP latency or concurrent capacity.
