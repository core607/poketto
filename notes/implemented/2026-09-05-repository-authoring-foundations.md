# Repository Authoring Foundations

Date: 2026-09-05
Status: Implemented

## Scope

This slice implements repository-native reads, public snapshots, atomic text patches, relational identity, and local immutable image storage for the [phase-one delivery](../proposed/2026-09-05-phase-one-daily-use.md). Rendering, image grants, MCP transport, and the production executor are separate integration work. The broader publishing, membership, asset, frontend, and retrieval proposals remain proposed until their complete acceptance criteria are met.

## Repository and publication

`RepositoryContentReader` reads exact UTF-8 blobs and service-issued revisions from arbitrary paths. Explicit commits must be full lowercase Git object ids reachable from current remote `main`. Missing files return expected absence; existing unreadable files return diagnostics. Structured Markdown accepts optional frontmatter, preserves original source, falls back to a real Markdown heading or filename for title, and uses path-filtered Git history for missing dates. Malformed files and path/route collisions are isolated. Traversal, Git internals, symlinks, and submodules cannot supply structured documents.

The authority's object-only read path fetches history and updates the local ref without a checkout or the legacy `documents/` validation gate. Tree scans cap entries at 100,000, Markdown count at 10,000, and accumulated raw Markdown bytes at 256 MiB. Individual text reads retain the 1 MiB bound. History work caps reachable commits at 100,000 and conservatively charges the reachable history per fallback path against a one-million-visit budget. These limits bound work without claiming selective network transfer.

`PublicContentSnapshots` reads a tree and its `.poketto/publishing.yaml` in one authority operation. Missing or disabled policy produces an empty public snapshot. Invalid policy closes public service immediately; a CLOSED marker is written before validating a changed commit. Offline restoration requires an OPEN marker matching the cache ref and retains the original verification time. Public reads reject expired snapshots, snapshots dated in the future, and unavailable publication state. The configured lifetime cannot exceed one hour.

Publication policy uses the bounded YAML and glob schema in the phase-one record. Top-level YAML nodes are checked before map construction so merge keys cannot bypass unknown- or duplicate-key rejection. Public path eligibility runs before route collision resolution: a private file cannot suppress or reveal a public article by changing its route metadata.

Startup keeps the application and refresh loop running when content is unavailable, with readiness out of service, so authenticated diagnosis can be integrated without requiring a Git repair before the process starts. Public requests never fetch remotely. The public endpoints are `GET /api/public/documents`, `GET /api/public/document?route=...`, and `GET /api/public/tags`; the former UUID document route is absent. Responses carry commit, verification time, expiry, and `no-store`. Public mappers omit raw frontmatter, revisions, private diagnostics, and source repository paths.

Public and authorized private search perform case-sensitive literal matching with tag and date filters. Query length is capped at 200 characters, tag length at 64, pages at 100 results, and snippets at 240 characters. Tag pages are capped at 200 entries. No database content index is present.

## Atomic authoring and identity

`RepositoryPatchService` validates a batch of at most 64 text changes and 4 MiB, with a 1 MiB per-file limit. Every path carries a revision or explicit expected absence and the batch names the exact base commit, including explicit unborn state. An in-memory Git index builds the candidate; untouched objects and file modes must remain identical, including when the index editor would otherwise replace an ancestor or directory implicitly. Moves are checked deletion/creation pairs. Images and non-UTF-8 binary files are not writable through this service.

`AuthService.withAuthorization` holds the workspace row lock through the operation, serializing it with membership and key revocation. Patches require `WRITE_PRIVATE`; policy edits and paths public before or after the change also require `PUBLISH`. A changed raw body is retained exactly rather than canonicalized. Commits contain a fixed service author and an opaque account or API-key UUID trailer.

Remote exact-ref acknowledgement owns success. Concurrent edits conflict. A lost response is reconciled once by reading remote `main`; unreadable authority or failed local recording after verified remote success produces an explicit indeterminate result requiring a fresh read before retry. An acknowledged patch immediately calls the public snapshot installer under the authority lock without another fetch. If installation fails, public state closes and the result still reports the Git commit with `snapshotUpdated=false`; it does not pretend the remote write failed. A database transaction completion error after the external acknowledgement also requires reconciliation.

