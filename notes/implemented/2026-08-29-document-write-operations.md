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

`DocumentWriteService` accepts the revision as opaque input and does not define an agent read entrance. [Repository-native retrieval and sandboxed agent execution](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) defines the proposed structured `get_doc` handshake that supplies the document and revision from one committed snapshot before an agent invokes these writes.

### Concurrency and acknowledgement

One in-JVM lock per workspace repository serializes these operations; locks, like repositories, are independent across workspaces. The application process is the only machine writer, and one process owns a data directory: running two application processes against the same data directory is outside the deployment contract, and no cross-process lock exists.

Under the lock, the operation first requires a clean repository: the worktree and index must equal `HEAD`, because the owner may edit the worktree directly as break-glass. That requirement reads `HEAD` literally, so an untracked or ignored file inside the content directory blocks a machine write as well — the worktree then holds bytes the machine did not produce, and it cannot tell an unfinished document from debris. Any such state raises `RepositoryNotCleanException` naming what must be committed or reverted first.

The operation then reads the current `main` tree, verifies `expected_revision` against the committed blob hash, applies the change, and commits. A mismatch changes nothing and raises `DocumentConflictException` carrying the live revision so the caller re-reads instead of retrying blind. A missing document raises `DocumentNotFoundException`, distinct from a conflict: after a lost `delete` acknowledgement, a retry reads as already applied. `create` and a path-changing `update` verify that the target path is free after Unicode normalization and case folding, and `create` verifies document-UUID uniqueness, under the same lock. The first successful write on an unborn `main` creates the root commit.

A committed change always carries a later `updated_at`, and therefore a new revision. Canonical serialization advances it when the document's own fields change; the write service advances it for a move, and for a write that rewrites a hand-edited file whose fields already match into canonical form.

Before mutating the worktree, the operation records the paths it will touch in an intent journal inside the repository's git directory. A failed stage or commit resets exactly those paths to `HEAD` and removes the journal before the lock releases. Recovery runs at the start of every write rather than at process startup, so a workspace recovers without the process having enumerated it: a journal left behind by a crash resets its paths and is removed, touching nothing else. Dirty state without a journal is operator activity and blocks machine writes until resolved.

`DocumentWriteResult` shares the document UUID, the commit that holds the reported state, and the independent `committed` and `mirrored` observations; an operation that finds the repository already in the requested state reports `committed` false and the unchanged commit. `create`, `update`, and `publish` add the resulting path and revision; `delete` reports the removed path and carries no revision, because no blob remains. [Git replication and write acknowledgement modes](2026-08-27-git-durability-modes.md) own the acknowledgement policy and wrap these machine writes. A crash between commit and response loses only the acknowledgement — `main` either contains the whole commit or none of it.

### Attribution

Commits use the fixed service author and committer identity `Poketto <poketto@invalid>`, whose domain is reserved and routes nowhere. The acting principal — `WritePrincipal`, a principal type and a stable identifier — is recorded in a `Poketto-Principal` commit trailer, and the commit subject names the operation and document UUID. [Invitation-only membership](../proposed/2026-08-27-invitation-only-membership.md) supplies real account and API-key identities later; these operations accept the principal as data and do not depend on login existing.

Commit metadata must never contain display names, email addresses, credentials, or session tokens: content repositories may be mirrored off-host, so identity in git history stays limited to durable opaque identifiers. Entrances map credentials and sessions to stable principal identifiers before constructing `WritePrincipal`. The value type accepts up to 64 characters of `[A-Za-z0-9._-]` after an alphanumeric first character, making email addresses, display names, and trailer-breaking newlines unrepresentable. This syntax check cannot determine whether an otherwise valid token is secret.

### Capability mapping

