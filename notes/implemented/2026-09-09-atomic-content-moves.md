# Atomic Content Moves

Date: 2026-09-09
Partially superseded by: [Sandcrab adoption](../proposed/2026-10-26-sandcrab-sandbox-service.md) for native CLI local preparation and verification.
Status: Implemented

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
byte limit. Native CLI preparation retains originals and candidates without installing working files.

The service derives its requirements from every affected path, as [member content permissions](2026-09-12-member-content-permissions.md) define: a publishable source, destination, repaired reference or media entry requires `PUBLISH`, and any other one requires `READ_PRIVATE` and `WRITE_PRIVATE`. A plan that would disclose private media-index entries also requires `READ_PRIVATE`. It uses the same remote-ref writer as text patches, including atomic compare-and-swap, current publication authorization, withdrawal before an uncertain outcome, reconciliation, attribution and separate snapshot-installation acknowledgement. A move that creates a public-to-private reference fails before authority advances. Invalid publication or media configuration must be repaired first.

`RepositoryMoveService.recover` accepts the original request and host-retained
commit bytes after an uncertain acknowledgement. It recomputes the candidate from
the original immutable base under current authorization and requires the exact
tree, parent and attribution. If remote history already contains that commit,
recovery acknowledges it without another push and retains later remote changes.
Otherwise only the same commit can be retried from the unchanged base; a changed
destination or diverged remote is rejected. Prepared writes also compare the saved
change-set digest before checkpointing or pushing; current local bytes cannot alter
that candidate.

Reference repair scans bounded Markdown at the same immutable base, resolves existing file/media paths and unambiguous document routes, and computes relative destinations with preserved fragments. CommonMark source spans identify links, images and reference definitions. Only destination tokens change; frontmatter, labels, titles, code, HTML and line endings remain intact. Missing or external targets remain authored text. Unsupported repair syntax fails the whole operation instead of silently rewriting prose. The existing document, node, reference and workspace byte bounds apply. Copying objects avoids the 64-file/4-MiB external text-patch limit while retaining the repository tree bound.

## Delivery and evidence

The session-authenticated `/api/admin/workspaces/{workspaceId}/repository/directory` endpoint supplies commit-pinned immediate Git and indexed-media entries, and `…/repository/move` applies the same domain operation with CSRF protection. Browser moves of existing files use this entrance instead of delete/create saves. Unsaved edits block moves, and a rejected destination is never retried automatically. A stale authority version closes the picker and clears the unmodified selection, so the user selects again against current state. The service consumes the current publication policy rather than changing it.

`RepositoryPatchServiceTests`, `RepositoryPatchIntegrationIT` and `RepositoryAdminIntegrationIT` pin moves through real Git fixtures, PostgreSQL, scoped keys and authenticated HTTP; the required Linux storage replay includes the shared writer suite, and native executor and client evidence below covers the CLI.

## CLI preparation and acceptance

The native CLI prepares authoritative content and repaired references at one
immutable base. Affected local files must match that plan; dirty files, changed
selected media mappings and occupied targets refuse preparation while preserving
working files. Missing on-demand originals remain absent. Unrelated local media
mappings survive in the local candidate but stay outside the remote write.

The account journal retains the immutable definition, private originals and
candidates before exposing staging paths. Users apply candidates with ordinary
tools and explicitly accept path hashes or absence. Each path is verified at its
own acceptance; there is no final simultaneous snapshot. Later edits remain
unsaved. This contract avoids requiring atomic worktree replacement from the
independent sandbox service or mistaking a non-replacing upload for compare-and-swap.

Only complete acceptance requests the saved domain move. Durable intent and the
exact commit candidate precede the remote side effect. Current authorization,
original-base CAS and the saved change-set digest still apply. An uncertain outcome
retains its original attempt for recovery; it cannot be replaced, replayed as a new
move or released. Confirmed remote results precede bounded per-path baseline
retention. Recovery finishes those baselines without reading later working bytes;
only confirmed paths advance, and the global base advances after all are retained.
Explicit plan release drops backups only after uncertainty and pending baseline
retention are resolved. Working files are never automatically installed.

[Usage](../../docs/usage.md#mcp-and-isolated-execution) owns commands and response
fields. `NativeMovesTests`, `NativeMoveRecoveryTests` and `NativeRepositoryPlanTests`
pin the host workflow and durable transitions; the Linux storage replay covers
media-index merging and real subprocess CLI routing. Earlier [native CLI](../../executor-native/evidence/2026-09-10-cli-moves.json),
[recovery](../../executor-native/evidence/2026-09-10-cli-move-recovery.json) and
[client](../../acceptance/clients/evidence/2026-09-10-cli-moves.json) evidence covers
the signed backend, not native Sandcrab consumer acceptance.

## Alternatives and related records

Sending delete/create text pairs through the external patch API limits ordinary folders to small text batches and cannot move arbitrary binary objects. Letting the agent edit the index and every backlink separately leaves repair incomplete and creates multiple authorization commit points. One host-prepared candidate avoids both problems without adding another persistence engine.

[Authoring foundations](2026-09-05-repository-authoring-foundations.md) retain the atomic writer and authorization contract; [logical media](2026-09-09-logical-media-index.md) retains index ownership and original validation; [directory navigation](2026-09-08-repository-directory-navigation.md) retains the listing contract for the destination picker. The [CodeAct workspace record](2026-09-09-codeact-workspaces.md) owns session capture and baseline advancement.
