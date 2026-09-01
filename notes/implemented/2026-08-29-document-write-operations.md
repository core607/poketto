# Document Write Operations

Date: 2026-08-29
Status: Implemented

## Problem

The [content repository foundation](2026-08-26-content-foundation.md) defines repository bootstrap, the managed document layout, the frontmatter schema, canonical serialization, and content-hash revisions, but nothing in it mutates a repository. The [requirements](2026-08-25-requirements-and-architecture.md) define the write model — machine entrances validate strictly, serialize writes per repository, commit on the caller's behalf, record the caller's identity, acknowledge on successful commit, and return a conflict instead of overwriting on a revision mismatch — without an owning implementation contract.

MCP tools, the admin UI, and projection replay all need the same commit semantics. If each entrance implements its own, the acknowledgement point, conflict behaviour, and audit attribution diverge.

## Decision

### Operations

`DocumentWriteService` exposes four workspace-scoped operations: `create`, `update`, `delete`, and `publish`. Every operation takes an explicit `WorkspaceId` and a `WritePrincipal`, validates its input before taking the repository lock, and commits to the workspace repository's `main`.

`create` and `update` carry the same `DocumentDraft` of path, title, tags, and body. A draft is complete caller-supplied state, so an update replaces every field it carries rather than merging.

- `create` assigns the document UUID, sets `created_at` and `updated_at` to the current UTC instant, serializes canonically, and commits. Every created document is `private`; no create parameter selects `public`.
- `update` takes the document UUID, `expected_revision`, and a draft. It preserves `id`, `created_at`, `published_at`, and visibility. A path change is a move, and a move is an edit: any change to the bytes or the path advances `updated_at` and therefore produces a new revision, so a concurrent move surfaces as a conflict instead of being silently relocated back. An update that changes neither bytes nor path succeeds without a new commit.
- `delete` takes the document UUID and `expected_revision` and removes the document file.
- `publish` takes the document UUID and `expected_revision`, sets visibility to `public`, sets `published_at` on the first publish only, and commits. Publishing an already-public document whose `expected_revision` matches the live revision succeeds without a new commit. A retry after a lost publish acknowledgement carries the pre-publish revision and returns a conflict; the caller re-reads and observes the completed publish. No operation returns a public document to `private`; reverting is a break-glass repository edit followed by explicit reindexing.

`DocumentWriteService` accepts the revision as opaque input and does not define an agent read entrance. [Repository-native retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) proposes the structured `get_file` read that supplies opaque blob revisions from one committed snapshot, and [repository-native publishing and images](../proposed/2026-09-01-repository-native-publishing-and-assets.md) proposes the `repo_patch` bridge that consumes them for repository-native Markdown writes.

### Concurrency and acknowledgement

One in-JVM lock per workspace cache prevents two local operations from mutating the same candidate worktree. Locks and caches are independent across workspaces. They are a local coordination optimization, not the correctness boundary: independent application processes use separate caches, and [remote repository authority](2026-09-01-remote-repository-authority.md) applies an exact expected-ref update so only one candidate can advance a shared base.

The cache is fully machine-owned. Before a write, the authority adapter fetches remote `main`, resets tracked state to that commit, and removes untracked and ignored files. Owners author by pushing the private remote; cache edits are disposable and never block or become content.

The operation reads the resolved remote `main` tree, verifies `expected_revision` against the committed blob hash, applies the change, and builds a candidate commit. A mismatch changes nothing and raises `DocumentConflictException` carrying the live revision so the caller re-reads instead of retrying blind. A missing document raises `DocumentNotFoundException`, distinct from a conflict: after a lost `delete` acknowledgement, a retry reads as already applied. `create` and a path-changing `update` verify that the target path is free after Unicode normalization and case folding, and `create` verifies document-UUID uniqueness, under the same local lock. The first successful write on an unborn remote `main` creates the root commit.

A committed change always carries a later `updated_at`, and therefore a new revision. Canonical serialization advances it when the document's own fields change; the write service advances it for a move, and for a write that rewrites a hand-edited file whose fields already match into canonical form.

Before mutating the candidate worktree, the operation records the paths it will touch in an intent journal inside the cache's git directory. A failed stage or commit resets those paths to the resolved base and removes the journal before the lock releases. A process crash needs no durable local recovery contract: the next operation rematerializes the entire disposable cache from remote `main` before building another candidate.

`DocumentWriteResult` shares the document UUID, the commit that holds the reported state, and the `committed` observation; an operation that finds the repository already in the requested state reports `committed` false and the unchanged remote commit. `create`, `update`, and `publish` add the resulting path and revision; `delete` reports the removed path and carries no revision, because no blob remains. `committed` observes exact-ref remote acknowledgement. A lost response is reconciled by reading remote `main`: equality with the candidate succeeds, a competing commit conflicts, and an unreadable ref is explicitly ambiguous and must not be retried blindly.

