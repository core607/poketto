# Repository-Native Publishing and Images

Date: 2026-09-01
Status: Proposed

## Problem

The implemented [content foundation](../implemented/2026-08-26-content-foundation.md) recognizes only strict Poketto documents below `documents/`, and the [requirements](../implemented/2026-08-25-requirements-and-architecture.md) require per-document publication metadata. Those contracts suit content created through a structured editor, but they do not adopt an existing personal repository containing Markdown and images in ordinary nested folders.

A repository-native service should not require the owner to relocate every note, add Poketto frontmatter to every file, hand-write every sibling image reference, or mark each article public individually. It must also preserve the ownership difference between repository files and images uploaded through Poketto.

## Proposal

### Repository discovery

Poketto scans regular UTF-8 Markdown files throughout an authorized committed tree except Git internals, reserved Poketto state, and excluded paths. The `documents/` directory is no longer special. Paths are canonical repository-relative values; symlinks, submodules, traversal, ambiguous normalized paths, and non-regular files are never content.

Optional frontmatter augments a file with title, date, tags, summary, route, and other structured fields. A file without frontmatter remains readable: its first heading or filename supplies a title, repository history supplies fallback dates where needed, and missing metadata produces diagnostics rather than rejection. Malformed frontmatter or an unsafe route excludes that file from structured public results without hiding unrelated valid content.

A directory with an eligible `index.md` owns the folder route. Other Markdown files use deterministic path-derived routes unless valid metadata overrides them. Route collisions fail closed and appear in workspace diagnostics.

### Publishing policy and private paths

An unconfigured repository is private. Publishing begins only after the owner commits a valid `.poketto/publishing.yaml` and explicitly enables its policy. The first policy mode is `public-by-default`: eligible Markdown and images reachable from it are public unless a path exclusion applies.

The repository-root `private/` tree is always private under this mode. Configured repository-relative exclusions may protect additional trees. Exclusions use bounded path globs rather than regular expressions, do not accept request-time patterns, and always win over a public document reference or folder gallery. Reserved metadata, Git internals, symlinks, submodules, non-regular files, and paths outside the repository are never public.

Repository images do not become public merely because they exist somewhere in the tree. Public reachability requires an eligible Markdown relative reference or inclusion in the non-recursive folder gallery owned by an eligible public `index.md`. A managed image revision is public only while an eligible public document references it.

A later valid commit may remove a document or image from current publication by moving it into the built-in private tree, adding an exclusion, removing its public reference, or deleting the repository file through Git. Public delivery then denies the no-longer-reachable path or managed revision. This cannot recall copies already delivered to browsers, feeds, crawlers, archives, or third-party caches, so the product still presents publication as practically irreversible.

### Folder pages and repository images

An eligible folder `index.md` renders its Markdown body followed by a gallery of eligible regular image files in the same directory. The gallery is non-recursive and uses deterministic filename order. An image already referenced explicitly by that `index.md` renders at the authored position and is not repeated in the gallery.

This convention lets a Git author publish a folder containing `index.md` and sibling images without writing one Markdown reference per image. It does not guess relationships for other Markdown files or images in child directories. Page-level image-count, individual-byte, cumulative-byte, and execution-time bounds apply before public rendering.

Repository images are owned by Git. Poketto may validate and materialize them through the derived cache defined by [managed assets and repository image materialization](2026-09-01-repository-asset-blob-store.md), but it never changes their paths or bytes. The browser presents them as repository-managed and provides no delete, move, replace, import, export, or synchronization control. Adding an image to an eligible public folder is a publication action; an owner who wants to remove or privatize it changes the repository or publishing exclusions through Git.

### Managed images and rendering

A browser, API client, or trusted agent upload creates an immutable managed image revision in `ManagedBlobStore`. The caller receives an opaque managed identity and revision that a repository write can insert into Markdown. Poketto writes no binary into Git and establishes no relationship with a repository image.

The renderer resolves a managed reference through the workspace-scoped catalog and ManagedBlobStore. It resolves a relative repository reference against the referring file's directory at the same commit and materializes the exact Git blob when needed. Both forms produce immutable workspace-scoped Poketto delivery URLs without writing a deployment hostname, local path, bucket URL, remote repository address, or signed provider URL into Markdown.

One page response pins the repository commit and every managed revision it emits. Revision-pinned managed references and commit-pinned repository paths identify exact bytes without a separate page-snapshot table.

Delivery validates workspace, public reachability, path containment, file type, media signature, size, response bounds, and active-content policy. Managed-image reachability uses a bounded scan of the resolved current public tree with a workspace-and-commit-keyed in-memory cache; it creates no durable PostgreSQL content index. HTML rendering sanitizes active content and applies the public CSP. A document cannot expose a private managed image, excluded repository file, repository metadata, or arbitrary remote resource through an image link.

### Browser and agent authoring

The browser editor offers managed upload and repository browsing as separate sources. Upload returns a managed reference that the editor inserts automatically. Repository browsing enumerates authorized image paths without copying them; preview materializes bounded original bytes on demand, and selecting an image makes the editor calculate and write the relative path. A user never has to type the repository path merely because Poketto preserves it.

`repo_exec` discovers folder structure, reads text, inspects Git history, and runs bounded local analysis inside the SRT workspace. It never writes repository or asset authority. A structured `get_asset` accepts either an exact managed identity and revision or an authorized commit and repository path. It returns bounded multimodal content without giving SRT a repository-authority, ManagedBlobStore, or remote Git credential.

