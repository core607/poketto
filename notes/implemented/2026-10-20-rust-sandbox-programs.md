# Rust Sandbox Programs

Date: 2026-10-20
Status: Implemented

## Problem

The sandbox's `poketto` client starts a process for every host operation. Parsing a
small command and exchanging bounded JSON through FIFOs do not need a Python
interpreter, yet every invocation pays for its startup and imports. The
[direct launcher](2026-10-18-direct-sandbox-launch.md) removes SRT from unit startup
but does not remove this per-command cost.

## Decision

The CLI is a static x64 Rust program. `serde_json` owns JSON encoding and decoding;
`rustix` supplies safe Unix flag and polling interfaces. The implementation forbids
unsafe Rust. The pinned toolchain, Cargo lockfile and image digest make dependency
updates deliberate; every Cargo build uses `--locked`.

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

The resident shell driver, launcher and supervisors remain Python. The
[remaining proposal](../proposed/2026-10-20-rust-sandbox-programs.md) owns their
separate evidence and acceptance conditions.

## Alternatives

Python startup flags remove some imports but retain the interpreter cost.
Embedding the bridge client in the shell driver cannot replace the executable
that arbitrary scripts and subprocesses invoke through `PATH`; it also mixes
execution framing with host operations. Go brings a runtime and additional
resident resources for the proposed driver. C avoids those costs but adds
memory-safety work around JSON and protocol validation. Rust permits a shared
toolchain across the small programs while retaining memory safety.

## Consequences

The build gains a compiled toolchain, provided by Docker on contributor machines.
The host bridge and client use different languages, so wire changes update both
and their parity fixture. Python remains available to sandbox commands and
retains privileged supervision. No unsupported isolation configuration gains an
unsandboxed fallback.

`executorRustCheck`, `executorServiceTests`, `existingDeploymentTests` and
`appImageIdentityCheck` cover the compiled program, wire contract and delivery.
The native adapter, toolkit and game probes exercise real containment; the
`load` lane and same-host capacity comparisons cover behavior under concurrency.
The serial [CLI probe](../../executor-native/cli_probe.py) compares no-bridge
`status` startup against the test oracle on one host and requires the native CPU
median to be at most one tenth. This microbenchmark does not measure HTTP latency
or establish concurrent capacity.