The auth foundation uses PostgreSQL for one-time initialization, accounts, workspace memberships, invitations, and API keys. `POKETTO_AUTH_INITIALIZATION_TOKEN` protects owner initialization; a blank value disables it. Tokens are random and stored only as digests, with plaintext returned once. Passwords use an upgradeable Spring Security PBKDF2 encoding. A workspace retains an active owner under concurrent changes. Human members have private read/write and publication rights; owners manage membership and keys. AI defaults include only private read/write. Keys never inherit unstored capabilities from their holder.

Authorization rechecks current membership and key state. Suspension and role changes revoke affected keys; revocation events are emitted after commit. Executor subscribers and durable audit presentation are not supplied by this foundation. An event consumer must reconcile stored revocation after restart rather than treating event delivery as the authority.

Browser authentication uses Spring Security server-side sessions with fixation protection, secure HttpOnly SameSite cookies, CSRF tokens, origin checks, logout, and bounded login throttling. The initialization, invitation, membership, and key APIs use the same auth service. Browser repository tree, file, private-search, and atomic-patch endpoints use the shared authorized reader and writer. Authentication bodies are limited to 16 KiB; patch and preview JSON to 6 MiB, with the lower domain text limits still enforced. Multipart uploads allow a 16 MiB file within a 17 MiB request.

## Managed originals and sandbox evidence

`ManagedBlobStore` stores originals outside Git under workspace namespaces. Uploads stream at most 16 MiB plus one detection byte, validate image signatures, structural envelopes and dimension bounds, and return a random asset identity with an exact SHA-256 revision. Supported formats are PNG, JPEG, GIF, and WebP; active media and transformations are excluded. A workspace-scoped operation key makes an identical retry return the same reference; different bytes conflict.

File and directory synchronization, atomic directory publication, a synchronized operation ledger, and OS file locks protect acknowledgement and cross-process retries. Reads verify exact version, digest, metadata and image policy. All acknowledged originals are retained. The store rejects symlinks and unsupported durability primitives; Windows providers lacking directory synchronization report unavailability instead of claiming durability.

The [native SRT probe](../../executor-spike/README.md) establishes Linux filesystem/network isolation, separate PID namespaces, external resource bounds, complete descendant cancellation, source-object independence, and ordinary cleanup on synthetic data. Its checked-in evidence identifies the exact probe. It is not the production socket, lease, revocation, or restart-recovery implementation and does not measure the real content corpus.

## Alternatives and related records

This record replaces the whole-commit rejection and indefinite stale public reads in the [validated snapshot baseline](2026-09-04-validated-content-snapshot.md), while retaining its rationale and cache ownership. File diagnostics permit unrelated articles to remain available; publication-policy failure remains global because authorization cannot be guessed.

The [remote authority decision](2026-09-01-remote-repository-authority.md) remains unchanged. Object-only patches avoid worktree mutation and cleanup as part of new writes. Legacy UUID write code remains an internal transitional implementation and has no new external compatibility endpoint; its removal follows migration of remaining callers and tests.

The same-topic audit retains [publishing](../proposed/2026-09-01-repository-native-publishing-and-assets.md), [retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), [membership](../proposed/2026-08-27-invitation-only-membership.md), and [assets](../proposed/2026-09-01-repository-asset-blob-store.md) as partially fulfilled proposals. [Stock PostgreSQL](2026-09-05-stock-postgresql.md) owns the database-image simplification. No note is archived or rejected by this slice.

## Verification and limits

The integrated Gradle `check` passes unit/module tests, real PostgreSQL integration, deployment scripts, repository validation, formatting, and required native Linux storage replay. `RepositoryPatchIntegrationIT` exercises live key capabilities, actual remote Git acknowledgement, immediate snapshot installation, private/public route isolation, and post-revocation rejection through the real Spring composition.

On Windows, `linuxStorageTest` is a required `check` dependency. It runs the exact owning JUnit storage suite using a pinned Linux JDK image, staged compiled classes and runtime jars, no network, a low-privilege user, and a disposable native disk volume. It fails on zero tests, skips, aborts, or failures. Linux CI executes those tests directly through the normal test task. Local unsupported-platform tests do not substitute for native storage verification.

Final browser, MCP client, deployment, and whole-product acceptance remain required by the phase-one record. These foundation checks do not establish that the installation is ready for daily use.
