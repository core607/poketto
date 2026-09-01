# Repository Asset Blob Store

Date: 2026-09-01
Status: Proposed

## Problem

Under [repository-native publishing](2026-09-01-repository-native-publishing-and-assets.md), an article may keep images beside its Markdown and refer to them with ordinary relative links. Remote Git remains the authority for those bytes, but serving every image through a Git object read couples public delivery to repository-cache layout and makes the optional serverless request layer depend on ephemeral disk.

Poketto needs one asset-serving contract whose implementation can use local disk on the primary single-server deployment and object storage on the optional serverless deployment. The store must remain a derived cache, not a second image authority.

## Proposal

### Derived asset contract

`BlobStore` stores immutable repository asset bytes under a workspace-scoped opaque asset identity derived from the verified Git blob at one resolved commit. Only an internal repository reader can create the verified asset value accepted by materialization. Callers cannot select a filesystem path, bucket key, repository address, or provider object directly.

On an asset request, Poketto resolves the relative path and publishing policy at the referring Markdown commit, verifies a regular supported file and its bounds, then materializes the exact Git blob into `BlobStore` if it is absent. Materialization is idempotent and safe under concurrent requests. A cache hit does not read or copy the Git blob again; a miss may stream the verified bytes to the response while publishing the same bytes to the store.

The renderer emits an immutable workspace-scoped Poketto asset URL. Source Markdown keeps its relative link and never receives a local path, object-storage URL, signed URL, deployment hostname, or generated content hash. A changed Git image produces a different asset identity; old objects never masquerade as the new commit's image.

The BlobStore is rebuildable from remote Git. Losing it affects latency and availability while objects are rematerialized, but it does not lose acknowledged content, image history, or publishing state. It is excluded from authoritative backup and restore requirements.

### Single-server adapter

The primary single-server profile uses a local filesystem adapter below an operator-configured data directory. The adapter creates files through bounded streaming, a private temporary file, verification, and atomic publication. It rejects traversal, symlinks, non-regular files, cross-workspace keys, partial files, and content that exceeds configured byte or media bounds.

The local layout is an adapter detail. Business modules, public URLs, cache identities, and tests do not encode it. Restart preserves warm objects when the data directory remains, while deleting the directory and restarting rebuilds every requested asset from remote Git.

### Serverless adapter

The optional serverless profile implements the same contract with OSS-compatible object storage. Request instances keep no authoritative or required shared asset bytes on ephemeral disk. Provider bucket names, object keys, credentials, signed URLs, and retry behavior remain inside the adapter and deployment configuration.

The object-storage adapter is delivered with the complete optional serverless profile because it requires a real isolated namespace, credentials, lifecycle behavior, and production-like network evidence. Its objects remain derived from remote Git just like the local adapter's files.

## Implementation scope and dependencies

The first implementation depends on [remote repository authority](2026-09-01-remote-repository-authority.md). It adds the `BlobStore` port, verified repository-asset materialization service, local filesystem adapter, immutable delivery contract, bounds, workspace isolation, concurrency control, and focused tests. Publishing policy later decides whether a verified object may enter a public response; cache existence never grants visibility.

The OSS adapter is intentionally deferred to [the optional serverless deployment profile](2026-09-01-optional-serverless-deployment-profile.md), where the required external infrastructure can be accepted as one runnable topology. The first implementation excludes direct uploads, agent binary writes, image transformation, thumbnails, OCR, CDN configuration, garbage collection, and provider-specific code in business modules.

## Alternatives considered

**Serve every image directly from Git.** This avoids a cache abstraction but repeatedly couples public delivery to Git object access and leaves serverless instances without a shared warm asset layer.

**Move image authority from Git into BlobStore.** Markdown and sibling images would stop being a portable repository unit, and backup, writes, and revision history would span two authorities. The store remains a disposable materialization of Git bytes.

**Rewrite Markdown to an object-storage URL.** This leaks deployment topology into user content, breaks rendering outside Poketto, and requires another commit whenever storage configuration changes.

**Require OSS on the single-server profile.** It would make the simplest supported deployment depend on another external service without improving correctness. The port keeps the behavior identical while local disk supplies the cache.

## Acceptance

- A cold request for an authorized relative repository image stores and returns the exact Git bytes. A warm request uses `BlobStore` without recopying the unchanged Git object.
- Concurrent cold requests publish one complete immutable object and never expose a temporary or partial file.
- A new Git blob for the same path receives a new asset identity. A stale asset URL cannot return bytes from the new commit.
- Public delivery rejects private or excluded assets, traversal, symlinks, submodules, unsupported media, oversized files, malformed identities, and cross-workspace access.
- Deleting the local BlobStore and restarting rebuilds the requested images from remote Git without changing source Markdown or acknowledged repository state.
- The local adapter survives restart with exact bytes, keeps provider and filesystem paths out of public contracts, and passes focused filesystem and repository integration tests.
- The optional serverless profile proves the same behavioral suite against a real isolated OSS-compatible namespace before that adapter is described as implemented.
- `./gradlew repoCheck` and `git diff --check` pass.

## Risks

The first request after cache loss pays a remote Git read and local or network write. Bounded streaming, immutable caching, and warm-transfer measurements keep the cost visible without making it a correctness dependency.

Derived objects accumulate when repository images change. Garbage collection is deferred until real retention data exists; operators must receive cache-size visibility and a safe whole-cache rebuild procedure in the first implementation.

Incorrect authorization before materialization could preserve private bytes in a shared store. Workspace-scoped identities, policy checks before lookup or write, and tests that treat cache existence as non-authoritative are mandatory.
