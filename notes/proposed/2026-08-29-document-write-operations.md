# Document Write Operations

Date: 2026-08-29
Status: Proposed

## Problem

The [content repository foundation](../implemented/2026-08-26-content-foundation.md) defines repository bootstrap, the managed document layout, the frontmatter schema, canonical serialization, and content-hash revisions, but no operation mutates a repository. The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) define the write model — machine entrances validate strictly, serialize writes per repository, commit on the caller's behalf, record the caller's identity, acknowledge on successful commit, and return a conflict instead of overwriting on a revision mismatch — without an owning implementation contract.

MCP tools and the admin UI need the same commit semantics. If each entrance implements its own, the acknowledgement point, conflict behavior, and audit attribution diverge.

## Proposal

### Operations

The content module exposes four workspace-scoped operations: `create`, `update`, `delete`, and `publish`. Every operation requires an explicit `WorkspaceId` and an acting principal, validates its input before taking the repository lock, and commits to the workspace repository's `main`.

- `create` takes a path below `documents/`, a title, tags, and a body. The service assigns the document UUID, sets `created_at` and `updated_at` to the current UTC instant, serializes canonically, and commits. Every created document is `private`; no create parameter selects `public`.
- `update` takes the document UUID, `expected_revision`, and the full new title, tags, body, and path. It preserves `id`, `created_at`, `published_at`, and visibility. A path change is a move, and a move is an edit: any change to the bytes or the path advances `updated_at` and therefore produces a new revision, so a concurrent move surfaces as a conflict instead of being silently relocated back. An update that changes neither bytes nor path succeeds without a new commit.
- `delete` takes the document UUID and `expected_revision` and removes the document file.
- `publish` takes the document UUID and `expected_revision`, sets visibility to `public`, sets `published_at` on the first publish only, and commits. Publishing an already-public document whose `expected_revision` matches the live revision succeeds without a new commit. A retry after a lost publish acknowledgement carries the pre-publish revision and returns a conflict; the caller re-reads and observes the completed publish. No operation returns a public document to `private`; reverting is a break-glass repository commit.

### Concurrency and acknowledgement

One in-JVM lock per workspace repository serializes these operations; locks, like repositories, are independent across workspaces. The application process is the only machine writer, and one process owns a data directory: running two application processes against the same data directory is outside the deployment contract, and no cross-process lock exists in v1.

Under the lock, the operation first requires a clean repository: the worktree and index must equal `HEAD`, because the owner may edit the worktree directly as break-glass. Any other state refuses the machine write with an error naming what must be committed or reverted first — a machine write never commits or overwrites bytes it did not produce. The operation then reads the current `main` tree, verifies `expected_revision` against the committed blob hash, applies the change, and commits. A mismatch changes nothing and returns a conflict carrying the live revision so the caller re-reads instead of retrying blind. A missing document returns not-found, distinct from conflict: after a lost `delete` acknowledgement, a retry reads as already applied. `create` and a path-changing `update` verify that the target path is free after Unicode normalization and case folding, and `create` verifies document-UUID uniqueness, under the same lock. The first successful write on an unborn `main` creates the root commit.

Before mutating the worktree, the operation records the paths it will touch in an intent journal inside the repository's git directory. A failed stage or commit resets exactly those paths to `HEAD` and removes the journal before the lock releases. After a process crash mid-write, recovery — at startup or before the next write — resets the journaled paths and removes the journal, touching nothing else. Dirty state without a journal is operator activity and blocks machine writes until resolved.

Write results share the document UUID, the commit SHA, and the `committed` observation. `create`, `update`, and `publish` add the resulting path and revision; `delete` reports the removed path and carries no revision, because no blob remains. The acknowledgement policy and the `mirrored` observation belong to [Git replication and write acknowledgement modes](2026-08-27-git-durability-modes.md); these operations are the machine write its policies wrap. Repository-backed readers resolve committed state directly as proposed by [Repository-native retrieval and sandboxed agent execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). A crash between commit and response loses only the acknowledgement — `main` either contains the whole commit or none of it.

### Attribution

Commits use a fixed service author identity. The acting principal — a value type naming the principal type and a stable identifier — is recorded in a `Poketto-Principal` commit trailer, and the commit subject names the operation and document UUID. [Invitation-only membership](2026-08-27-invitation-only-membership.md) supplies real account and API-key identities later; these operations accept the principal as data and do not depend on login existing. Commit metadata never contains display names, email addresses, credentials, or session tokens: content repositories may be mirrored off-host, so identity in git history stays limited to durable opaque identifiers.

