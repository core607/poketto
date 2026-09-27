# Local Execution Supervisor

Date: 2026-09-05
Implemented: 2026-09-15

## Problem

[Phase one](2026-09-05-phase-one-daily-use.md) requires authenticated repository
execution with independent process, filesystem, network, and resource limits.
A native feasibility probe, since replaced by the worker's
[native probe](../../executor-service/native_probe.py), established SRT
isolation, but its temporary root test harness supplied no production lease,
revocation, or restart contract.

## Decision

The [CodeAct MCP entrance](2026-09-10-codeact-mcp-entrance.md) owns the tool
catalog, [account working copies](2026-09-14-account-working-copies.md) own disk
quotas, shared copy identity, expiry and restart persistence, and
[per-lease command sandboxes](2026-09-16-per-lease-command-sandboxes.md) own unit
reuse and reset reporting. Runtime leases and command containment remain separate
from stored-copy lifetime.

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
subprocess. A lease admits at most `maxExecutionsPerSession` commands; the worker
refuses the next one with a signed `EXECUTION_CAPACITY` before it runs, and the
application reports `EXECUTION_REFUSED` with `CAPACITY` without recording an
interruption and closes the exhausted lease. The next call attaches the same disk
copy under a fresh lease.

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
The worker grants read access to the trusted bootstrap directory, the bridge
lock, state and response paths, and, only while initializing or installing a
baseline, the snapshot or baseline bundle; working, home and bridge request
directories receive their own write grants. It never grants read access to the
whole session directory: a read grant for the whole session parent can install
a later read-only bind over the writable children. Keeping test runtime state
under `/tmp` can conceal that error because the invocation's temporary-write
grant also covers it. The native probe therefore places runtime state under
`/run`, with the production supervisor umask, while synthetic source and tools
remain in a separate disposable directory. No special relaxation for `/run`
or broader grant to the session parent is part of this decision.

### Sandbox toolkit

The worker host installs a content-processing toolkit from its configured
Debian-compatible package repositories with `executor-service/install-sandbox-tools.sh`,
a root-operated host step separate from application deployment and sandbox
execution; the [worker reference](../../executor-service/README.md#runtime) lists
the packages. Distribution packages install once under the existing system paths
and resolve their native-library dependencies through the package manager. Every
copy reads the same root-owned tools; there is no per-copy installation, network
access or package manager inside the sandbox. The prepared tool directory supplies
`awk` directly from `/usr/bin/mawk` and the `python` alias for `/usr/bin/python3`,
because the host's `/etc/alternatives` indirection lies outside the sandbox read
allowlist. Provisioning does not discard copies, modify content or require an
application or worker restart. Office libraries read and write DOCX/XLSX without
a full renderer, PDF text extraction performs no OCR, and audio/video processing,
OCR engines and large scientific environments are outside the toolkit.

### MCP and Java integration

Spring AI supplies WebMVC Streamable HTTP at `/mcp`. Browser sessions do not
authenticate it; the [identity boundary](2026-09-06-workspace-identity-http.md)
verifies workspace Bearer credentials and [MCP OAuth](2026-09-11-mcp-oauth.md) issues
them. SDK transports bind their current principal and workspace, but do not own
copy identity or lifetime. Authorized clients of one account and workspace share
the default copy within the same reading scope. Tools use the
[repository authoring services](2026-09-05-repository-authoring-foundations.md) for
authoritative files, exact images, idempotent uploads and revision-checked saves.

[MCP request admission removal](2026-09-15-mcp-request-admission-removal.md) owns the
request bounds: all request bodies share a 128 KiB bound and the bounded streaming
envelope preflight, and image import and response owners retain the shared image
budget through actual processing and response writes. SDK transport exceptions
become JSON-RPC error envelopes without exception internals; HTTP status, headers
and cookies remain intact. Errors without an identifiable request omit `id`, and
ordinary RPC responses and event streams retain SDK handling.

The `executor` Java module implements `RepositoryExecutor` through the signed local
worker client. It validates root-owned protected socket paths, root peer
credentials and a private Ed25519 PKCS8 signing key. Snapshot exports contain exact
authoritative Git history without credentials, source object inode sharing or
alternates. Authoritative file reads never use a command-modified execution copy.
A lease keeps its original export commit as provenance. An omitted commit selects
the copy's current acknowledged baseline, which only an acknowledged save, move or
explicit synchronization advances ([mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md));
a requested commit that differs from that baseline is refused.

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

Vendoring the toolkit's packages into a second filesystem tree would require
maintaining Python and dynamic-library search paths beside the host interpreter;
the already readable system paths avoid that duplicate installation logic.
Package versions follow the host distribution and its security updates rather
than Python packages installed independently with pip.

The supervisor is security-sensitive privileged code. Its executable, toolchain,
configuration, verification key, and generated records must be root-owned.
Signed requests do not excuse unsafe filesystem cleanup or arbitrary systemd
properties. Resource examples require real corpus sizing before production.

## Verification and acceptance

The Gradle `executorServiceTests` task runs the worker's tests, with real Ed25519
signatures, on Linux with zero skipped or aborted tests; Windows uses a pinned
Linux container. `WorkerSocketTests`, `WorkerAnswerTests` and
`WorkerFrameContractTests` pin the Java adapter, and `McpProtocolIntegrationIT` the
HTTP MCP entrance. These gates do not rerun the privileged SRT probe. The
privileged runs are recorded in the [native evidence](../../executor-service/evidence.jsonl),
the [combined Java/worker evidence](../../executor-native/evidence/2026-09-05-combined.json),
the [real-corpus worker sample](../../acceptance/evidence/2026-09-15-real-corpus-worker-timing.json)
(a 173 MB retained bundle and twenty reused executions on the installed worker),
and the [sandbox toolkit run](../../acceptance/evidence/2026-09-15-sandbox-toolkit.json).

Related: [repository-native retrieval and sandboxed execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)
owns the composable execution and isolation contract, and execution copies never
become write authority under [remote repository authority](2026-09-01-remote-repository-authority.md).
This local socket and systemd topology does not implement remote workers; the
[optional serverless profile](../rejected/2026-09-01-optional-serverless-deployment-profile.md)
that proposed them is rejected.
