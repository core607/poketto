# Logical Media Index and Atomic Authoring

Date: 2026-09-09
Status: Implemented

## Format and ownership

`.poketto/assets.json` maps logical repository paths to immutable workspace originals. It is strict UTF-8 JSON with exactly `version: 1` and a `files` object. Each file value contains exactly `assetId` (canonical UUID), `revision` (lowercase SHA-256), `mediaType` and `size` in bytes. The index is bounded to 1 MiB and 10,000 files. Unknown fields, duplicate JSON keys, trailing documents, coerced values and normalized path collisions are rejected. Path comparison follows the repository rule: NFC normalization, uppercase then lowercase with the root locale, then NFC again; this includes expanded collisions such as `straße` and `strasse`. Encoding prefers readable indentation and falls back to compact JSON when needed to stay within the byte bound.

Media paths share the repository namespace. They cannot replace Markdown, reserved metadata, Git files, symlinks, submodules or their descendants. Directory listing combines committed Git entries with indexed paths at one pinned commit, including directories that contain only managed media. It reads the bounded index rather than original bytes. Missing originals are not represented by empty placeholders. A text read of an indexed path reports `MANAGED_MEDIA` and does not claim expected absence. Invalid or colliding indexes fail explicitly instead of presenting an incomplete directory.

## CodeAct discovery

`poketto media list` reads a frozen snapshot of the full-read session's local
index, including unsaved imports, or an explicitly selected authorized historical
index. Public sessions list only their host-retained projection mapping, ignoring
mutable local index metadata. Results expose logical paths, media types and sizes;
they never disclose source mappings or original identities to public callers.

Pagination has both entry and serialized-byte bounds. The returned index version
lets a caller reject changed indexes between pages of the same prefix and history
selection rather than silently skip or repeat entries. A different selection starts
at offset zero. Discovery does not fetch original bytes and does not assert their
availability. Current authorization is checked before delivery, and withdrawal
invalidates the public session. The [worker reference](../../executor-service/README.md)
owns CLI arguments and limits; the [CodeAct plan](../proposed/2026-09-09-codeact-workspaces.md)
retains session admission and remaining agent-tool cutover work.

## Writes and authorization

The shared repository writer accepts the index with ordinary text changes in one revision-checked patch. Before advancing the remote ref it validates the complete logical namespace and resolves each indexed identity against the same workspace's original metadata. A missing or foreign original, metadata mismatch, collision, conflict or authorization failure prevents the entire patch from committing. Blob upload acknowledgement remains separate from Git acknowledgement.

Changed media mappings require `PUBLISH` when either their prior or candidate path is eligible for publication. Index edits cannot hide a publication change behind the reserved metadata path. Repairing an invalid prior index also requires `PUBLISH`, because its previous media scope cannot be trusted. While an index is invalid, existing non-public text can still be edited in place without changing paths or metadata. Structural and publication changes require index repair. The index contains no independent visibility flag; publication policy remains authoritative.

## Related decisions and limits

[Authoring foundations](2026-09-05-repository-authoring-foundations.md) retain original storage, durability and atomic Git ownership. [Directory navigation](2026-09-08-repository-directory-navigation.md) retains commit-pinned pagination and filesystem discovery. This record extends both without introducing a second asset database. [Indexed media delivery](2026-09-09-indexed-media-delivery.md) owns logical-path images, galleries and original-file HTTP transfers; inline managed image references remain supported. [Atomic content moves](../implemented/2026-09-09-atomic-content-moves.md) records the shared service, browser picker and CLI integration. The public-root cutover, CodeAct materialization and portable exports remain assigned by [CodeAct content and media](../proposed/2026-09-09-codeact-content-and-media.md). No related note is archived or rejected.

Directory reads with media scan at most 100,000 Git entries to reject namespace overlays. Original metadata validation does not hash all original bytes on every text save; uploads acknowledge durable immutable objects and delivery verifies bytes. Index state is not proof of current read or publication authority.

`RepositoryMediaIndexTests`, `RepositoryDirectoryReaderTests`, `RepositoryPatchServiceTests` and `ManagedAssetDeliveryTests` cover strict parsing, namespace collisions, pinned virtual directories, explicit media reads, index/text atomicity, publication authorization and workspace metadata resolution. Native storage replay and real Spring/PostgreSQL integration remain required before release.
