# Account Working Copies

Date: 2026-09-14

Partially superseded by: [Native repository consumer](2026-11-05-native-repository-consumer.md).

## Problem

An MCP transport is not a durable editing identity. A connector may initialize a new transport for each tool call while retaining the same authorization. Transport-owned copies then reject continuation, duplicate repository exports and consume idle capacity. Keeping those copies in tmpfs couples stored work to scarce execution memory.

A commit does not identify a copy either: a fresh export at the same revision looks identical, so a command could run in an unintended copy and report success. Closing a copy on a command timeout would delete unrelated unsaved files even after the command's processes are contained.

## Decision

The executor uses a default working copy per Poketto account and content workspace, and opens further copies for agents working at the same time ([concurrent agent copies](2026-10-18-concurrent-agent-copies.md)). OAuth grants authorize individual operations; they do not own separate copies. A transport ending only detaches that transport. Reauthorization, another authorized client and an application restart can continue the account's existing work without silently replacing its files or baseline. Explicit creation must not replace an existing default copy.

Read scope remains a security boundary. A public-only grant must never mount the account's full repository or obtain its private output, artifacts, file names or history. Restricted execution uses an independently isolated public projection. Each command and host operation uses the requesting principal's current grants, not the principal that originally opened the copy. Grant revocation stops affected execution and access without deleting the account's work or granting another client additional permissions. An explicit copy ID selects its existing authorized reading scope even after a grant gains private-read permission; permission expansion does not silently replace or expand a public copy.

The [native consumer](2026-11-05-native-repository-consumer.md) stores acknowledged writable-layer archives and journals on a bounded XFS filesystem. Each copy has configured byte and entry ceilings, with separate limits for transfers and the aggregate account pool. A failed capture retains the previous acknowledged journal. Execution memory, CPU and temporary storage have independent sandbox limits.

Imported copies retain quota-enforced native volumes. The daemon owns allocation, project identities and recovery of its storage. Sandboxed commands retain a noninitial user namespace that prevents changing project IDs or inheritance. The [offline import contract](../../executor-service/native-migration.md) owns validation and deferred cases.

A copy expires after seven days without successful authorized use. Read and write use renews that deadline. Return its expiry to callers. A transport disconnect, command timeout or ordinary service restart does not expire the copy. Explicit owner-authorized disposal and stated expiry remove local work without reversing remote saves. Persist ownership, expiry, the original commit, the installed Git baseline ([mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md)), per-file baselines and pending remote-write receipts with the files. Reconcile interrupted commands and uncertain saves before claiming completion or retrying writes. No off-host backup guarantee is introduced.

Commands are serialized for a shared copy, including host calls and disposal. Bounded admission distinguishes capacity from a busy copy. The [native consumer](2026-11-05-native-repository-consumer.md) releases each command's runtime after recording its acknowledged changes. Account copies retain unsaved files until explicit disposal or expiry. Site-wide and per-group command limits bound concurrent work; administrators retain the site-wide bound.

After confirmed containment and a successful writable-layer capture, a timeout returns under the same copy ID with partial work retained. An uncertain close or capture records an interruption and keeps the last acknowledged state. An incompletely initialized copy is refused by exact ID with `RECOVERY_REQUIRED` and can be replaced through a new admission.

The [native backend](../../executor-service/native-repository.md) keeps a verified upper archive with each account's pinned template because session closure releases the live overlay. Imported volumes keep their original binding until disposal or expiry. The copy ID addresses either form across reconnects.

## Copy identity and disposal

