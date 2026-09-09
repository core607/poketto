# Atomic Content Moves

Date: 2026-09-09
Status: Proposed

## Scope

The [CodeAct content plan](2026-09-09-codeact-content-and-media.md) requires one move operation for the browser and CLI. A directory can contain Git files and indexed media, while other documents refer to either kind. Separate rename, index and link commits can leave broken references or expose only part of a selected folder.

## Prepared service

`RepositoryMoveService` accepts exact source and destination paths at an expected authority commit. A folder destination must be absent; moves do not merge directories, replace workspace roots or metadata, follow symlinks, traverse submodules, or convert Markdown into another file type. Collision checks include normalized case, directory ancestors, Git entries and logical media. Directory moves reuse existing Git object identities and relocate media index entries without loading original bytes. Selecting one file never implicitly relocates its dependencies.

The service requires `READ_PRIVATE` and `WRITE_PRIVATE` before accessing repository state. It uses the same remote-ref writer as text patches, including atomic compare-and-swap, current publication authorization, withdrawal before an uncertain outcome, reconciliation, attribution and separate snapshot-installation acknowledgement. Public changes and repaired inbound references require `PUBLISH`. A move that creates a public-to-private reference fails before authority advances. Invalid publication or media configuration must be repaired first.

Reference repair scans bounded Markdown at the same immutable base, resolves existing file/media paths and unambiguous document routes, and computes relative destinations with preserved fragments. CommonMark source spans identify links, images and reference definitions. Only destination tokens change; frontmatter, labels, titles, code, HTML and line endings remain intact. Missing or external targets remain authored text. Unsupported repair syntax fails the whole operation instead of silently rewriting prose. The existing document, node, reference and workspace byte bounds apply. Copying objects avoids the 64-file/4-MiB external text-patch limit while retaining the repository tree bound.

## Delivery and evidence

The domain implementation and shared-writer refactor are available. The session-authenticated `/api/admin/repository/directory` endpoint supplies commit-pinned immediate Git and indexed-media entries, and `/api/admin/repository/move` applies the same domain operation with CSRF protection. The browser presents a lazy directory tree and a destination picker for files and folders, including renaming and one new child directory. Browser moves of existing files use this entrance instead of delete/create saves. Unsaved edits block moves; rejected destinations remain editable without automatic retries. A stale authority version closes the picker and clears the unmodified selection while refreshing the directory, so the user selects again against current state. A successful move reloads the selected document and directory state, including repaired references. CLI session integration and its real client acceptance remain required before this proposal is implemented. Public-root format conversion belongs to its coordinated migration; this service consumes the current publication policy rather than changing it.

Real Git fixtures cover a folder exceeding the external patch's file limit, exact binary object reuse, logical media relocation, inbound/outbound reference repair, preserved source, stale bases, collisions and publication refusal without partial writes. The PostgreSQL integration path exercises the actual bean, scoped key permissions, owner publication, snapshot replacement and key revocation, plus authenticated HTTP listing and moves with CSRF enforcement. The required Linux storage replay includes the shared writer suite. Browser acceptance through the real Spring, PostgreSQL, Next.js and gateway services demonstrates cancellation with focus restoration, collision rejection, destination correction, a successful folder move and the repaired inbound document link. CLI acceptance remains outstanding.

## Alternatives and related records

[Browser recording and source provenance](https://github.com/core607/poketto/blob/2c46c5798fcd8fde41660c28b058a23ba2c3e64f/README.md) retain the isolated real-service acceptance separately from product history. The recording covers an actual concurrent save as well as a rejected destination; mocked HTTP component tests do not substitute for it.

Sending delete/create text pairs through the external patch API limits ordinary folders to small text batches and cannot move arbitrary binary objects. Letting the agent edit the index and every backlink separately leaves repair incomplete and creates multiple authorization commit points. One host-prepared candidate avoids both problems without adding another persistence engine.

[Authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md) retain the atomic writer and authorization contract; [logical media](../implemented/2026-09-09-logical-media-index.md) retains index ownership and original validation; [directory navigation](../implemented/2026-09-08-repository-directory-navigation.md) retains the listing contract for the destination picker. The [CodeAct workspace proposal](2026-09-09-codeact-workspaces.md) owns session capture and baseline advancement. These records remain active during the wider CodeAct cutover.