### Attribution

Commits use the fixed service author and committer identity `Poketto <poketto@invalid>`, whose domain is reserved and routes nowhere. The acting principal — `WritePrincipal`, a principal type and a stable identifier — is recorded in a `Poketto-Principal` commit trailer, and the commit subject names the operation and document UUID. [Invitation-only membership](../proposed/2026-08-27-invitation-only-membership.md) supplies real account and API-key identities later; these operations accept the principal as data and do not depend on login existing.

Commit metadata must never contain display names, email addresses, credentials, or session tokens: content repositories may be mirrored off-host, so identity in git history stays limited to durable opaque identifiers. Entrances map credentials and sessions to stable principal identifiers before constructing `WritePrincipal`. The value type accepts up to 64 characters of `[A-Za-z0-9._-]` after an alphanumeric first character, making email addresses, display names, and trailer-breaking newlines unrepresentable. This syntax check cannot determine whether an otherwise valid token is secret.

### Capability mapping

These operations do not authorize. Entry points resolve an authorized workspace and capability before invoking them, per the [workspace boundary](2026-08-27-workspace-tenancy.md). The contract entry points must enforce: mutating a private document requires `WRITE_PRIVATE`; `publish`, and every mutation of an already-public document, requires `PUBLISH`. A key holding only `WRITE_PRIVATE` therefore can never change what the public site serves. An entrance checks the target's visibility from its own read, and `expected_revision` closes the race between that check and the write: a publish in between changes the bytes and turns the stale write into a conflict.

### Implemented scope

The implementation covers the four operations, per-cache locking, candidate journaling and rollback, remote compare-and-swap acknowledgement, validation, attribution, and write results, reusing the foundation's canonical serialization and validation. It excludes HTTP, MCP, and admin entry points, capability enforcement, projection and indexing, and an unpublish operation.

## Alternatives

**Use commit SHAs for optimistic concurrency.** A commit SHA identifies repository history, not the document the caller read; any unrelated commit would invalidate it. The requirements make the content-hash revision the concurrency token and commit SHAs audit-only.

**Last-writer-wins instead of `expected_revision`.** Simpler for callers, but a stale agent would silently destroy newer content. The requirements require a conflict instead of an overwrite.

**Queue writes through a background worker.** A queue decouples the response from the commit, so the acknowledgement point would move to "enqueued" or require the caller to poll. Synchronous writes under a short per-repository lock match the acknowledgement contract and the two-core deployment target.

**Let `update` change visibility.** One flag on an existing operation, but then `WRITE_PRIVATE` could expose or hide public content and the `PUBLISH` capability boundary collapses. `publish` stays the only door to `public`.

**Server-generated paths from date or title.** Auto-slugging eases creation but makes location a service policy with normalization and collision rules of its own. Location is caller intent; entrances may add conveniences later without changing this contract.

**Idempotency keys on `create`.** A retried create after a lost response can duplicate a document. Keys would fix that at the cost of a persistent deduplication store. Deferred: results report `committed` honestly, and a caller can search before retrying; a real duplicate incident may justify a separate proposal.

## Verification

- `DocumentWriteServiceTests` covers the root commit on an unborn `main`, private creation, path validation before the repository is reached, revision and `updated_at` advance, the unchanged-update and republish no-ops, moves, conflicts carrying the live revision, not-found against conflict, occupied and normalization-colliding targets, single-publication time, workspace independence, and commit subject and trailer contents.
- `DocumentWriteRecoveryTests` covers disposable dirty-cache recovery, a lost successful push response applying exactly once, an unverifiable ambiguous result, and independent application caches advancing the same base with one success and one conflict.
- `WritePrincipalTests` covers trailer rendering and the identifiers the value type refuses.
- `ModularityTests` verifies that the write contracts live in the content module's API package while its JGit implementation stays internal.
- The document-UUID uniqueness guard in `create` is unreachable through the service, which assigns a fresh random UUID, so it stands as a guard against a repository that already violates the foundation's uniqueness rule rather than a tested path.
- `./gradlew test`, `./gradlew repoCheck`, and `git diff --check` cover this implementation. Docker is not required because these operations add no database state.

## Risks

A lost create response can still produce duplicate documents, because create has no natural idempotency token. Honest `committed` reporting and AI-oriented error text are the current mitigations.

The write lock is process-local, so application instances must not share one cache directory. Correctness across separate instance caches comes from remote compare-and-swap, not from distributed locking.

A definite remote conflict leaves the candidate unacknowledged even though its objects may already exist remotely. They are unreachable and can be collected by ordinary Git maintenance; no caller treats object transfer as commit success.

A write needs a clock that has advanced past the document's `updated_at`. A backwards clock step fails the write rather than committing a document whose update time precedes the one it replaced.

Remote unavailability now blocks current reads and writes. A populated cache avoids retransferring unchanged objects but never becomes an offline acknowledgement mode.
