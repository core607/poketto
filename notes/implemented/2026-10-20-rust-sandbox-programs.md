# Rust Sandbox Programs

Date: 2026-10-20
Status: Implemented

## Problem

The sandbox's `poketto` client starts a process for every host operation, and its
resident shell driver waits between commands. Python adds startup and resident
memory to both; a 50 ms shell-polling timeout also wakes every idle unit twenty
times a second. The
[direct launcher](2026-10-18-direct-sandbox-launch.md) removes SRT from unit startup
but does not remove this per-command cost.

## Decision

The CLI, shell driver and filesystem-sync helper are static x64 Rust programs. `serde_json` owns JSON;
`rustix` supplies safe Unix, pipe and pidfd interfaces; `base64` encodes binary
output frames without a custom codec. The implementations forbid
unsafe Rust. The pinned toolchain, Cargo lockfile and image digest make dependency
updates deliberate; every Cargo build uses `--locked`.

Filesystem synchronization uses `poketto-syncfs` under the supervisor's existing
15-second subprocess timeout. An in-process call would remove that timeout;
the system `sync` utility retains its startup cost. The helper opens one
directory without following a symlink and reports synchronization errors before
acknowledgement.

The client keeps the host bridge's authority boundary: current execution identity,
exclusive writer lock, bounded length-prefixed frames, protected response-file
checks and acknowledgements. A known host result survives an unsuccessful ack;
an uncertain write is never retried. The reply deadline remains 55 seconds.
Subcommands, defaults, input bounds, help content and exit statuses remain covered
by a test-only Python oracle; that oracle is absent from runtime bundles.

The [worker delivery decision](2026-10-19-coordinated-worker-delivery.md) owns
installation, generation selection and rollback. Programs run from the selected
generation's protected `bin/` directory, bound read-only into every repository
sandbox. Sharing one inode lets units share executable pages instead of each
paying for a copied binary. The directory contains only the expected programs;
supervisor startup and every launch verify ownership and permissions.

The driver blocks on command input, shell replies, output FIFOs and a pidfd; it
has no periodic idle timer. A sentinel holds each FIFO open until bash opens both
writers. Completion drains only the bytes already present, so a background writer
cannot delay it indefinitely. Finished-command output is discarded, and retaining
at most 128 readers bounds descriptor use. The shell wrapper, execution identity,
frame bounds and exit-status mapping remain part of the protocol.

The [in-repository split](2026-10-27-sandcrab-in-repository-split.md)
owns the launcher; [pocket games](2026-10-09-pocket-games.md) owns its use of the generic service. The
[repository supervisor decision](2026-10-21-rust-repository-supervisor.md) owns its host runtime.

## Alternatives

Python startup flags remove some imports but retain the interpreter cost.
An event-driven Python driver removes idle polling but retains interpreter
startup and resident memory. A native driver addresses both costs.
Embedding the bridge client in the shell driver cannot replace the executable
that arbitrary scripts and subprocesses invoke through `PATH`; it also mixes
execution framing with host operations. Go brings a runtime and additional
resident resources for the proposed driver. C avoids those costs but adds
memory-safety work around JSON and protocol validation. Rust permits a shared
toolchain across the small programs while retaining memory safety.

## Consequences

The build gains a compiled toolchain, provided by Docker on contributor machines.
The host bridge and client share Rust.
Wire changes update their parity fixtures. Python remains available to sandbox
commands. No unsupported isolation configuration gains an
unsandboxed fallback.

`executorRustCheck`, `executorServiceTests`, `existingDeploymentTests` and
`appImageIdentityCheck` cover the compiled program, wire contract and delivery.
The native adapter, toolkit and game probes exercise real containment; the
`load` lane and same-host capacity comparisons cover behavior under concurrency.
The serial [CLI probe](../../executor-native/cli_probe.py) compares no-bridge
`status` startup against the test oracle on one host and requires the native CPU
median to be at most one tenth. This microbenchmark does not measure HTTP latency
or establish concurrent capacity.
The sandbox comparison measures each idle driver for 60 seconds and requires
native CPU use to stay at most one tenth of the Python reference, while reporting
memory and startup costs. `test_shell_loop.py` and `test_command_channel.py` drive
the native binary; the Python driver remains only a measurement reference.
