# Asset BlobStore and Git Synchronization

Date: 2026-09-01
Status: Proposed

## Problem

Poketto must accept images through more than one writing path. An owner may upload an image through an application entrance without adding it to Git, while an existing repository may already contain images beside Markdown. Requiring every image to originate in Git would make repository layout the only write model. Treating every Git image as an independent authority would instead create unresolved conflicts between repository bytes and uploaded bytes.

The primary single-server deployment needs durable local image storage. The optional serverless deployment needs durable OSS-compatible storage. Both need the same asset identity, rendering, synchronization, move, and deletion semantics even when a Git copy exists.

## Proposal

### Asset authority and optional Git source

- `BlobStore` is the durable byte authority for managed images. The single-server adapter uses local filesystem storage; the optional serverless adapter uses OSS-compatible object storage.
- An `AssetCatalog` record in PostgreSQL owns the workspace-scoped asset identity, active content hash, media metadata, lifecycle state, and optional Git binding. Blob bytes are immutable; replacing an image writes a new object and advances the catalog record with an expected asset revision.
- A managed-only asset has no Git binding. A Git-backed asset records a canonical repository path, last synchronized Git blob revision, resolved commit, and last synchronized asset version. The Git file is an import source and optional synchronized copy, not the authority for the active managed bytes.
- The same content hash may back several records inside one workspace, but authorization, references, lifecycle, errors, and provider keys remain workspace-scoped. No external contract exposes a filesystem path, bucket key, repository address, provider object, or signed storage URL.

Git images become managed only after bounded validation and successful import into BlobStore. Discovering a repository file does not by itself acknowledge an asset write. Once a binding exists, rendering reads the active catalog version from BlobStore; repository synchronization state is reported separately.

### References and repository discovery

Markdown may refer to either a managed asset identity or an ordinary repository-relative image path.

- A managed reference resolves directly through `AssetCatalog` and BlobStore. It works for assets uploaded without a Git copy.
- A relative repository reference resolves the path at the document's commit. Poketto validates publishing scope, file type, size, and path safety, then finds or creates the Git binding and imports the exact blob into BlobStore. Later requests use the active managed asset rather than reading Git for every response.
- The renderer emits one immutable workspace-scoped Poketto asset URL for the resolved asset version. Source Markdown never receives a deployment hostname, local path, bucket URL, or signed provider URL.

A direct owner push may add, change, move, or remove a bound Git source. Reconciliation compares the observed Git blob revision and active asset revision with their last synchronized pair. If only Git bytes changed, Poketto may import the new blob as the next asset version. If only the managed asset changed and synchronization was requested, Poketto may produce a candidate Git update. If both changed, it records a conflict and changes neither active version nor remote `main` automatically.

A missing Git source never deletes the managed asset automatically. It marks the binding `SOURCE_MISSING` and keeps the last confirmed BlobStore version until an owner detaches, retargets, or retires it. Finding the same Git blob at a new path may produce a move candidate, but Poketto does not infer a move from content equality because the owner may have copied the file intentionally.

### Multi-write synchronization

Asset mutations are explicit application operations rather than side effects of `repo_exec`:

- `put_asset` creates a managed-only asset or replaces an existing asset version through bounded streaming. A caller may request synchronization to a canonical Git path. Replacing a Git-backed asset requires `SYNC_GIT`, `KEEP_GIT_SOURCE`, or `DETACH_GIT_SOURCE`; it never creates silent divergence.
- `bind_asset` imports a Git path into a new or existing managed asset and records the synchronized pair.
- `move_asset` changes a Git source binding; managed-only assets have no storage path to move. It detaches and rebinds or moves the Git source and updates known repository-relative references in the same candidate commit.
- `inspect_asset_removal` returns whether the asset is managed-only or Git-backed, every known public or private reference, whether immutable bytes are shared, the allowed dispositions, and an opaque inspection revision.
- `delete_asset` carries that inspection revision, current asset revision, required Git revisions, and an explicit disposition. A browser obtains the inspection and asks the owner to choose; an agent never guesses the choice from free-form confirmation text.

A Git-backed delete supports these dispositions:

- `KEEP_GIT_SOURCE` retires the managed asset and binding while leaving the Git file unchanged. An eligible repository reference may import it again later; the response must say so.
- `DELETE_GIT_SOURCE` removes the bound Git path with expected-ref and expected-blob protection. The same candidate commit removes or rewrites known relative references, or validation rejects the operation as leaving a public broken reference.
- `MOVE_GIT_SOURCE` moves the file to a validated target and rewrites known relative references in the same candidate commit. The binding advances only after remote Git confirms that commit.

`KEEP_GIT_SOURCE` on replacement keeps the binding and records that Git is behind the active managed version. `DETACH_GIT_SOURCE` removes the synchronization relationship without changing the file. Either state remains visible until another explicit operation changes it.

Managed-only deletion retires the catalog record without touching Git. Physical object deletion occurs only after no live asset version, Markdown reference, Git synchronization operation, or retention rule can reach the bytes. A Git source deletion does not prove that BlobStore bytes are unreferenced, and a BlobStore deletion never implies a Git change.

### Cross-store acknowledgement and recovery

PostgreSQL, BlobStore, and remote Git cannot share one transaction. Every multi-write mutation therefore has a durable workspace-scoped operation record with the requested disposition, expected asset revision, expected Git ref and blob revision when applicable, immutable candidate object identity, progress, and sanitized failure state.

