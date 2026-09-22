# Account Working Copies

Date: 2026-09-14

## Problem

An MCP transport is not a durable editing identity. A connector may initialize a new transport for each tool call while retaining the same authorization. Transport-owned copies then reject continuation, duplicate repository exports and consume idle capacity. Keeping those copies in tmpfs couples stored work to scarce execution memory.

## Decision

The executor uses one default working copy per Poketto account and content workspace. OAuth grants authorize individual operations; they do not own separate copies. A transport ending only detaches that transport. Reauthorization, another authorized client and an application restart can continue the account's existing work without silently replacing its files or baseline. Explicit creation must not replace an existing default copy.

Read scope remains a security boundary. A public-only grant must never mount the account's full repository or obtain its private output, artifacts, file names or history. Restricted execution uses an independently isolated public projection. Each command and host operation uses the requesting principal's current grants, not the principal that originally opened the copy. Grant revocation stops affected execution and access without deleting the account's work or granting another client additional permissions. An explicit copy ID selects its existing authorized reading scope even after a grant gains private-read permission; permission expansion does not silently replace or expand a public copy.

Copies use disk storage with kernel-enforced limits. Initial configurable limits are 4 GiB per copy and 32 GiB for the complete executor storage pool, including exports, recovery data and artifacts. Byte limits are ceilings rather than per-copy reservations; inode usage is also bounded. Refuse growth or new admission when capacity is exhausted, preserving existing acknowledged work. Size polling is observation, not enforcement. Keep command temporary storage and command CPU/memory limits separate from stored-copy capacity.

Storage requires a dedicated, size-bounded XFS filesystem with enforced project quotas. An installation on an existing filesystem can use one preallocated loopback image for the aggregate bound; no host-root remount is required. Each copy receives an inherited block and inode hard quota before command access. This reserves aggregate pool capacity, not the full individual allowance for every copy. The worker refuses an absent, oversized or non-enforcing mount. Project identifiers remain allocated across restarts and are not recycled onto another retained copy.

Allocation publishes a copy directory only after its protected identity is durable. Under the project allocation lock, startup and subsequent allocations remove unpublished staging left by an interrupted creator. This cleanup never sweeps published copies by guessing whether application metadata still exists. Runtime lease cleanup can run even when the copy mount fails validation.

Commands must retain SRT's noninitial user namespace. Linux rejects project-ID and project-inheritance changes from that namespace, including changes requested by a file's owner. Ordinary unprivileged host processes do not provide this restriction. Native acceptance therefore exercises quota attribute changes from inside the actual command sandbox, alongside byte and inode exhaustion.

A copy expires after seven days without successful authorized use. Read and write use renews that deadline. Return its expiry to callers. A transport disconnect, command timeout or ordinary service restart does not expire the copy. Explicit owner-authorized disposal and stated expiry remove local work without reversing remote saves. Persist ownership, expiry, pinned baseline, per-file baselines and pending remote-write receipts with the files. Reconcile interrupted commands and uncertain saves before claiming completion or retrying writes. No off-host backup guarantee is introduced.

Commands are serialized for a shared copy, including host bridge writes and disposal. Use bounded waiting and distinct capacity/busy reasons. Copy files remain after execution resources stop. [Per-lease command sandboxes](2026-09-16-per-lease-command-sandboxes.md) records the measurement and owns runtime reuse, idle cleanup and explicit sandbox resets.

Application continuity uses only the account journal and disk copy. The optional Java checkpoint coordinator, duplicate metadata/original-store lifecycle and obsolete fixtures are removed. Immutable original-file framing, current authorization checks, host save-state receipts, process containment and bounded record publication remain. The MCP interface identifies a copy by ID and returns expiry/interruption information; it does not require a generation counter or an explicit resume switch.

## Editing and synchronization

The `poketto edit` CLI operation replaces an exact, nonempty old text with new text in an existing file. Reject missing or ambiguous matches without changing the file. New-file creation must not overwrite an existing path. Keep ordinary shell execution; these edit checks do not claim to prevent deliberate whole-file overwrites or every semantic conflict. Do not require a workspace revision or separate read/write mode on every command.

For full-read copies, `poketto status` checks remote main against the last confirmed save/sync base and reports whether it matches, differs, or could not be checked. It preserves local status on remote failure and never updates working files or baselines. The head comparison does not certify freshness of every file: selected operations retain separate per-file baselines. Synchronization is explicit and reuses those baselines and JGit three-way merge. Preserve local work, surface conflicting versions for the agent to resolve, and retain final save conflict checks. Never silently update working files in the background. Keep credentials and authoritative remote writes outside the sandbox; local checkpoints do not publish content. Public copies report their synthetic projection commit without exposing or comparing the private authority commit; every command still requires the current public-projection check.

## Verification and operational delivery

The [authenticated HTTP evidence](../../acceptance/clients/evidence/2026-09-14-account-copies.json) uses real login, independently issued grants, PostgreSQL, JGit and native SRT. Every call creates a new MCP transport. The five scenarios verify shared copy identity, private-scope refusal, timeout retention of text and binary files, selected save with authoritative readback, public-copy scope preservation, grant revocation without deleting account work, and explicit disposal without reversing remote saves. Fixture disk limits are scaled; the evidence is not a production connector claim.

The native account probes cover byte and inode quotas, aggregate exhaustion, process loss, immutable original baselines, acknowledged local work, pending remote-save reconciliation, disposal intent and idle collection. Socket tests retain signature, frame, authorization, containment and bounded admission checks. A failed CLOSE can be retried by the next caller; only confirmed containment releases capacity, and the command itself is never replayed.

Deployments provide the enforced XFS pool before enabling disk execution and configure application metadata, exports and worker copies on that same pool. Application and worker admission defaults are four active leases; stored-copy count and seven-day expiry are independent. The worker's disk-copy handshake is required, so application and worker installation must be coordinated.

The [multi-user plan](2026-09-11-multiuser-workspaces-and-discovery.md) owns final installation acceptance. [Site search](2026-09-14-public-site-search.md), [administration filename search](2026-09-14-administration-filename-search.md) and [editor public-page state](2026-09-14-editor-public-page-state.md) now have their own implementation and real browser evidence. This subsystem record does not mark the broader delivery complete.

## Alternatives and same-topic audit

Transport-owned and grant-owned copies multiply storage for one account. Account ownership deliberately shares local edits between authorized clients. Whole-workspace version protocols impose coordination on unrelated edits; exact-text editing provides a smaller operation-level check. Merely raising tmpfs session counts does not decouple stored work from execution memory. Background reset or pull can invalidate unsaved work and is excluded.

This decision supersedes the transport ownership and rejection of default shared copies in [copy identity](2026-09-12-executor-copy-identity.md) and [work continuity](../rejected/2026-09-12-executor-work-continuity.md). Both records remain useful for replacement guards, acknowledged-work preservation and uncertain-write boundaries. Retain [CodeAct content and media](2026-09-09-codeact-content-and-media.md) for remote authority, selected saves, CLI and public projection boundaries, and [ephemeral copy lifecycle](2026-09-14-ephemeral-copy-lifecycle.md) for command containment. The [multi-user plan](2026-09-11-multiuser-workspaces-and-discovery.md) owns the complete delivered product scope. Public projection and local-supervisor proposals retain their independent isolation and installation requirements; this record changes their copy ownership and storage target, not those security boundaries.
