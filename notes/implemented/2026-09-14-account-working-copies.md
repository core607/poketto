# Account Working Copies

Date: 2026-09-14

## Problem

An MCP transport is not a durable editing identity. A connector may initialize a new transport for each tool call while retaining the same authorization. Transport-owned copies then reject continuation, duplicate repository exports and consume idle capacity. Keeping those copies in tmpfs couples stored work to scarce execution memory.

A commit does not identify a copy either: a fresh export at the same revision looks identical, so a command could run in an unintended copy and report success. Closing a copy on a command timeout would delete unrelated unsaved files even after the command's processes are contained.

## Decision

The executor uses one default working copy per Poketto account and content workspace. OAuth grants authorize individual operations; they do not own separate copies. A transport ending only detaches that transport. Reauthorization, another authorized client and an application restart can continue the account's existing work without silently replacing its files or baseline. Explicit creation must not replace an existing default copy.

Read scope remains a security boundary. A public-only grant must never mount the account's full repository or obtain its private output, artifacts, file names or history. Restricted execution uses an independently isolated public projection. Each command and host operation uses the requesting principal's current grants, not the principal that originally opened the copy. Grant revocation stops affected execution and access without deleting the account's work or granting another client additional permissions. An explicit copy ID selects its existing authorized reading scope even after a grant gains private-read permission; permission expansion does not silently replace or expand a public copy.

Copies use disk storage with kernel-enforced limits. Initial configurable limits are 4 GiB per copy and 32 GiB for the complete executor storage pool, including exports, recovery data and artifacts. Byte limits are ceilings rather than per-copy reservations; inode usage is also bounded. Refuse growth or new admission when capacity is exhausted, preserving existing acknowledged work. Size polling is observation, not enforcement. Keep command temporary storage and command CPU/memory limits separate from stored-copy capacity.

Storage requires a dedicated, size-bounded XFS filesystem with enforced project quotas. An installation on an existing filesystem can use one preallocated loopback image for the aggregate bound; no host-root remount is required. Each copy receives an inherited block and inode hard quota before command access. This reserves aggregate pool capacity, not the full individual allowance for every copy. The worker refuses an absent, oversized or non-enforcing mount. Project identifiers remain allocated across restarts and are not recycled onto another retained copy.

Allocation publishes a copy directory only after its protected identity is durable. Under the project allocation lock, startup and subsequent allocations remove unpublished staging left by an interrupted creator. This cleanup never sweeps published copies by guessing whether application metadata still exists. Runtime lease cleanup can run even when the copy mount fails validation.

Commands must retain SRT's noninitial user namespace. Linux rejects project-ID and project-inheritance changes from that namespace, including changes requested by a file's owner. Ordinary unprivileged host processes do not provide this restriction. Native acceptance therefore exercises quota attribute changes from inside the actual command sandbox, alongside byte and inode exhaustion.

A copy expires after seven days without successful authorized use. Read and write use renews that deadline. Return its expiry to callers. A transport disconnect, command timeout or ordinary service restart does not expire the copy. Explicit owner-authorized disposal and stated expiry remove local work without reversing remote saves. Persist ownership, expiry, the original commit, the installed Git baseline ([mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md)), per-file baselines and pending remote-write receipts with the files. Reconcile interrupted commands and uncertain saves before claiming completion or retrying writes. No off-host backup guarantee is introduced.

Commands are serialized for a shared copy, including host bridge writes and disposal. Use bounded waiting and distinct capacity/busy reasons. Copy files remain after execution resources stop. Capacity pressure releases an idle lease's runtime resources but never deletes copy files: unsaved work is never evicted to admit another client. Admission bounds are global; there is no per-account quota or per-account fair scheduling. [Per-lease command sandboxes](2026-09-16-per-lease-command-sandboxes.md) records the measurement and owns runtime reuse, idle cleanup and explicit sandbox resets.

A command timeout keeps the copy once the worker confirms that the command's control group is empty. The result reports the timeout and a nonzero exit code under the same copy ID; earlier edits and the command's partial work remain. Resource exhaustion, cancellation, revocation and unconfirmed containment close the lease, and the disk copy remains for a later lease. A copy whose initialization never completed is discarded by the next `new` admission; a request naming its ID is refused with `RECOVERY_REQUIRED`.

Application continuity uses only the account journal and disk copy. The optional Java checkpoint coordinator, duplicate metadata/original-store lifecycle and obsolete fixtures are removed. Immutable original-file framing, current authorization checks, host save-state receipts, process containment and bounded record publication remain. The MCP interface identifies a copy by ID and returns expiry/interruption information; it does not require a generation counter or an explicit resume switch.

## Copy identity and disposal

