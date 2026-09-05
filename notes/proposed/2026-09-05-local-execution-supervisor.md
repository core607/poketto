# Local Execution Supervisor

Date: 2026-09-05
Status: Proposed

## Problem

[Phase one](2026-09-05-phase-one-daily-use.md) requires authenticated repository
execution with independent process, filesystem, network, and resource limits.
The [native feasibility probe](../../executor-spike/README.md) establishes SRT
isolation but its temporary root test harness supplies no production lease,
revocation, or restart contract.

## Proposed decision

Use a small root resource supervisor behind a permissioned local UNIX socket.
It verifies Ed25519-signed Spring requests, owns bounded session tmpfs mounts,
and launches fixed systemd units under an independent unprivileged execution
account. Both Git initialization and user commands run through pinned SRT
0.0.75. The supervisor never interprets shell commands or opens application
repository caches. Spring receives no Docker socket or elevated host capability.

Spring exports a bounded Git bundle containing the pinned commit and ancestor
history. A signed lease binds workspace, key, account, server MCP session,
application epoch, worker epoch, export ID, commit, byte count, and digest.
The worker maps opaque export IDs under its configured export directory; caller
arguments cannot select a host path or remote address. A new bundle clone has
no source inode sharing, alternates, credentials, or inherited Git configuration.

Each command gets a fresh process tree in a fixed low-privilege SRT unit while
the session's own directory persists. Runtime, CPU, memory, swap, descendant
count, output, temporary storage, and repository storage are bounded outside
the command. Only one command runs per session. Session and request admission
have explicit bounds. Sandbox failure never invokes an ordinary subprocess.

Cancellation and revocation stop the entire systemd unit. Short signed renewal
leases also expire when Spring fails or communication stops. Revocation and
pre-initialization cancellation establish tombstones before cleanup begins.
A worker restart invalidates all prior signatures. Service dependencies and
root-owned cleanup recover from supervisor SIGKILL; cleanup stops known units,
verifies empty cgroups, unmounts only generated session mountpoints, and removes
empty mount directories without traversing caller-controlled files.
The worker publishes a new boot ID through HELLO only after acquiring its
exclusive lock and completing startup cleanup. The application may use a
different authenticated boot ID to retire unresolved leases from the earlier
worker, but cannot infer cleanup from a failed connection or an unchanged ID.

The [worker reference](../../executor-service/README.md) owns the exact wire
schema, state transitions, operational requirements, and executable tests.

SRT's Linux mount ordering requires disjoint read-only and writable grants.
The worker grants read access to the trusted bootstrap directory and, during
initialization only, the bundle file; working and home directories receive
their own write grants. A read grant for the whole session parent can install
a later read-only bind over the writable children. Keeping test runtime state
under `/tmp` can conceal that error because the invocation's temporary-write
grant also covers it. The native probe therefore places runtime state under
`/run`, with the production supervisor umask, while synthetic source and tools
remain in a separate disposable directory. No special relaxation for `/run`
or broader grant to the session parent is part of this decision.

## Alternatives and consequences

A fully unprivileged daemon could use delegated cgroup v2 and user namespaces,
but delegation must be isolated from the command's UID. It also needs race-free
process admission, enforceable persistent storage bounds, and crash cleanup of
namespace holders. Those mechanisms add unverified dependencies to the existing
systemd-based boundary. A narrow privileged supervisor keeps those operations
under one service manager while command interpretation remains unprivileged.

Granting the executor account read access to the application cache would avoid
bundle export, but expands its host authority and creates source-object copying
risks. Explicit bundles isolate the transfer boundary at the cost of copying
historical objects. Ordinary Git history transfer remains in scope; partial
clone remains excluded.

The supervisor is security-sensitive privileged code. Its executable, toolchain,
configuration, verification key, and generated records must be root-owned.
Signed requests do not excuse unsafe filesystem cleanup or arbitrary systemd
properties. Resource examples require real corpus sizing before production.

## Verification and remaining acceptance

The [native evidence](../../executor-service/evidence.jsonl) covers a synthetic
bundle, twenty directory reuses, same-key client isolation, denied host and
cross-session reads, PID namespace and network checks, external resource limits,
source immutability, cancellation, revocation, abandoned leases, and supervisor
SIGKILL cleanup. Unit tests use real Ed25519 signatures and exercise replay,
identity, expiration, admission, and initialization races.

This proposal remains pending until the Java adapter, live authorization events,
deployment artifacts, and exact production topology are integrated and tested.
Real Codex and Claude Code acceptance, actual corpus/history costs, production
resource values, and final deployed-version agreement remain required by the
phase-one record. A passing synthetic probe does not satisfy those conditions.

## Same-topic audit

- Retain [repository retrieval and execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). This proposal narrows its local transport and substitutes bundle handoff for executor read access to application caches; its five-tool and isolation contracts remain useful.
- Retain [remote repository authority](../implemented/2026-09-01-remote-repository-authority.md). Execution copies never become write authority.
- Retain [phase-one delivery](2026-09-05-phase-one-daily-use.md), which owns completion criteria and excludes partial clone.
- Retain [the optional serverless profile](2026-09-01-optional-serverless-deployment-profile.md) as independent future work. This local socket and systemd topology does not implement remote workers.

No note is archived or rejected by this proposal.
