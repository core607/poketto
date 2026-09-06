# Managed Asset BlobStore and Repository Image Materialization

Date: 2026-09-01
Status: Proposed

The [repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md) record the delivered subset and its remaining integration gaps.

[Phase-one delivery](2026-09-05-phase-one-daily-use.md) implements the local storage profile and retains all acknowledged managed originals. OSS, physical reclamation, and backup-dependent recovery remain outside that delivery; completing the local profile alone does not implement this entire proposal.

## Problem

Poketto must support two image-authoring paths without turning them into a synchronization system. An existing repository may contain ordinary image files beside Markdown. A browser, API client, or trusted agent must also be able to upload an image without adding binary history to Git.

Treating both paths as one mutable asset would require Git bindings, bidirectional synchronization, conflict states, and cross-store deletion rules. Treating every upload as a Git binary would instead make repository history the default media store and increase clone and sandbox costs. The product needs one rendering boundary while preserving the ownership of each source.

## Proposal

### Two image types

Poketto recognizes two disjoint image types:

- A **managed image** is created through a Poketto browser, API, or agent entrance. `ManagedBlobStore` is the durable byte authority. A workspace-scoped PostgreSQL catalog records its identity, immutable revision, content hash, media metadata, and retention state.
- A **repository image** is a regular file in the workspace's remote Git repository. The resolved commit, canonical path, and Git blob revision identify its bytes. Poketto treats the file as repository-owner-managed and may materialize a disposable delivery copy, but it never converts the file into a managed image or records a lifecycle for it.

The types have no binding, synchronization pair, or automatic conversion. Poketto never creates, replaces, moves, or deletes a repository image. A repository owner who adds an image through Git continues to manage that file through Git.

### Managed image references

Managed image bytes are immutable. Uploading changed bytes creates a new opaque revision; it never changes the bytes behind an existing revision. Markdown references the exact managed identity and revision, so a committed document snapshot selects one immutable object without a separate publication manifest.

`put_asset` accepts a bounded image stream, validates it, writes and verifies one immutable object, and returns its managed identity and revision. A workspace-scoped idempotency key makes a lost-response retry return the same revision; reusing that key with different bytes conflicts. The upload is acknowledged independently from a later document edit. A browser or agent then uses the repository write contract to insert the returned reference into Markdown. If that document write conflicts or is abandoned, the unreferenced object remains an ordinary retention candidate rather than a partially synchronized Git write.

Removing a managed image from a page removes its Markdown reference through the repository write contract. It does not synchronously delete bytes. Physical cleanup waits until no current document reference, in-flight write, retention rule, or backup hold can reach the revision. The first implementation exposes no separate destructive asset-library operation.

### Repository image materialization

A repository image enters no catalog merely because a repository is connected or scanned. Poketto reads one only when an authorized document reference, folder gallery, or structured image read selects its exact commit and path.

The reader validates repository containment, publishing scope, file type, media signature, byte bounds, and response bounds before materializing a workspace-scoped cache entry keyed by the Git blob revision. The primary single-server profile uses a bounded local cache. The optional serverless profile may use an OSS-backed shared cache. Either cache is derived: eviction, process replacement, or loss of the entire cache changes no acknowledged content and triggers rematerialization from the authorized Git snapshot.

Repository image caches carry no backup obligation. Cache keys, errors, metrics, and delivery URLs expose neither repository coordinates nor cross-workspace content equality. A changed, moved, or deleted Git file is simply a different repository snapshot; Poketto does not infer lifecycle intent or reconcile it with a managed object.

Repository images are read-only in Poketto's browser and agent asset operations. An eligible folder gallery may display an unreferenced sibling image under [repository-native publishing](2026-09-01-repository-native-publishing-and-assets.md), but Poketto offers no control that pretends to delete the underlying Git file. The owner moves, excludes, replaces, or deletes it through Git.

### Delivery and safety

Both image types use a workspace-scoped immutable Poketto delivery URL. A managed URL identifies its opaque asset revision. A repository-image URL identifies the authorized Git snapshot and blob without exposing the remote address, local cache path, provider object key, or signed storage URL.

Delivery rechecks workspace and public reachability before returning bytes. It rejects traversal, symlinks, submodules, unsupported media, active content that the image policy does not safely serve, malformed identities, oversized files, and cross-workspace access. One invalid repository image fails that image and appears in workspace diagnostics; it does not publish the raw file or make the enclosing repository public.