Every `repo_exec` request carries `expectedCopyId`. The literal `new` opens the account's default copy for the grant's reading scope and creates it only when absent; it never replaces an existing copy. Any other value must equal the copy's opaque UUID, returned as `copyId` by every command result and by `poketto status`. The ID is separate from the Git commit, the worker lease and the MCP transport. The executor compares it before any export, attachment or command. A mismatch exports nothing, never runs the command, and returns `SESSION_REPLACED` with `executed: false`: `MISSING_COPY` with `newCopyAllowed: true` when the account holds no such copy, or `DIFFERENT_COPY` with the account's current `copyId` when it holds another. Only a rejection known to precede execution reports `executed: false`; a lost worker response is `EXECUTION_UNCONFIRMED`. Clients keep the ID across reconnects and never treat `new` as a retry after an uncertain result. An omitted or malformed ID is invalid input. A copy ID is not a bearer credential: every use requires current authorization for the same account, workspace and reading scope.

`repo_discard` takes the exact copy ID and requires current `EXECUTE_REPOSITORY` permission. Lookup binds the account, workspace and copy ID in either reading scope, so lost content-read permission does not prevent cleanup, and no content is returned. A busy copy refuses disposal. The worker must confirm containment before the application removes the binding and releases admission capacity; an unconfirmed close keeps both and never admits an overlapping replacement. `DISCARDED` and `ABSENT` confirm that the addressed copy is gone; repeating the call is safe, and disposal never reverses remote Git commits. A later `new` creates another copy.

Executor metrics count sessions, operations and admission refusals with fixed reasons. They carry no identity, workspace, command, file path or content labels. The [worker reference](../../executor-service/README.md#working-copy-identity) lists their names.

## Editing and synchronization

The `poketto edit` CLI operation replaces an exact, nonempty old text with new text in an existing file. Reject missing or ambiguous matches without changing the file. New-file creation must not overwrite an existing path. Keep ordinary shell execution; these edit checks do not claim to prevent deliberate whole-file overwrites or every semantic conflict. Do not require a workspace revision or separate read/write mode on every command.

For full-read copies, `poketto status` checks remote main against the last confirmed save/sync base and reports whether it matches, differs, or could not be checked. It preserves local status on remote failure and never updates working files or baselines. The head comparison does not certify freshness of every file: selected operations retain separate per-file baselines. [Whole-workspace synchronization](2026-09-15-workspace-synchronization.md) is explicit and reuses those baselines and JGit three-way merge. Preserve local work, surface conflicting versions for the agent to resolve, and retain final save conflict checks. Never silently update working files in the background. Keep credentials and authoritative remote writes outside the sandbox; local checkpoints do not publish content. Public copies report their synthetic projection commit without exposing or comparing the private authority commit; every command still requires the current public-projection check.

## Verification and operational delivery

`WorkerSocketTests`, `McpCopyIdentityTests`, `McpDiscardTests` and `McpProtocolIntegrationIT` pin identity refusal, disposal and admission; `AccountCopyStoreNativeProbe`, `EphemeralLifecycleNativeProbe` and `executor-service/disk_pool_probe.py` pin quotas, timeout retention, containment and restart recovery on real XFS and SRT. The [authenticated HTTP evidence](../../acceptance/clients/evidence/2026-09-14-account-copies.json) records one copy shared across new transports and independently issued grants.

The worker's disk-copy handshake is required, so application and worker installation are coordinated; the [worker reference](../../executor-service/README.md#account-copy-storage) owns pool installation and default limits.

## Alternatives

Transport-owned and grant-owned copies multiply storage for one account. Account ownership deliberately shares local edits between authorized clients, so concurrent chats of one account work in the same files; serialized commands and exact-text edits are the accepted protection. Whole-workspace version protocols impose coordination on unrelated edits; exact-text editing provides a smaller operation-level check. Merely raising tmpfs session counts does not decouple stored work from execution memory.

A response-only `fresh` flag arrives after the command has already run in the wrong copy, and comparing commits misses a fresh export at the same revision; only a precondition checked before execution prevents that. Letting `new` replace a live copy would turn an admission request into implicit data deletion.

Capturing each command's work into archived checkpoints and restoring them into fresh tmpfs copies under per-transport writer generations was built first. Attaching the same quota-bounded disk copy made capture, restore and generation handshakes unnecessary, and they were removed. Packing work only at normal shutdown would miss crashes, untracked binary files and host-write recovery state.

Reattaching a copy onto current remote history would change the user's baseline and create an implicit merge. A reattached copy keeps its own baseline; background reset or pull can invalidate unsaved work and is excluded, so synchronization stays explicit.

A dynamic user per command would still need a persistent ownership and UID-reuse design for retained files; enabling `DynamicUser` independently for each command is insufficient. Commands run as one fixed execution account.

Related: [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns remote authority, selected saves and the CLI; the [local execution supervisor](2026-09-05-local-execution-supervisor.md) and [public execution projection](2026-09-09-public-execution-projection.md) keep their isolation boundaries.
