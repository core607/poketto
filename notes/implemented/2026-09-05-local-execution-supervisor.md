# Local Execution Supervisor

Date: 2026-09-05
Implemented: 2026-09-15

## Problem

[Phase one](2026-09-05-phase-one-daily-use.md) requires authenticated repository
execution with independent process, filesystem, network, and resource limits.
The [native feasibility probe](../../executor-spike/README.md) established SRT
isolation, but its temporary root test harness supplied no production lease,
revocation, or restart contract.

## Decision

This record was consolidated on 2026-09-17 after the work shipped. Its transport,
resource and lifecycle guarantees are current; three later records refine parts of
it and are named where they apply. The [CodeAct MCP entrance](2026-09-10-codeact-mcp-entrance.md)
owns the tool catalog, the [account-copy decision](2026-09-14-account-working-copies.md)
owns disk quotas, shared copy identity, expiry and restart persistence, and
[executor command ownership](2026-09-16-executor-command-ownership.md) owns the
Java adapter's internal structure. Runtime leases and command containment remain
separate from stored-copy lifetime.

### Supervisor and worker

A small root resource supervisor sits behind a permissioned local UNIX socket.
It verifies Ed25519-signed Spring requests, mounts quota-bounded disk copies,
and launches fixed systemd units under an independent unprivileged execution
account. Both Git initialization and user commands run through pinned SRT
0.0.75. The supervisor never interprets shell commands or opens application
repository caches. Spring receives no Docker socket or elevated host capability.

Spring exports a bounded Git bundle containing the pinned commit and ancestor
history. A signed lease binds workspace, requesting grant, account, copy identity,
application epoch, worker epoch, export ID, commit, byte count, and digest.
The worker maps opaque export IDs under its configured export directory; caller
arguments cannot select a host path or remote address. A new bundle clone has
no source inode sharing, alternates, credentials, or inherited Git configuration.

Commands share a fixed low-privilege SRT unit within one lease while
the account/workspace copy persists across transports. Runtime, CPU, memory, swap,
descendant count, output, temporary storage, and repository storage are bounded
outside the command. Only one command runs per shared copy. Lease and request
admission have explicit bounds. Sandbox failure never invokes an ordinary
subprocess. The [per-lease command sandboxes](2026-09-16-per-lease-command-sandboxes.md)
decision owns unit reuse and reset reporting; this record retains the privileged boundary.

A dedicated systemd slice supplies the aggregate memory, swap, process and CPU
budget. The root supervisor and every transient command explicitly use the same
slice; per-command limits alone cannot bound supervisor allocations and all
concurrent commands. XFS project quotas bound retained storage separately. The
slice owns the resource values, while worker configuration names it. Startup and
new operations verify actual cgroup membership and finite kernel limits. A prefix
drop-in is insufficient because an omitted installation step could place commands
outside the pool. Cleanup and revocation remain available when resource validation
fails. The [worker reference](../../executor-service/README.md) owns installation
and the isolated retained-memory probe; examples do not replace real corpus sizing.

Cancellation and revocation stop the entire systemd unit. Short signed renewal
leases also expire when Spring fails or communication stops. Revocation and
pre-initialization cancellation establish tombstones before cleanup begins.
A worker restart invalidates all prior signatures. Service dependencies and
root-owned cleanup recover from supervisor SIGKILL; cleanup stops known units,
verifies empty cgroups, unmounts only generated session mountpoints, and removes
empty mount directories without traversing caller-controlled files. This runtime
cleanup preserves published disk copies and their protected account journals;
only explicit disposal or idle expiry removes retained work.
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

### MCP and Java integration

Spring AI supplies WebMVC Streamable HTTP at `/mcp`. Browser sessions do not
authenticate it; the [identity boundary](2026-09-06-workspace-identity-http.md)
verifies workspace Bearer credentials and [MCP OAuth](2026-09-11-mcp-oauth.md) issues
them. SDK transports bind their current principal and workspace, but do not own
copy identity or lifetime. Authorized clients of one account and workspace share
the default copy within the same reading scope. Tools use the
[repository authoring services](2026-09-05-repository-authoring-foundations.md) for
authoritative files, exact images, idempotent uploads and revision-checked saves.

[MCP request admission removal](2026-09-15-mcp-request-admission-removal.md)
superseded the former 16 KiB initialization and 32 MiB session-body limits, global
filter slots and filter-owned image reservations. All request bodies share a
128 KiB bound and the bounded streaming envelope preflight. Image import and
response owners retain the shared image budget through actual processing and
response writes. SDK transport exceptions become JSON-RPC error envelopes
without exception internals; HTTP status, headers and cookies remain intact.
Errors without an identifiable request omit `id`, and ordinary RPC responses and
event streams retain SDK handling.

