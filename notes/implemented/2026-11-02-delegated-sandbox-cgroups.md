# Delegated Sandbox Cgroups

Date: 2026-11-02

## Problem

Per-sandbox systemd transactions add startup and host requirements. Replacement
must preserve identity, temporary storage and containment: an empty leaf alone
does not establish launcher reaping.

## Decision

The native [runtime](../../executor-service/rust/sandcrab/src/runtime.rs) owns
delegated cgroups. A protected marker binds runtime storage to the delegation.
Locked recovery validates ownership before killing descendants and publishing
another epoch; unknown ownership refuses startup and preserves data.

The [process owner](../../executor-service/rust/sandcrab-core/src/process.rs)
retains the launcher handle through reaping and leaf containment. The private
[spawn boundary](../../executor-service/rust/sandcrab-spawn/src/lib.rs) uses
`CLONE_INTO_CGROUP`; native and core retain their unsafe-code prohibitions.
Trusted self-exec verifies placement before identity drop; uncertain cleanup
retains admission and storage.
Parent-death handling supplements explicit cleanup: credential changes require
rearming it, and the spawning thread must outlive the launcher.

A shared parent bounds contexts; each shell leaf owns group termination and OOM
evidence, so a sibling's failure cannot contaminate later results. Trusted file
helpers retain mount, capability and network isolation, not arbitrary root execution.

Helpers poll pidfd/output readiness to avoid EOF-before-exit delay, draining within
timeout. Failed observation kills the helper and reports failure. Bundled-helper
verification bounds file reads without executing unverified bytes. Listener readiness removes the
measured accept delay; a 10 ms bound preserves idle maintenance and shutdown.

This partially supersedes [direct launch](2026-10-18-direct-sandbox-launch.md)
for native runs and games. The repository worker retains its unit backend.
The [native README](../../executor-service/rust/sandcrab/README.md) owns deployment
requirements; the [lightweight proposal](../proposed/2026-11-02-sandcrab-lightweight-service.md)
retains the remaining targets and workstreams.

## Alternatives

Per-operation units retain startup and host requirements. Standard child handles
avoid unsafe post-fork setup, but subsequent `cgroup.procs` migration incurs the
[measured quiescent delay](../../executor-native/evidence/sandcrab-l2-2026-11-02/local/clone-placement2.json).
Direct placement needs a small audited syscall/exec boundary, without another
resident process or host-wide cgroup tuning. Missing kernel support refuses execution.

## Consequences

Daemon-owned lifecycle needs writable, bounded delegation. Pre-delegation state
is not adopted under another cleanup identity. Shared-kernel risks and workload
resource demand remain.

`executorRustCheck`, the native, delegation, context, file and volume probes,
and [SDK/Inspect conformance](../../evals/README.md) pin the implementation.
The [measurement suite](../../experiments/sandcrab-density/README.md) owns footprint,
readiness and cold-start evidence; functional checks establish no capacity gain.