The operation writes and verifies immutable BlobStore bytes before exposing a new asset version. When Git synchronization is requested, it then builds and validates a candidate commit and advances remote `main` through `RepositoryAuthority` compare-and-swap. The catalog switches the active asset version and synchronized pair only after every required target is confirmed. A timeout after a remote update is reconciled by reading the ref; retry resumes the same operation and never performs a blind second write.

A managed-only mutation is acknowledged after BlobStore verification and the catalog transaction. A Git-synchronized mutation is acknowledged only after BlobStore, remote Git, and the catalog agree on the recorded pair. Until then, reads use the previous confirmed asset version and diagnostics expose pending or conflicted synchronization without leaking paths to unauthorized callers.

### Deployment adapters

The primary single-server profile stores authoritative BlobStore objects below an operator-configured data directory. The adapter uses bounded streaming, a private temporary file, verification, and atomic publication. Restart preserves assets; loss of this directory loses managed-only images and therefore requires backup and restore.

The optional serverless profile implements the same contract with durable OSS-compatible object storage. Request instances keep no required asset bytes on ephemeral disk. Provider bucket names, credentials, object keys, retry behavior, and lifecycle rules remain inside the adapter and deployment configuration.

## Implementation scope and dependencies

The first implementation depends on [remote repository authority](2026-09-01-remote-repository-authority.md). It adds `AssetCatalog`, the `BlobStore` port, the local filesystem adapter, managed asset identity and revisions, Git bindings, import and reconciliation, durable multi-write operations, move and delete dispositions, immutable delivery, bounds, workspace isolation, recovery, and focused storage and Git integration tests.

[Repository-native publishing and assets](2026-09-01-repository-native-publishing-and-assets.md) consumes these contracts for relative-link resolution, public policy, and structured agent entrances. The OSS adapter remains inside [the optional serverless deployment profile](2026-09-01-optional-serverless-deployment-profile.md), where real external infrastructure can prove the same contract.

The first implementation excludes image transformation, thumbnails, OCR, automatic captions, CDN configuration, cross-workspace physical deduplication, Git LFS, and arbitrary binary writes through `repo_exec`.

## Alternatives considered

**Make Git the image authority.** Existing repository images would be portable, but uploads, generated images, and administration writes would all need a Git path and commit. This recreates a single write model after repository-native scanning removed the need for one canonical file layout.

**Make Git and BlobStore coequal authorities.** A byte mismatch would have no deterministic winner, and delete or move could silently discard one side. BlobStore owns the active managed bytes; a Git binding carries explicit synchronization state.

**Keep Git images outside the asset catalog and stream them directly.** This avoids import state but gives repository and uploaded images different URLs, metadata, access checks, lifecycle operations, and serverless behavior.

**Rewrite every relative Markdown link to a managed asset identity.** This simplifies later rendering but mutates adopted repositories merely to read them and breaks ordinary Git viewers. Relative links remain valid and resolve through a binding.

**Hide cross-store writes behind best-effort background copying.** This makes a successful response ambiguous and lets Git and the catalog drift silently. Durable operations expose pending, conflict, and failure states and define the acknowledgement point.

## Acceptance

- A bounded upload creates and renders a managed-only asset without writing Git. A repository-relative image imports exact bytes, records its Git binding, and thereafter renders the confirmed BlobStore version.
- Replacing a managed-only asset advances its opaque asset revision and immutable URL. Stale expected revisions conflict without changing the active version.
- A one-sided direct Git change imports safely. Concurrent Git and managed changes produce a visible conflict and preserve the last confirmed version on both sides.
- A direct Git deletion preserves the managed asset as `SOURCE_MISSING`. Content-equal files at new paths appear only as move candidates and never rebind automatically.
- Git-synchronized create, replace, move, and delete survive failure after each storage boundary. Retry resumes one durable operation; an ambiguous remote response is reconciled without duplicate commits or blind ref updates.
- Deleting a managed-only asset never changes Git. Deleting a Git-backed asset requires a fresh removal inspection plus `KEEP_GIT_SOURCE`, `DELETE_GIT_SOURCE`, or `MOVE_GIT_SOURCE`; tests prove each disposition, reference rewrite, capability check, stale-inspection conflict, and repository conflict path.
- Physical deletion refuses bytes still reachable by a live version, repository reference, pending operation, or retention rule. Shared immutable bytes are never removed for one record while another still uses them.
- Public delivery rejects private or excluded assets, traversal, symlinks, submodules, unsupported media, oversized files, malformed identities, stale revisions, and cross-workspace access.
- Deleting the single-server BlobStore demonstrates that managed-only images require restore. Git-backed assets may be reimported, but their recoverability never weakens backup requirements for the complete asset set.
- The optional serverless profile passes the same behavioral suite against a real isolated OSS-compatible namespace before that adapter is described as implemented.
- `./gradlew repoCheck` and `git diff --check` pass.

## Risks

Multi-write synchronization adds durable state and reconciliation to a previously single-authority write path. Keeping immutable objects, exact revisions, and an explicit last synchronized pair prevents guesswork but does not remove operational failures; pending and conflict states must be visible.

Repository moves and Markdown rewrites can touch several paths in one commit. Bounds on affected files, total bytes, and operation duration are required so one asset action cannot become an unbounded repository rewrite.

Managed-only assets are not recoverable from Git. Local and OSS BlobStores are authoritative production data and require independent backup, retention, and restore drills.
