# Native Seccomp Filter

Date: 2026-11-02

## Problem

Provisioning the [direct launcher's](2026-10-18-direct-sandbox-launch.md) filter
helper installs Node.js, npm and SRT. Removing that dependency also removes its
protected nested PID namespace, potentially exposing an unfiltered, traceable reaper.

## Decision

The shared [launcher](../../executor-service/rust/sandcrab-launch/src/seccomp.rs)
compiles its filter with pinned `seccompiler`, preserving SRT 0.0.75's policy at
`40804af269e1616092e9971de12a1f358f58eba9`:

- Non-x86-64 audit architectures return `KILL_THREAD`.
- Unsigned syscall numbers at or above `0x40000000` return `KILL_THREAD`, except
  `0xffffffff`, which passes through to the kernel.
- `socket` with the low 32 bits of its domain equal to `AF_UNIX` returns `EPERM`.
- `io_uring_setup`, `io_uring_enter` and `io_uring_register` return `EPERM`.
- Other native calls remain allowed, including `socketpair` and `AF_VSOCK` socket
  creation. Broader denials belong to the separate hardening workstream.

The references are the upstream
[generator](https://github.com/anthropics/sandbox-runtime/blob/40804af269e1616092e9971de12a1f358f58eba9/vendor/seccomp-src/seccomp-unix-block.c)
and [helper](https://github.com/anthropics/sandbox-runtime/blob/40804af269e1616092e9971de12a1f358f58eba9/vendor/seccomp-src/apply-seccomp.c).
The x64 helper's SHA-256 is
`64fa7514fa7199584b836159885e59b9ce3684fb8fef7e13de49965b23f54f6b`.
Its embedded instructions form the independent test reference.

The launcher passes a sealed, rewound memfd to bubblewrap's `--seccomp FD`.
Installing the same base filter before exec protects bubblewrap's reaper before
its own filter installation. Every visible peer inherits these rules. The immutable
descriptor stays owned until exec and is consumed before the workload.
Installation failure refuses execution.

The [bundled helper's patch](../../executor-service/rust/sandcrab-launch/bwrap/loopback-index.patch)
keeps musl's loopback lookup compatible with inherited AF_UNIX denial. Helper
compatibility cannot weaken or defer filter installation.

This partially supersedes the direct-launch note's helper and tool-provisioning
choice. Its filesystem, environment, containment and upgrade obligations remain.
The [in-repository split](2026-10-27-sandcrab-in-repository-split.md) still owns crate
boundaries. The [lightweight proposal](../proposed/2026-11-02-sandcrab-lightweight-service.md)
retains the remaining workstreams.

## Alternatives

Fetching only the existing helper removes npm preparation but retains a separately
trusted executable and its reaper. A naive `seccompiler` policy changes ABI guard
behavior: its default architecture failure is `KILL_PROCESS`, and it lacks the
helper's x32 guard. Explicit guards preserve the existing behavior.

## Consequences

The release owns the filter and checksummed bubblewrap. Sandcrab needs no Node.js,
npm or SRT; Poketto separately provisions Node for commands and games.

`executorRustCheck` compares generated actions with the pinned helper and verifies
descriptor seals. The native probe checks rule equivalence, visible peers and FD
consumption; launcher, worker and game probes cover consumers and cleanup.
These establish neither kernel isolation nor broader hardening.
