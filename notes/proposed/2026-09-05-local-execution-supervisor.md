# Local Execution Supervisor

Date: 2026-09-05
Status: Proposed

## Problem

[Phase one](2026-09-05-phase-one-daily-use.md) requires authenticated repository
execution with independent process, filesystem, network, and resource limits.
The [native feasibility probe](../../executor-spike/README.md) establishes SRT
isolation but its temporary root test harness supplies no production lease,
revocation, or restart contract.

## Implemented components

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

## MCP and Java integration

Spring AI 2.0.1 supplies WebMVC Streamable HTTP at `/mcp`. Browser sessions do not authenticate it; the existing [identity boundary](../implemented/2026-09-06-workspace-identity-http.md) verifies workspace Bearer keys. The official SDK session ID binds the principal and workspace; independent clients sharing one key receive independent sessions. Tools use the [repository authoring services](../implemented/2026-09-05-repository-authoring-foundations.md) for authoritative files, exact images, idempotent uploads and revision-checked patches.

MCP initialization requests are limited to 16 KiB and session POST bodies to 32 MiB. Before SDK dispatch, the filter reads at most the limit plus one byte and rejects excess with 413. Four data slots cover body buffering and asynchronous handling; four separate slots admit complete `notifications/initialized` and `notifications/cancelled` bodies up to 16 KiB, with eight bounded prefix readers. Large admitted requests each reserve one 32 MiB buffer plus a sentinel byte. SDK transport exceptions become JSON-RPC error envelopes without exception internals; HTTP status, headers and cookies remain intact. Errors without an identifiable request omit `id`, as allowed by the [Streamable HTTP contract](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports). Standard RPC responses and event streams retain the SDK handling.

The `executor` Java module implements `RepositoryExecutor` through the signed local worker client. It validates root-owned protected socket paths, root peer credentials and a private Ed25519 PKCS8 signing key. Snapshot exports contain exact authoritative Git history without credentials, source object inode sharing or alternates. `get_file` never reads a command-modified execution copy. An omitted commit keeps an execution session pinned, and a patch does not silently switch it.

Set `POKETTO_EXECUTOR_ENABLED=true` only on Linux with the separate worker configured. Required application settings are `POKETTO_EXECUTOR_SOCKET`, `POKETTO_EXECUTOR_SIGNING_KEY` and `POKETTO_EXECUTOR_STAGING_DIRECTORY`. The default application admission is two sessions and the maximum bundle is 128 MiB; these starting values must not exceed the worker's configured limits and are not production sizing evidence. With execution disabled, `repo_exec` is absent from tool discovery. Worker or isolation failure never creates an ordinary subprocess fallback.

Exports independently limit compressed bundle bytes and preflight work: reachable blob sizes may total at most twice the configured bundle limit, with at most 100,000 commits and 250,000 total visited objects. The raw-byte check bounds compression work and can reject highly compressible history whose bundle would fit; it does not estimate the resulting bundle size. Both checks retain the export deadline.

The adapter renews leases while initialization or execution waits, rechecks stored authority on renewal, and propagates cancellation and committed revocation to the complete worker unit. Unconfirmed closure retains admission capacity. After a failed close attempt, the bounded control pool rechecks CLOSE at the worker's advertised renewal interval, without renewing authority or retrying execution. The original failed acknowledgement remains a failure until closure is actually confirmed. Only confirmed CLOSED or a different authenticated worker boot after startup cleanup releases that capacity; the old MCP session cannot reopen. Lost execution responses require reconciliation rather than a blind retry.

## Verification and remaining acceptance

The [native evidence](../../executor-service/evidence.jsonl) covers a synthetic
bundle, twenty directory reuses, same-key client isolation, denied host and
cross-session reads, PID namespace and network checks, external resource limits,
source immutability, cancellation, revocation, abandoned leases, and supervisor
SIGKILL cleanup. Unit tests use real Ed25519 signatures and exercise replay,
identity, expiration, admission, and initialization races. The required Gradle
`executorServiceTests` task runs those tests on Linux with zero skipped or aborted
tests; Windows uses a pinned Linux container. The normal `check` also runs actual
HTTP MCP protocol and PostgreSQL tests, Java socket tests, module checks and native
managed-storage replay. These gates do not rerun the privileged SRT probe or
replace real client and final deployment acceptance.

The Java adapter, MCP transport, live authorization boundary and worker source are
implemented. The checked-in [combined Java/worker evidence](../../executor-native/evidence/2026-09-05-combined.json)
records the exact synthetic runtime and source hashes, including lease and restart
behavior. It is distinct from a run against the final installed application.
This proposal remains pending for deployment integration and verification of the
exact production topology.
Real Codex and Claude Code acceptance, actual corpus/history costs, production
resource values, and final deployed-version agreement remain required by the
phase-one record. A passing synthetic probe does not satisfy those conditions.

## Same-topic audit

- Retain [repository retrieval and execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). This proposal narrows its local transport and substitutes bundle handoff for executor read access to application caches; its five-tool and isolation contracts remain useful.
- Retain [remote repository authority](../implemented/2026-09-01-remote-repository-authority.md). Execution copies never become write authority.
- Retain [phase-one delivery](2026-09-05-phase-one-daily-use.md), which owns completion criteria and excludes partial clone.
- Retain [the optional serverless profile](2026-09-01-optional-serverless-deployment-profile.md) as independent future work. This local socket and systemd topology does not implement remote workers.

No note is archived or rejected by this proposal.