Every `repo_exec` request carries `expectedCopyId`. The literal `new` opens the account's default copy for the grant's reading scope, creating it only when absent, or a fork while another agent works in it; it never replaces an existing copy. Any other value must equal the copy's opaque UUID, returned as `copyId` by every command result and by `poketto status`. The ID is separate from the Git commit, the worker lease and the MCP transport. The executor compares it before any export, attachment or command. A mismatch exports nothing, never runs the command, and returns `SESSION_REPLACED` with `executed: false`: `MISSING_COPY` with `newCopyAllowed: true` when the account holds no such copy, or `DIFFERENT_COPY` with the account's current `copyId` when it holds another. Only a rejection known to precede execution reports `executed: false`; a lost worker response is `EXECUTION_UNCONFIRMED`. Clients keep the ID across reconnects and never treat `new` as a retry after an uncertain result. An omitted or malformed ID is invalid input. A copy ID is not a bearer credential: every use requires current authorization for the same account, workspace and reading scope.

`repo_discard` takes the exact copy ID and requires current `EXECUTE_REPOSITORY` permission. Lookup binds the account, workspace and copy ID in either reading scope, so lost content-read permission does not prevent cleanup, and no content is returned. A busy copy refuses disposal. The worker must confirm containment before the application removes the binding and releases admission capacity; an unconfirmed close keeps both and never admits an overlapping replacement. `DISCARDED` and `ABSENT` confirm that the addressed copy is gone; repeating the call is safe, and disposal never reverses remote Git commits. A later `new` creates another copy.

Executor metrics count sessions, operations and admission refusals with fixed reasons. They carry no identity, workspace, command, file path or content labels. The [metrics reference](../../deploy/observability/README.md) lists their names.

## Editing and synchronization

Shell commands edit local files. Selected saves compare each affected path with
its acknowledged baseline and use host-side Git authority. Full-read copies can
request `poketto status --remote` to compare their baseline with remote main.
Preparing synchronization creates candidates for inspection and explicit
acceptance. Credentials stay in the host, and publication changes fence public
copies. The [native command contract](../../executor-service/native-repository.md)
and [public projection](2026-09-09-public-execution-projection.md) own those rules.

## Verification and operational delivery

`NativeRepositoryCopyClaimsTests`, `NativeDurabilityBarriersTests`,
`McpCopyIdentityTests`, `McpDiscardTests` and `McpProtocolIntegrationIT`
pin copy ownership and transitions. The [native workspace probe](../../executor-native/overlay_copy_probe.py)
checks process-loss recovery and disposal on real XFS with the released daemon.
[Authenticated acceptance](../../acceptance/clients/README.md) exercises current
clients, grants, timeouts, conflict handling and disposal through HTTP/MCP.

The [native deployment contract](../../executor-service/native-repository.md)
owns the release pin and independently installed service prerequisites.

## Alternatives

Transport-owned and grant-owned copies multiply storage for one account. The default copy deliberately shares local edits between authorized clients, so chats of one account that start one after another work in the same files; serialized commands and exact-text edits are the accepted protection, and agents that start while it is busy get forks. Whole-workspace version protocols impose coordination on unrelated edits; exact-text editing provides a smaller operation-level check. Merely raising tmpfs session counts does not decouple stored work from execution memory.

A response-only `fresh` flag arrives after the command has already run in the wrong copy, and comparing commits misses a fresh export at the same revision; only a precondition checked before execution prevents that. Letting `new` replace a live copy would turn an admission request into implicit data deletion.

The earlier disk worker attached a quota volume to avoid full-tree capture and per-transport writer generations. The native consumer instead shares immutable commit templates and journals each account copy's changes. This adds capture and restore work while separating stored copies from resident shells. Capturing only at shutdown would omit interrupted work and host-write recovery state.

Reattaching a copy onto current remote history would change the user's baseline and create an implicit merge. A reattached copy keeps its own baseline; background reset or pull can invalidate unsaved work and is excluded, so synchronization stays explicit.

A dynamic user per command would still need a persistent ownership and UID-reuse design for retained files; enabling `DynamicUser` independently for each command is insufficient. Commands run as one fixed execution account.

Related: [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns remote authority, selected saves and the CLI; the [local execution supervisor](2026-09-05-local-execution-supervisor.md) and [public execution projection](2026-09-09-public-execution-projection.md) keep their isolation boundaries.
