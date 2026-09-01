# Local Content-Addressed Blob Storage

Date: 2026-09-01
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) place image bytes outside Git, address them by SHA-256, scope them to a workspace, and retain them physically in v1. The implemented [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md) reserves a blob namespace for each workspace, but no blob module, storage contract, or local implementation exists.

The primary deployment is one cloud server. It needs a production blob store that works with the existing data directory before an optional object-storage adapter is justified. Leaving the local mechanism implicit would make uploads, delivery, backup, and a future remote adapter invent incompatible path, atomicity, and error rules.

## Proposal

### Contract and identity

- Add a `BlobStore` port owned by a dedicated application module. Every operation requires an authorized `WorkspaceId`; no caller supplies a filesystem path, bucket name, namespace, or storage key.
- A blob identity is the SHA-256 digest of its exact bytes, encoded as an opaque lowercase value. The same bytes may have the same digest in two workspaces, but authorization, external references, errors, logs, and backup manifests remain workspace-scoped.
- A narrow administrative `prepare(WorkspaceId)` operation creates or validates one workspace namespace idempotently and reports readiness to provisioning. It accepts no path or provider identifier and grants no content access.
- The content contract provides bounded `put`, `stat`, and `open` operations. It does not expose directory listing, mutable overwrite, rename, cross-workspace copy, or physical deletion.
- Documents and APIs refer to a blob by its digest rather than its storage path. A later image feature owns filenames, captions, media validation, and document attachment semantics; those concerns do not change byte identity.

### Local filesystem adapter

- The production local adapter stores blobs below the configured absolute data directory in a namespace derived only from `WorkspaceId` and the digest. Its internal fan-out layout is private to the adapter.
- `put` streams into a bounded temporary file in the target filesystem while calculating SHA-256 and byte count. It publishes the completed object atomically only after size limits and digest calculation succeed.
- Uploading bytes that already exist in the same workspace is idempotent success. The adapter verifies the existing object's size and bytes before accepting it; a mismatch for the same digest is a repository-integrity failure rather than an overwrite.
- A failed, cancelled, or oversized upload never exposes a partial object. Startup or the next scoped operation removes abandoned temporary files without following symlinks or caller-controlled paths.
- `open` verifies that the resolved object is a regular file inside the workspace namespace. Missing and unauthorized blobs use the same external semantics, and no error reveals another workspace's matching digest or local path.

### Durability and lifecycle

An acknowledged `put` survives process restart and application-image replacement. The implementation defines the flush and atomic-move behavior required for that acknowledgement on supported local filesystems and fails startup when the configured data directory cannot provide it.

Blobs are immutable and v1 never deletes them physically. Workspace deletion remains unavailable under the workspace decision until retention, cancellation, recovery, and backup behavior are accepted together. The [off-host backup proposal](2026-08-27-off-host-backup-and-restore.md) consumes `BlobStore` through a workspace-scoped export or manifest contract rather than scanning private adapter paths.

## Implementation scope and dependencies

The first implementation adds the port, namespace preparation, local filesystem adapter, configuration binding, value types, resource limits, crash-safe publication and cleanup, cross-workspace isolation tests, and backup-facing enumeration below an internal administrative capability. It updates the requirements counterparts and operating documentation to describe the built local store.

It does not add an upload page, MCP tool, image transformation, thumbnailing, CDN, object storage, deduplication across workspace authorization boundaries, garbage collection, or a physical delete operation. The optional [serverless deployment profile](2026-09-01-optional-serverless-deployment-profile.md) owns the object-storage adapter when a real service and credentials are available.

## Alternatives considered

**Store blob bytes in Git.** This would give images repository history, but large immutable binaries inflate clone and execution-snapshot cost and make ordinary document history carry unrelated media transfer.

**Store blob bytes in PostgreSQL.** Transactions would simplify metadata coordination, but large byte streams would enlarge database backup and replication while the primary deployment already has a durable data directory.

**Implement only an S3-compatible adapter.** That would make an external service mandatory for the primary single-server deployment and leave the selected local topology without an implementation.

**Expose local paths to callers.** This removes one lookup, but it leaks deployment topology, permits path confusion, and prevents a configuration-only switch to another adapter.

## Acceptance

- A production local profile stores, restarts, and reads exact blob bytes by workspace and digest without exposing an adapter path through application contracts.
- Repeated and concurrent namespace preparation for one `WorkspaceId` is idempotent. Readiness is reported only after the adapter can safely publish objects in that namespace.
- Repeating the same upload is idempotent. Concurrent identical uploads publish one valid immutable object, and concurrent different uploads remain independent.
- Oversized, cancelled, truncated, and injected-crash uploads leave no readable partial object. Recovery removes only adapter-owned temporary files.
- Two workspaces may store the same digest without sharing authorization, URLs, errors, counts, or backup records. Caller-selected paths, workspace names, and storage keys never select an object.
- Tests cover symlinks, traversal, non-regular files, digest mismatch, exact size limits, multimegabyte streaming, process restart, and cleanup after simulated failure.
- Backup integration can enumerate and verify one authorized workspace without reading another workspace or depending on the filesystem layout.
- The relevant automated tests, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

Immutable retention grows disk use monotonically. Per-object and invocation limits bound immediate abuse; workspace quota enforcement remains a consumer-layer prerequisite before public uploads. Garbage collection requires a separate reachability and recovery decision before deletion is safe.

Filesystem durability differs by platform. Atomic rename alone does not prove bytes reached stable storage, so the implementation must name and test its acknowledgement assumptions on every supported production filesystem.

A local adapter keeps the primary deployment stateful by design. That is acceptable for the single-server profile; optional serverless deployment replaces the adapter without changing blob identity or application authorization.