These operations do not authorize. Entry points resolve an authorized workspace and capability before invoking them, per the [workspace boundary](2026-08-27-workspace-tenancy.md). The contract entry points must enforce: mutating a private document requires `WRITE_PRIVATE`; `publish`, and every mutation of an already-public document, requires `PUBLISH`. A key holding only `WRITE_PRIVATE` therefore can never change what the public site serves. An entrance checks the target's visibility from its own read, and `expected_revision` closes the race between that check and the write: a publish in between changes the bytes and turns the stale write into a conflict.

### Implemented scope

The implementation covers the four operations, per-repository locking, the clean-repository check, the intent journal and its crash recovery, validation, attribution, and write results, reusing the foundation's canonical serialization and validation. It excludes HTTP, MCP, and admin entry points, capability enforcement, projection and indexing, an unpublish operation, and replication.

## Alternatives

**Use commit SHAs for optimistic concurrency.** A commit SHA identifies repository history, not the document the caller read; any unrelated commit would invalidate it. The requirements make the content-hash revision the concurrency token and commit SHAs audit-only.

**Last-writer-wins instead of `expected_revision`.** Simpler for callers, but a stale agent would silently destroy newer content. The requirements require a conflict instead of an overwrite.

**Queue writes through a background worker.** A queue decouples the response from the commit, so the acknowledgement point would move to "enqueued" or require the caller to poll. Synchronous writes under a short per-repository lock match the acknowledgement contract and the two-core deployment target.

**Let `update` change visibility.** One flag on an existing operation, but then `WRITE_PRIVATE` could expose or hide public content and the `PUBLISH` capability boundary collapses. `publish` stays the only door to `public`.

**Server-generated paths from date or title.** Auto-slugging eases creation but makes location a service policy with normalization and collision rules of its own. Location is caller intent; entrances may add conveniences later without changing this contract.

**Idempotency keys on `create`.** A retried create after a lost response can duplicate a document. Keys would fix that at the cost of a persistent deduplication store. Deferred: results report `committed` honestly, and a caller can search before retrying; a real duplicate incident may justify a separate proposal.

## Verification

- `DocumentWriteServiceTests` covers the root commit on an unborn `main`, private creation, path validation before the repository is reached, revision and `updated_at` advance, the unchanged-update and republish no-ops, moves, conflicts carrying the live revision, not-found against conflict, occupied and normalization-colliding targets, single-publication time, workspace independence, and commit subject and trailer contents.
- `DocumentWriteRecoveryTests` covers blocked writes for modified, untracked, and ignored worktree state, journal recovery for a path `HEAD` holds and one it never held, operator edits outside the journal surviving recovery and still blocking, rollback and journal removal after an injected apply failure, a lost acknowledgement applying exactly once, serialization of two writers on one repository, and independent progress across two workspaces.
- `WritePrincipalTests` covers trailer rendering and the identifiers the value type refuses.
- `ModularityTests` verifies that the write contracts live in the content module's API package while its JGit implementation stays internal.
- The document-UUID uniqueness guard in `create` is unreachable through the service, which assigns a fresh random UUID, so it stands as a guard against a repository that already violates the foundation's uniqueness rule rather than a tested path.
- `./gradlew test`, `./gradlew repoCheck`, and `git diff --check` cover this implementation. Docker is not required because these operations add no database state.

## Risks

A lost create response can still produce duplicate documents, because create has no natural idempotency token. Honest `committed` reporting and AI-oriented error text are the current mitigations.

The write lock is process-local. Deployment and operations documentation must keep one application process per data directory; a second process would bypass serialization and can corrupt the worktree mid-commit.

Journal-driven recovery resets the journaled paths unconditionally, so a break-glass edit made to exactly those paths between a crash and recovery is discarded. The journal names only paths a machine write was already replacing, which keeps the window narrow.

A write needs a clock that has advanced past the document's `updated_at`. A backwards clock step fails the write rather than committing a document whose update time precedes the one it replaced.

Requiring the worktree to equal `HEAD` means an untracked or ignored scratch file in the content directory stops every machine write until the owner removes or commits it. Refusing is safer than guessing which stray bytes are disposable, but it makes the failure visible to agents rather than to the person who created it.
