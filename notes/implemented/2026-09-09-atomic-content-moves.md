# Atomic Content Moves

Date: 2026-09-09
Status: Implemented

Partially superseded by: [Native repository consumer](2026-11-05-native-repository-consumer.md).

## Scope

The [CodeAct content plan](2026-09-09-codeact-content-and-media.md) requires one move operation for the browser and CLI. A directory can contain Git files and indexed media, while other documents refer to either kind. Separate rename, index and link commits can leave broken references or expose only part of a selected folder.

## Shared service

`RepositoryMoveService` accepts exact source and destination paths at an expected authority commit. A folder destination must be absent; moves do not merge directories, replace workspace roots or metadata, follow symlinks, traverse submodules, or convert Markdown into another file type. Collision checks include normalized case, directory ancestors, Git entries and logical media. Directory moves reuse existing Git object identities and relocate media index entries without loading original bytes. Selecting one file never implicitly relocates its dependencies.

`RepositoryMoveService.plan` prepares host-owned local preconditions without
advancing authority. It shares the move planner, identifies every relocation and
repaired text file, and fingerprints affected Git originals. Indexed originals
carry their immutable content hashes and may be absent from the sandbox; preparing
the plan does not fetch them from media storage. Plans retain their workspace and
exact authority base, bound affected paths to 16,384, replacement text to 32 MiB,
each Git original to 128 MiB and aggregate Git fingerprint reads to the workspace
byte limit. The native consumer retains this plan for the explicit CLI acceptance below.

The service derives its requirements from every affected path, as [member content permissions](2026-09-12-member-content-permissions.md) define: a publishable source, destination, repaired reference or media entry requires `PUBLISH`, and any other one requires `READ_PRIVATE` and `WRITE_PRIVATE`. A plan that would disclose private media-index entries also requires `READ_PRIVATE`. It uses the same remote-ref writer as text patches, including atomic compare-and-swap, current publication authorization, withdrawal before an uncertain outcome, reconciliation, attribution and separate snapshot-installation acknowledgement. A move that creates a public-to-private reference fails before authority advances. Invalid publication or media configuration must be repaired first.

`RepositoryMoveService.recover` accepts the original request and host-retained
commit bytes after an uncertain acknowledgement. It recomputes the candidate from
the original immutable base under current authorization and requires the exact
tree, parent and attribution. If remote history already contains that commit,
recovery acknowledges it without another push and retains later remote changes.
Otherwise only the same commit can be retried from the unchanged base; a changed
destination or diverged remote is rejected. CLI recovery reconciles this retained
commit before advancing host baselines.

Reference repair scans bounded Markdown at the same immutable base, resolves existing file/media paths and unambiguous document routes, and computes relative destinations with preserved fragments. CommonMark source spans identify links, images and reference definitions. Only destination tokens change; frontmatter, labels, titles, code, HTML and line endings remain intact. Missing or external targets remain authored text. Unsupported repair syntax fails the whole operation instead of silently rewriting prose. The existing document, node, reference and workspace byte bounds apply. Copying objects avoids the 64-file/4-MiB external text-patch limit while retaining the repository tree bound.

## Delivery and evidence

The session-authenticated `/api/admin/workspaces/{workspaceId}/repository/directory` endpoint supplies commit-pinned immediate Git and indexed-media entries, and `…/repository/move` applies the same domain operation with CSRF protection. Browser moves of existing files use this entrance instead of delete/create saves. Unsaved edits block moves, and a rejected destination is never retried automatically. A stale authority version closes the picker and clears the unmodified selection, so the user selects again against current state. The service consumes the current publication policy rather than changing it.

`RepositoryPatchServiceTests`, `RepositoryPatchIntegrationIT` and `RepositoryAdminIntegrationIT` pin moves through real Git fixtures, PostgreSQL, scoped keys and authenticated HTTP; the required Linux storage replay includes the shared writer suite, and native executor and client evidence below covers the CLI.

## CLI integration

The CLI moves existing authoritative content, matching the browser entrance.
Selected files and affected backlinks must match their host-owned baselines;
unsaved affected edits require saving or resolving them before preparation.
A missing on-demand media file is not a deletion. Materialized originals must
match their immutable content hashes. Local media-index planning retains unrelated
unsaved entries and refuses a changed selected mapping or destination collision.

The host retains the request, affected paths, repaired references and immutable
candidate at one authority commit. Sandbox Git refs never supply its preconditions.
Preparation records original bytes and stages candidate files under the copy's
plan directory without replacing working files. File-count, byte, inode and
account-storage limits also apply to retained originals and staging. An interrupted
preparation resumes the same request and candidate.

The caller applies the candidates and explicitly accepts each expected hash or
absence. A hash must match both the retained candidate and observed working file;
all paths in one acceptance batch are checked before the batch is retained. After
every path has been accepted, the host requests the retained atomic remote move.
Each acceptance observes its own paths at that time; it does not freeze the tree
or promise a simultaneous local snapshot. Untracked files outside the plan remain
the caller's responsibility and are never implicitly published or removed.

The host records the exact remote attempt before publication. An uncertain result
blocks new writes and plan release until recovery reconciles that same candidate.
Recovery preserves later remote changes and never derives a different move from
new local edits. A confirmed remote result advances affected host baselines and
the [local Git baseline](2026-09-15-mutable-working-copy-baselines.md); unrelated
file baselines keep their prior revisions. Working files remain as edited even
when the remote move conflicts.

Retained originals and candidates can be recovered to an absent output path.
Explicit release removes host backups after any submitted move and baseline work
are reconciled; it leaves local files and accepted baselines intact. The
[native consumer decision](2026-11-05-native-repository-consumer.md) records why
the signed worker's frozen installation and rollback contract was retired.

`NativeMovesTests`, `NativeMoveRecoveryTests`, `NativeMoveCheckpointTests` and
`NativeNonTextMoveTests` cover this consumer protocol. The
[native workspace probe](../../executor-native/overlay_copy_probe.py) verifies
candidate staging and acceptance with the released daemon. The
[earlier CLI run](../../executor-native/evidence/2026-09-10-cli-moves.json) and
[recovery run](../../executor-native/evidence/2026-09-10-cli-move-recovery.json)
remain historical evidence for signed-worker local installation.

## Alternatives and related records

Sending delete/create text pairs through the external patch API limits ordinary folders to small text batches and cannot move arbitrary binary objects. Letting the agent edit the index and every backlink separately leaves repair incomplete and creates multiple authorization commit points. One host-prepared candidate avoids both problems without adding another persistence engine.

[Authoring foundations](2026-09-05-repository-authoring-foundations.md) retain the atomic writer and authorization contract; [logical media](2026-09-09-logical-media-index.md) retains index ownership and original validation; [directory navigation](2026-09-08-repository-directory-navigation.md) retains the listing contract for the destination picker. The [CodeAct workspace record](2026-09-09-codeact-workspaces.md) owns session capture and baseline advancement.
