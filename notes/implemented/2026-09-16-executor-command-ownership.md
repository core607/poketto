# Executor Command Ownership

Date: 2026-09-16

## Problem

`IsolatedRepositoryExecutor` combined account-copy admission, lease containment,
command I/O, CLI dispatch, repository reconciliation and media transfers in one
2,610-line class. Changing media index handling required navigating the same
owner as cancellation and lease renewal. Its constructor also hid the dependency
boundaries between those operations.

## Decision

Keep admission, execution, renewal, revocation and confirmed lease containment in
`IsolatedRepositoryExecutor`. Extract the in-command operations into package-private
owners within the executor module:

| Owner | Responsibility |
|---|---|
| `ExecutorBridge` | Dispatch CLI requests; handle status, local text, exports and artifacts |
| `SessionRepositoryCommands` | Save, sync, move and recovery; install acknowledged Git baselines |
| `SessionMediaCommands` | Resolve authorized originals, capture imports and update the local media index |
| `SessionFileTransfers` | Capture frozen files and materialize host bytes with bounded chunks and digest checks |
| `SessionWorker` | Shared authorization checks, lease-bound requests and worker acknowledgement validation |
| `ExecutionSession` | The existing shared lease identity, lifecycle latches and working save state |

The executor constructs these collaborators once. They introduce no new public
API, configuration, thread pool, journal or lifecycle state machine. Commands
still acquire the account journal before operating on the shared copy. Worker
request preparation stays under the session monitor; network I/O stays outside
it. Capability checks retain their existing operation boundaries.

Repository reconciliation still records remote outcomes before local installation.
A failed local move installation keeps the acknowledged remote receipt for
recovery. Media import acknowledges its stored original before updating the local
index. Capture release and transfer abort remain in their original `finally`
boundaries. Materialization flushes buffered bytes only after its source succeeds;
closing a failed source must not replay a partially sent chunk.

## Alternatives and consequences

Keeping the single class preserves proximity but makes unrelated dependencies and
failure paths harder to distinguish. Splitting every command into a separate
service would duplicate access to the same worker, transfer rules and save state.
Splitting lifecycle coordination at the same time would change more concurrency
ownership than this cleanup requires. Those alternatives are not adopted.

The lifecycle owner remains over the file-length limit and retains its existing
exemption. The extracted classes are below 600 lines and their methods below 60
code lines. Seven stale or resolved executor method exemptions are removed;
`executeWithBridge` retains its existing exemption. Total source length grows
with explicit constructors and operation boundaries; this is a responsibility
split, not a claim that the execution protocol itself is smaller.

## Verification

The [command-ownership evidence](../../executor-native/evidence/2026-09-16-command-ownership.json)
records eleven passing native modes with cleanup, source digests and per-run
runtime manifests. Compilation, Spotless, Checkstyle and repository checks pass.

Existing socket tests exercise the production adapter constructor and shared
request validation. Focused native modes replay the existing save, synchronization,
media and move scenarios on independent synthetic authorities, including uncertain
save acknowledgement and interrupted local move installation. Each selected
mode has its own account-copy pool and checks containment and cleanup. The probe
checks that an acknowledged save's commit matches the installed copy baseline.
The non-root peer probe expects the current structured admission refusal while
still requiring zero request bytes sent to that peer.

The worker client factory retains production socket and signature checks. The
move-installation probe injects a dropped response before adapter composition,
instead of reflectively replacing a final field after its collaborators exist.

The queued-cancellation socket test uses independent caller threads so both
requests reach the adapter on small hosts; a blocked common-pool worker must not
prevent the test from submitting the cancellation case.

Native execution uses real Java, signed Unix-socket requests, the worker and SRT;
its authentication and Git authority are synthetic. HTTP MCP protocol integration
with PostgreSQL remains a separate check. These fixtures do not establish a new
external-client or production deployment result.

## Same-topic audit

Retain [account working copies](2026-09-14-account-working-copies.md) for storage,
ownership and recovery semantics, and [CodeAct content and media](2026-09-09-codeact-content-and-media.md)
for host-mediated writes and public projection boundaries. Retain
[shared checks](2026-09-12-shared-checks.md) for operation-specific failure codes
and [Java style](2026-09-12-java-style-baseline.md) for the exemption policy.
This change updates their code ownership without reversing those decisions. No
same-topic proposal is superseded, and no active decision is archived.