### Deployment adapters

The primary single-server profile stores authoritative managed objects below an operator-configured data directory. The adapter uses bounded streaming, a private temporary file, verification, and atomic publication. Restart preserves managed images; loss of this directory requires encrypted backup restoration.

The optional serverless profile implements the same managed contract with durable OSS-compatible object storage. Request instances keep no required managed bytes on ephemeral disk. Any OSS namespace used for repository-image materialization remains a separately identifiable derived cache and is excluded from backup and restore.

## Implementation scope and dependencies

The first implementation depends on [remote repository authority](../implemented/2026-09-01-remote-repository-authority.md). It adds the `ManagedBlobStore` port, local filesystem adapter, workspace-scoped managed catalog, immutable upload and delivery, retention-safe cleanup, and the repository-image materialization cache with focused storage, security, and isolation tests.

[Repository-native publishing and images](2026-09-01-repository-native-publishing-and-assets.md) owns relative-link resolution, folder galleries, public reachability, structured reads, and document-reference writes. The authoritative OSS adapter and shared repository-image cache remain inside [the optional serverless deployment profile](2026-09-01-optional-serverless-deployment-profile.md), where real external infrastructure can prove the contracts.

The first implementation excludes image transformation, thumbnails, OCR, automatic captions, CDN configuration, Git LFS, cross-workspace physical deduplication, asset import or export, binary repository writes, and modification or deletion of repository images.

## Alternatives considered

**Synchronize managed and repository images.** A long-lived binding would require a winner for byte mismatches, deletion and move dispositions, and durable cross-store operations. The two image types instead retain separate ownership and meet only at rendering.

**Store every Poketto upload as a regular Git blob.** The repository would be a complete byte archive, but recurring binary history would increase remote storage, cold clone, and SRT materialization costs for every workspace.

**Import every repository image into managed storage.** Connecting a repository would copy unused and private binaries, create lifecycle state the owner did not request, and make later Git changes ambiguous. Repository images remain exact Git inputs with disposable delivery copies.

**Use one mutable managed identity without revision-pinned Markdown.** Replacing an image could change an old document commit without changing the repository. Exact revision references keep one page snapshot reproducible without storing a separate publication manifest.

**Delete repository files through Poketto.** A browser control would cross the ownership boundary and require path mutation, reference rewriting, and conflict behavior for files the owner chose to manage through Git. Poketto keeps repository images read-only.

## Acceptance

- A bounded upload creates an immutable managed revision without writing a Git binary. A lost-response retry with the same idempotency key returns that revision, while different bytes under the same key conflict. An authorized document write can reference the exact result, and a stale repository revision conflicts without changing confirmed state.
- Removing a managed reference changes only the document commit. Bytes remain while another current reference, in-flight write, retention rule, or backup hold reaches them, then become eligible for delayed cleanup.
- Connecting or scanning a repository copies no image. An explicit relative reference, eligible folder gallery, or authorized structured read materializes the exact Git blob on demand.
- Deleting every repository-image cache and restarting rematerializes the same bytes from the same authorized commit. The cache is absent from backup artifacts and authoritative PostgreSQL state.
- Poketto exposes no operation that creates, replaces, moves, deletes, imports, exports, or synchronizes a repository image. Direct Git changes appear only through a later resolved repository snapshot.
- Public delivery rejects private or excluded paths, traversal, symlinks, submodules, unsupported or active media, oversized files, malformed identities, stale revisions, and cross-workspace access.
- Deleting the single-server managed BlobStore demonstrates that managed images require restore, while deleting the repository-image cache demonstrates loss of performance only.
- The optional serverless profile passes the managed storage and derived-cache behavioral suites against a real isolated OSS-compatible namespace before those adapters are described as implemented.
- `./gradlew repoCheck` and `git diff --check` pass.

## Risks

The two image types intentionally have different ownership. Browser copy and diagnostics must identify repository images as read-only without exposing repository coordinates, while ordinary rendering should not make authors understand storage adapters.

Repository folders with large binary histories can still make cold fetches expensive. Bounds, commit-keyed caching, and measured transfer protect normal reads; the owner remains responsible for binaries deliberately committed to Git.

Managed images are not recoverable from Git. Local and OSS managed BlobStores are authoritative production data and require encrypted backup, retention, and restore drills.