### Capability mapping

These operations do not authorize. Entry points resolve an authorized workspace and capability before invoking them, per the [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md). The contract entry points must enforce: mutating a private document requires `WRITE_PRIVATE`; `publish`, and every mutation of an already-public document, requires `PUBLISH`. A key holding only `WRITE_PRIVATE` therefore can never change what the public site serves. An entrance checks the target's visibility from its own read, and `expected_revision` closes the race between that check and the write: a publish in between changes the bytes and turns the stale write into a conflict.

## Implementation scope and dependencies

This proposal depends on the implemented workspace boundary and the implemented [content repository foundation](../implemented/2026-08-26-content-foundation.md), whose canonical serialization and validation it reuses. The first implementation includes the four operations, per-repository locking, the clean-repository check, the intent journal and its crash recovery, validation, attribution, write results, and focused tests using temporary data directories. It excludes HTTP, MCP, and admin entry points, capability enforcement, repository read and execution entry points, an unpublish operation, and replication.

## Alternatives considered

**Use commit SHAs for optimistic concurrency.** A commit SHA identifies repository history, not the document the caller read; any unrelated commit would invalidate it. The requirements make the content-hash revision the concurrency token and commit SHAs audit-only.

**Last-writer-wins instead of `expected_revision`.** Simpler for callers, but a stale agent would silently destroy newer content. The requirements require a conflict instead of an overwrite.

**Queue writes through a background worker.** A queue decouples the response from the commit, so the acknowledgement point would move to "enqueued" or require the caller to poll. Synchronous writes under a short per-repository lock match the acknowledgement contract and the two-core deployment target.

**Let `update` change visibility.** One flag on an existing operation, but then `WRITE_PRIVATE` could expose or hide public content and the `PUBLISH` capability boundary collapses. `publish` stays the only door to `public`.

**Server-generated paths from date or title.** Auto-slugging eases creation but makes location a service policy with normalization and collision rules of its own. Location is caller intent; entrances may add conveniences later without changing this contract.

**Idempotency keys on `create`.** A retried create after a lost response can duplicate a document. Keys would fix that at the cost of a persistent deduplication store. Deferred: results report `committed` honestly, and a caller can search before retrying; a real duplicate incident may justify a separate proposal.

## Acceptance

- All four operations require `WorkspaceId` and a principal. Operations on one workspace never read or change another workspace's repository, and the same document UUID may exist independently in two workspaces.
- `create` on an unborn `main` produces the root commit in canonical form; a duplicate UUID or a path colliding after normalization is rejected with the conflicting path named.
- `update`, `delete`, and `publish` enforce `expected_revision` under the repository lock; a mismatch leaves `main` unchanged and returns the live revision; not-found and conflict are distinguishable.
- `publish` sets `published_at` exactly once; republishing with the live revision is an acknowledged no-op, a stale revision returns a conflict, and no operation makes a public document private.
- Any change to bytes or path advances `updated_at` and the revision; an update changing neither succeeds without a new commit. A stale revision after a concurrent move returns a conflict, and a move onto an occupied or normalization-colliding path is rejected.
- A worktree or index differing from `HEAD` blocks machine writes with the offending state named; after a failed write or a simulated crash mid-apply, journal-driven recovery restores the repository to `HEAD` without touching human edits.
- Commits carry the service author identity and the `Poketto-Principal` trailer; no commit contains a display name, email address, credential, or token.
- Concurrent writers to one repository serialize; writers to two workspaces proceed independently.
- After a simulated crash between commit and response, the repository equals the committed state and a retried `update` returns a conflict rather than applying twice.
- `./gradlew test`, `./gradlew repoCheck`, and `git diff --check` pass. Docker is not required because these operations add no database state.

## Risks

A lost create response can still produce duplicate documents, because create has no natural idempotency token. Honest `committed` reporting and AI-oriented error text are the current mitigations.

The write lock is process-local. Deployment and operations documentation must keep one application process per data directory; a second process would bypass serialization and can corrupt the worktree mid-commit.

Journal-driven recovery resets the journaled paths unconditionally, so a break-glass edit made to exactly those paths between a crash and recovery is discarded. The journal names only paths a machine write was already replacing, which keeps the window narrow.
