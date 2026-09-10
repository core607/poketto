# Atomic Content Moves

Date: 2026-09-09
Status: Proposed

## Scope

The [CodeAct content plan](2026-09-09-codeact-content-and-media.md) requires one move operation for the browser and CLI. A directory can contain Git files and indexed media, while other documents refer to either kind. Separate rename, index and link commits can leave broken references or expose only part of a selected folder.

## Prepared service

`RepositoryMoveService` accepts exact source and destination paths at an expected authority commit. A folder destination must be absent; moves do not merge directories, replace workspace roots or metadata, follow symlinks, traverse submodules, or convert Markdown into another file type. Collision checks include normalized case, directory ancestors, Git entries and logical media. Directory moves reuse existing Git object identities and relocate media index entries without loading original bytes. Selecting one file never implicitly relocates its dependencies.

`RepositoryMoveService.plan` prepares host-owned local preconditions without
advancing authority. It shares the move planner, identifies every relocation and
repaired text file, and fingerprints affected Git originals. Indexed originals
carry their immutable content hashes and may be absent from the sandbox; preparing
the plan does not fetch them from media storage. Plans retain their workspace and
exact authority base, bound affected paths to 16,384, replacement text to 32 MiB,
each Git original to 128 MiB and aggregate Git fingerprint reads to the workspace
byte limit. Worker staging and installation remain part of the CLI target below.

The service requires `READ_PRIVATE` and `WRITE_PRIVATE` before accessing repository state. It uses the same remote-ref writer as text patches, including atomic compare-and-swap, current publication authorization, withdrawal before an uncertain outcome, reconciliation, attribution and separate snapshot-installation acknowledgement. Public changes and repaired inbound references require `PUBLISH`. A move that creates a public-to-private reference fails before authority advances. Invalid publication or media configuration must be repaired first.

`RepositoryMoveService.recover` accepts the original request and host-retained
commit bytes after an uncertain acknowledgement. It recomputes the candidate from
the original immutable base under current authorization and requires the exact
tree, parent and attribution. If remote history already contains that commit,
recovery acknowledges it without another push and retains later remote changes.
Otherwise only the same commit can be retried from the unchanged base; a changed
destination or diverged remote is rejected. CLI dispatch and local move recovery
remain part of the session integration below.

Reference repair scans bounded Markdown at the same immutable base, resolves existing file/media paths and unambiguous document routes, and computes relative destinations with preserved fragments. CommonMark source spans identify links, images and reference definitions. Only destination tokens change; frontmatter, labels, titles, code, HTML and line endings remain intact. Missing or external targets remain authored text. Unsupported repair syntax fails the whole operation instead of silently rewriting prose. The existing document, node, reference and workspace byte bounds apply. Copying objects avoids the 64-file/4-MiB external text-patch limit while retaining the repository tree bound.

## Delivery and evidence

The domain implementation and shared-writer refactor are available. The session-authenticated `/api/admin/repository/directory` endpoint supplies commit-pinned immediate Git and indexed-media entries, and `/api/admin/repository/move` applies the same domain operation with CSRF protection. The browser presents a lazy directory tree and a destination picker for files and folders, including renaming and one new child directory. Browser moves of existing files use this entrance instead of delete/create saves. Unsaved edits block moves; rejected destinations remain editable without automatic retries. A stale authority version closes the picker and clears the unmodified selection while refreshing the directory, so the user selects again against current state. A successful move reloads the selected document and directory state, including repaired references. CLI session integration and its real client acceptance remain required before this proposal is implemented. Public-root format conversion belongs to its coordinated migration; this service consumes the current publication policy rather than changing it.

Real Git fixtures cover a folder exceeding the external patch's file limit, exact binary object reuse, logical media relocation, inbound/outbound reference repair, preserved source, stale bases, collisions and publication refusal without partial writes. The PostgreSQL integration path exercises the actual bean, scoped key permissions, owner publication, snapshot replacement and key revocation, plus authenticated HTTP listing and moves with CSRF enforcement. The required Linux storage replay includes the shared writer suite. Browser acceptance through the real Spring, PostgreSQL, Next.js and gateway services demonstrates cancellation with focus restoration, collision rejection, destination correction, a successful folder move and the repaired inbound document link. CLI acceptance remains outstanding.

## CLI session integration target

The CLI moves existing authoritative content, matching the browser entrance.
Selected files and affected backlinks must match their host-owned baselines;
unsaved affected edits require saving or resolving them before the move. Unrelated
local edits remain in place. A missing on-demand media file is not a deletion.
Materialized originals can move only after their bytes match the workspace index.
For the local media index, session integration must apply only the planned mapping
changes and retain unrelated unsaved entries. A changed selected mapping or a
collision at the new logical path prevents the move.

The host prepares the affected Git paths, media mappings and repaired references
at one immutable authority commit. Sandbox Git refs never supply the plan or its
preconditions. The worker stages incoming bytes outside the sandbox, checks the
affected local paths without following symlinks, and freezes the command cgroup
for final comparison and installation. Local paths absent from the authority,
including untracked files in a moved directory, must be preserved or cause an
explicit conflict before an overwrite or deletion. File-count, byte, inode and
session lifetime bounds apply to staging as well as the final workspace.

Remote commit acknowledgement and local installation are separate outcomes. A
committed move with incomplete local installation remains pending and blocks new
saves and moves until recovery reconciles the retained move and local state.
Recovery never recomputes a different move from newer local edits. Changed local
bytes remain intact and cause an explicit synchronization conflict. Only affected
file baselines advance after installation; unrelated file baselines retain their
prior revisions. Native executor and actual-client acceptance must cover this
whole path before the CLI integration is delivered.

The prepared `materialize.LocalMove` primitive verifies local fingerprints and
rejects unexpected source entries before moving a file or directory. Directory
renames preserve binary inodes and move only already-materialized originals.
Replacement text and rollback copies stay in the protected lease filesystem.
An installation fault restores the directory and original references before the
caller can unfreeze execution. A rollback failure retains protected evidence and
requires closing the lease. The primitive assumes the caller holds the lease
filesystem lock and freezes the command cgroup; signed worker dispatch and CLI
integration must establish those conditions before using it.

The signed `MOVE_*` dispatch and CLI session coordinator connect these operations.
`IncomingMove` validates a bounded host-generated plan and reserves a protected
receipt before the authority write. Receipts bind a fresh host operation ID and
the full plan hash, so retrying an acknowledged local installation preserves later
edits and a new cyclic move cannot reuse an older completion. Pending moves retain
the original request, plan and uncertain commit separately from local completion.
Actual native and client acceptance remain required before this proposal moves
to implemented status.

## Alternatives and related records

[Browser recording and source provenance](https://github.com/core607/poketto/blob/19a35eb913d169db25735ed1992aeadd868d8bf4/README.md) retain the isolated real-service acceptance separately from product history. The recording covers an actual concurrent save as well as a rejected destination; mocked HTTP component tests do not substitute for it.

Sending delete/create text pairs through the external patch API limits ordinary folders to small text batches and cannot move arbitrary binary objects. Letting the agent edit the index and every backlink separately leaves repair incomplete and creates multiple authorization commit points. One host-prepared candidate avoids both problems without adding another persistence engine.

[Authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md) retain the atomic writer and authorization contract; [logical media](../implemented/2026-09-09-logical-media-index.md) retains index ownership and original validation; [directory navigation](../implemented/2026-09-08-repository-directory-navigation.md) retains the listing contract for the destination picker. The [CodeAct workspace proposal](2026-09-09-codeact-workspaces.md) owns session capture and baseline advancement. These records remain active during the wider CodeAct cutover.