The `executor` Java module implements `RepositoryExecutor` through the signed local
worker client. It validates root-owned protected socket paths, root peer
credentials and a private Ed25519 PKCS8 signing key. Snapshot exports contain exact
authoritative Git history without credentials, source object inode sharing or
alternates. Authoritative file reads never use a command-modified execution copy.
An omitted commit keeps the original copy baseline pinned, and a save does not
silently switch it.

`POKETTO_EXECUTOR_ENABLED=true` is set only on Linux with the separate worker
configured. Application settings include `POKETTO_EXECUTOR_SOCKET`,
`POKETTO_EXECUTOR_SIGNING_KEY`, `POKETTO_EXECUTOR_STAGING_DIRECTORY` and persistent
account-copy metadata; the worker requires its enforcing XFS pool. The default
application admission is four active leases and the maximum bundle is 128 MiB;
these values must not exceed the worker's configured limits and are not
production sizing evidence. Retained copy count and storage expiry are separate
limits. With execution disabled, `repo_exec` is absent from tool discovery.
Worker or isolation failure never creates an ordinary subprocess fallback.

Exports independently limit compressed bundle bytes and preflight work: reachable
blob sizes may total at most twice the configured bundle limit, with at most
100,000 commits and 250,000 total visited objects. The raw-byte check bounds
compression work and can reject highly compressible history whose bundle would
fit; it does not estimate the resulting bundle size. Both checks retain the export
deadline.

The adapter renews leases while initialization or execution waits, rechecks stored
authority on renewal, and propagates cancellation and committed revocation to the
complete worker unit. Unconfirmed closure retains admission capacity. After a
failed close attempt, bounded control handling rechecks CLOSE without retrying
execution. The original failed acknowledgement remains a failure until closure is
actually confirmed. Only confirmed CLOSED or a different authenticated worker boot
after startup cleanup releases that capacity. An authorized caller can then attach
the preserved copy under a new lease. Lost execution responses require
reconciliation rather than a blind retry.

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
properties. Resource examples require real corpus sizing before production, which
the acceptance below supplied.

## Verification and acceptance

The original [native evidence](../../executor-service/evidence.jsonl) covers a synthetic
bundle, twenty directory reuses, same-key client isolation, denied host and
cross-session reads, PID namespace and network checks, external resource limits,
source immutability, cancellation, revocation, abandoned leases, and supervisor
SIGKILL cleanup. Unit tests use real Ed25519 signatures and exercise replay,
identity, expiration, admission, and initialization races. The required Gradle
`executorServiceTests` task runs those tests on Linux with zero skipped or aborted
tests; Windows uses a pinned Linux container. The normal `check` also runs actual
HTTP MCP protocol and PostgreSQL tests, Java socket tests, module checks and native
managed-storage replay. These gates do not rerun the privileged SRT probe. The
same-key directory separation in that evidence predates shared account copies;
current ownership, retention and disposal evidence is linked from the account-copy
decision.

The checked-in [combined Java/worker evidence](../../executor-native/evidence/2026-09-05-combined.json)
records the exact synthetic runtime and source hashes, including lease and restart
behavior. The conditions that kept this record pending, deployment integration in
the exact production topology, real corpus and history costs, production resource
values and callable client acceptance, were met on 2026-09-15: the
[real-corpus worker sample](../../acceptance/evidence/2026-09-15-real-corpus-worker-timing.json)
measures an isolated initial disk copy of a 173 MB retained bundle and twenty reused
native executions on the installed worker and SRT; the
[sandbox toolkit run](../../acceptance/evidence/2026-09-15-sandbox-toolkit.json) exercises
the host-installed toolkit; and the [multi-user daily-use acceptance](2026-09-15-multiuser-daily-use-acceptance.md)
records the executor on the authorized HTTPS installation with its four execution
slots and 32 GiB pool.

## Same-topic audit

- [Repository retrieval and execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) is retained: this record narrowed its local transport and substituted bundle handoff for executor read access to application caches, and its composable execution and isolation contracts remain; [directory navigation](2026-09-08-repository-directory-navigation.md) extends basic reads independently of the worker.
- [Remote repository authority](2026-09-01-remote-repository-authority.md) is retained. Execution copies never become write authority.
- [Phase-one delivery](2026-09-05-phase-one-daily-use.md) is retained; it owns completion criteria and excludes partial clone.
- [Account working copies](2026-09-14-account-working-copies.md), the [CodeAct MCP entrance](2026-09-10-codeact-mcp-entrance.md) and [executor command ownership](2026-09-16-executor-command-ownership.md) are retained as the authorities named above.
- The [optional serverless profile](../proposed/2026-09-01-optional-serverless-deployment-profile.md) stays an independent proposal; this local socket and systemd topology does not implement remote workers. [Per-lease command sandboxes](2026-09-16-per-lease-command-sandboxes.md) owns the implemented runtime reuse.

No note is archived or rejected by this consolidation.
