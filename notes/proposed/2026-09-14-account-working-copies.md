# Account Working Copies

Date: 2026-09-14

## Problem

An MCP transport is not a durable editing identity. A connector may initialize a new transport for each tool call while retaining the same authorization. Transport-owned copies then reject continuation, duplicate repository exports and consume idle capacity. Keeping those copies in tmpfs couples stored work to scarce execution memory.

## Decision

Use one default working copy per Poketto account and content workspace. OAuth grants authorize individual operations; they do not own separate copies. A transport ending only detaches that transport. Reauthorization, another authorized client and an application restart can continue the account's existing work without silently replacing its files or baseline. Explicit creation must not replace an existing default copy.

Read scope remains a security boundary. A public-only grant must never mount the account's full repository or obtain its private output, artifacts, file names or history. Restricted execution uses an independently isolated public projection. Each command and host operation uses the requesting principal's current grants, not the principal that originally opened the copy. Grant revocation stops affected execution and access without deleting the account's work or granting another client additional permissions. An explicit copy ID selects its existing authorized reading scope even after a grant gains private-read permission; permission expansion does not silently replace or expand a public copy.

Store copies on disk with kernel-enforced limits. Initial configurable limits are 4 GiB per copy and 32 GiB for the complete executor storage pool, including exports, recovery data and artifacts. Byte limits are ceilings rather than per-copy reservations; inode usage is also bounded. Refuse growth or new admission when capacity is exhausted, preserving existing acknowledged work. Size polling is observation, not enforcement. Keep command temporary storage and command CPU/memory limits separate from stored-copy capacity.

Use a dedicated, size-bounded XFS filesystem with enforced project quotas. An installation on an existing filesystem can use one preallocated loopback image for the aggregate bound; no host-root remount is required. Each copy receives an inherited block and inode hard quota before command access. This reserves aggregate pool capacity, not the full individual allowance for every copy. The worker refuses an absent, oversized or non-enforcing mount. Project identifiers remain allocated across restarts and are not recycled onto another retained copy.

Commands must retain SRT's noninitial user namespace. Linux rejects project-ID and project-inheritance changes from that namespace, including changes requested by a file's owner. Ordinary unprivileged host processes do not provide this restriction. Native acceptance therefore exercises quota attribute changes from inside the actual command sandbox, alongside byte and inode exhaustion.

Expire a copy after seven days without successful authorized use. Read and write use renews that deadline. Return its expiry to callers. A transport disconnect, command timeout or ordinary service restart does not expire the copy. Explicit owner-authorized disposal and stated expiry remove local work without reversing remote saves. Persist ownership, expiry, pinned baseline, per-file baselines and pending remote-write receipts with the files. Reconcile interrupted commands and uncertain saves before claiming completion or retrying writes. No off-host backup guarantee is introduced.

Serialize commands for a shared copy, including host bridge writes and disposal. Use bounded waiting and distinct capacity/busy reasons. A stopped command releases execution resources while the files remain. Retain per-command sandbox isolation; runtime reuse is a later measurement-based choice.

Application continuity uses only the account journal and disk copy. Remove the optional Java checkpoint coordinator, duplicate metadata/original-store lifecycle and their obsolete fixtures. Keep immutable original-file framing, current authorization checks, host save-state receipts, process containment and bounded record publication. The MCP interface identifies a copy by ID and returns expiry/interruption information; it does not require a generation counter or an explicit resume switch.

## Editing and synchronization

Add a `poketto edit` CLI operation that replaces an exact, nonempty old text with new text in an existing file. Reject missing or ambiguous matches without changing the file. New-file creation must not overwrite an existing path. Keep ordinary shell execution; these edit checks do not claim to prevent deliberate whole-file overwrites or every semantic conflict. Do not require a workspace revision or separate read/write mode on every command.

Expose remote changes through `poketto status`. Synchronization is explicit and reuses the existing per-file baseline and JGit three-way merge. Preserve local work, surface conflicting versions for the agent to resolve, and retain final save conflict checks. Never silently update working files in the background. Keep credentials and authoritative remote writes outside the sandbox; local checkpoints do not publish content.

## Acceptance and delivery

1. Verify repeated calls and new transports through the available Poketto connector against real authenticated service integration. Two grants of one account continue the same eligible copy; other accounts and restricted scopes cannot acquire its private data or extra capabilities.
2. Verify timeout, cancellation, revocation, explicit disposal, idle expiry and service restart separately. Preserve the original baseline, unsaved text and binary work, and uncertain-save reconciliation where applicable.
3. Exercise actual disk and inode limits on Linux, including aggregate exhaustion. Verify that one full copy cannot consume another copy's allocation or destroy its acknowledged work. Record effective application and worker configuration together.
4. Exercise exact edit success, stale and ambiguous text refusal, non-overwriting creation, remote changes, three-way conflicts and authoritative save readback through the real service path. Verify source and worker agreement and the client tool catalogue.
5. Continue the existing public-site-search, administration filename-search and editor public-page-state worktrees after the execution workflow is usable. Preserve their prior evidence and unfinished edits. External clients unavailable to the operator are not completion gates and must not be reported as tested.

## Alternatives and same-topic audit

Transport-owned and grant-owned copies multiply storage for one account. Account ownership deliberately shares local edits between authorized clients. Whole-workspace version protocols impose coordination on unrelated edits; exact-text editing provides a smaller operation-level check. Merely raising tmpfs session counts does not decouple stored work from execution memory. Background reset or pull can invalidate unsaved work and is excluded.

This proposal supersedes the transport ownership and rejection of default shared copies in [copy identity](../implemented/2026-09-12-executor-copy-identity.md) and [work continuity](2026-09-12-executor-work-continuity.md) when implemented. Both records remain useful for replacement guards, acknowledged-work preservation and uncertain-write boundaries. Retain [CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md) for remote authority, selected saves, CLI and public projection boundaries, and [ephemeral copy lifecycle](../implemented/2026-09-14-ephemeral-copy-lifecycle.md) for command containment. The [multi-user plan](2026-09-11-multiuser-workspaces-and-discovery.md) retains the complete outstanding product scope. Public projection and local-supervisor proposals retain their independent isolation and installation requirements; this record changes their copy ownership and storage target, not those security boundaries.