`put_asset` creates a managed image and returns its immutable reference. A separate `repo_patch` applies bounded UTF-8 changes to repository authority with the resolved base commit and expected blob revision or expected absence for every affected path. It requires `WRITE_PRIVATE`; it also requires `PUBLISH` whenever the resulting commit creates or changes currently or newly public content, changes public reachability through an `index.md` gallery or reference, or changes publishing policy. Without `PUBLISH`, every affected path must remain excluded or private before and after the patch. An editor or agent may compose upload and patch operations, but a successful upload does not claim that a conflicting document patch committed. Binary repository writes and modification of repository images remain unavailable.

### Directory shape and performance

The first read of a cold repository fetches only the objects required for the resolved commit and selected content. Later reads reuse a bounded commit-keyed cache. Repository-image materialization is on demand, so connecting or scanning a repository does not copy every image. A folder gallery enumerates metadata first and materializes each image only when a bounded response or lazy client request needs it.

Resource evidence covers a representative nested repository without identifying a private corpus. It records cold and warm repository reads, a folder gallery, managed image upload and delivery, Git-image materialization, and a reused SRT session. The measurements include network bytes, local bytes copied, latency, CPU, memory, and cache storage.

## Implementation scope and dependencies

The first implementation depends on [remote repository authority](2026-09-01-remote-repository-authority.md) and [managed asset storage](2026-09-01-repository-asset-blob-store.md). It adds arbitrary nested Markdown discovery, the repository publishing policy, the built-in private tree, folder pages and bounded sibling galleries, safe relative-image resolution, managed-reference rendering, browser and structured-agent entrances, and the UTF-8 `repo_patch` bridge.

It updates the requirements counterparts and frontend proposal. It reverses the target assumption that every content file lives below `documents/`, every publish is a per-document visibility mutation, every image reference is a managed hash, and repository sibling images require migration. The implemented content and write notes remain the executable baseline until this proposal ships.

The first implementation excludes arbitrary binary writes through `repo_exec` or `repo_patch`, repository-image mutation, managed-to-Git export, Git-to-managed import, image editing, thumbnail generation, OCR as a service, automatic captions, CDN configuration, redirects inferred from history, submodule content, and Git LFS integration.

## Alternatives considered

**Require every repository author to write image links.** Standard Markdown remains available for precise inline placement, but it makes a simple `index.md` plus sibling-image folder unnecessarily laborious. The bounded folder gallery supplies a deterministic repository-native convention.

**Automatically associate every image in the repository.** A repository may contain drafts, private media, unrelated assets, and large binary trees. Only an eligible folder page owns its non-recursive sibling gallery.

**Let Poketto delete repository images from the browser.** That would turn a read-only repository convention into binary write, path, conflict, and lifecycle behavior. Git authors remain responsible for files they add through Git.

**Convert repository images into managed assets.** Conversion would create state for user-owned files and make later direct Git changes ambiguous. Exact commit-and-path reads need only a disposable delivery copy.

**Store every upload in Git.** This would make ordinary clones carry recurring binary history and force the structured upload path into repository layout. Managed storage keeps browser and agent uploads independent from Git binary history.

**Generate a complete Git export of managed images.** A workspace export may later materialize managed references into a portable snapshot, but it is neither publishing nor synchronization and requires a separate product decision.

## Acceptance

- A configured repository discovers eligible nested Markdown outside `documents/`; malformed or excluded files fail locally without hiding unrelated valid content.
- An unconfigured repository is private. Enabling `public-by-default` never publishes the root `private/` tree or a configured exclusion, including through a cross-reference or folder gallery.
- `folder/index.md` renders a bounded non-recursive gallery of eligible sibling images in deterministic order. Explicit inline images are not duplicated, and child-directory or private images are absent.
- A repository image is read and materialized only after an eligible reference, gallery, preview, or structured read selects it. Poketto exposes no operation that changes or deletes the Git file.
- A managed upload writes no Git binary and returns an immutable reference. A document patch can attach it with expected revisions; a patch conflict leaves the uploaded object unreferenced rather than claiming a document commit.
- Removing a managed reference changes only Markdown. Repository images remain read-only, and managed physical cleanup remains delayed behind reachability, retention, and backup holds.
- Public rendering rejects unsafe HTML, traversal, symlinks, submodules, ambiguous routes, unsupported or active media, oversized files, excessive galleries, and links into private or excluded paths.
- `repo_exec` can discover mixed text-and-image folders. `get_asset` returns a bounded managed revision or exact repository blob without base64 command output or sandbox network access.
- `repo_patch` can create or update UTF-8 Markdown with expected-blob and expected-ref protection. `WRITE_PRIVATE` without `PUBLISH` changes only paths that remain private or excluded; creating or changing public content, gallery reachability, references, or publishing policy requires `PUBLISH`. It cannot write binary files, escape allowed paths, or commit sandbox debris.
- Requirements, README counterparts, frontend and retrieval proposals, relevant automated tests, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

Public-by-default folder galleries make repository placement meaningful: adding a sibling image to an eligible public folder publishes it. The built-in private tree, configured exclusions, bounded preview, and clear repository-managed labeling must make that consequence visible without pretending Poketto owns the file.

Arbitrary Markdown has weaker uniform metadata than a canonical schema. Optional parsing and diagnostics preserve readable content, while features that require dates, tags, or stable aliases must define their fallback rather than rejecting the whole repository.

Large folders and repository binary history can make cold reads expensive. Non-recursive discovery, lazy materialization, page bounds, incremental fetch, and session reuse constrain the normal path; measured evidence decides whether thumbnailing or filtered transfer deserves a later proposal.
