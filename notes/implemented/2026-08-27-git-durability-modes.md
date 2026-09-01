# Git Replication and Write Acknowledgement Modes

Date: 2026-08-27
Status: Implemented

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) make the local content repository's `main` the source of truth and allow an optional output mirror. The mirror needs defined replication timing, failure recovery, observability, and a deployment mode that waits for off-host durability before acknowledging a write.

Local authority must remain writable without a network while allowing an operator to put remote confirmation in the write path through one durability setting. Both modes share the same content model and Git replication mechanism rather than forming separate business implementations.

## Decision

### Authority and remote boundary

- Each workspace's local non-bare repository and its `main` are authoritative by default. The application is the only machine writer, and writes are serialized per repository.
- The owner may edit and commit directly in the server's content worktree as a break-glass path. v1 does not accept a push from an external workstation into the checked-out non-bare `main`.
- The configured remote `main` is an output mirror, not a second editing entrance. Operations documentation requires every other user and automation to avoid direct updates and force pushes to that ref.
- A remote that is ahead of or divergent from local state is not a retryable network failure. Replication stops and reports divergence. The application never force-pushes automatically or guesses which side to preserve.

### Acknowledgement policy

`poketto.git.acknowledgement` accepts two values:

- `local`, the default: the write succeeds when the local `main` commit succeeds. A per-repository replication worker advances the remote asynchronously.
- `mirrored`: the write succeeds only after the remote accepts the candidate commit and local `main` advances to it. The application fails startup rather than falling back to `local` when the remote, credentials, or common starting ref are unavailable. An unborn local `main` with no remote `main` ref is a valid starting state: the first write publishes the root commit. A remote `main` that already has commits while local `main` is unborn blocks startup for operator intervention.

The setting is an instance-level default. A future per-workspace override requires a separate configuration contract. Changing the policy at runtime requires restart and the startup consistency check.

`mirrored` cannot expose a candidate on local `main` before attempting the push. While holding the repository write lock, the application builds an unpublished commit from the current `main` and pushes it with the current remote ref as a precondition. It advances local `main` only after the remote accepts the candidate. A rejected push leaves only unreachable local objects and does not change visible local history. If the process crashes after remote success but before the local ref update, startup may advance local state only after proving that remote `main` descends from local `main`; every other relationship blocks startup for operator intervention.

### Asynchronous replication and status

- In `local` mode, every commit wakes the single replication worker for its repository. Several consecutive commits may collapse into one push because the worker advances the remote to the latest local `main` observed when the push begins; it does not persist one queue item per commit.
- Temporary network and timeout failures use bounded exponential backoff while remaining observable. Authentication failure, permission denial, a missing repository, divergence, and non-fast-forward rejection have distinct classifications. An endless retry loop does not hide a permanent failure.
- Write outcomes expose `commit_sha` when known and the independently observed `committed` and `mirrored` booleans. `committed` reports whether this operation produced the named local `main` commit; a no-op reports false with the unchanged commit. `mirrored` reports that the named commit has been verified at the remote ref and recorded in the local replication checkpoint. These observations do not change success under the selected acknowledgement policy. Content reads resolve committed repository state directly under the [repository-native retrieval proposal](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), so there is no independent indexed state.
- Per-workspace operational state exposes `local_head`, `last_mirrored_commit`, mirror lag in commits and duration, the last attempt time, and a sanitized failure category. Ordinary members cannot read remote addresses, credentials, or another workspace's state.
- A commit is mirrored only when the remote contains it. Uploading objects, starting a push, or recording a successful task does not advance `last_mirrored_commit`.

### Relationship to backup

A remote mirror reduces the content recovery point after host loss but does not replace an independent backup policy. Credential compromise, accidental deletion, and repository corruption can propagate to the remote. The [off-host backup and restore proposal](../proposed/2026-08-27-off-host-backup-and-restore.md) owns retention, confidentiality boundaries, and recovery drills.

## Implementation

This decision builds on the [workspace and tenant boundary](2026-08-27-workspace-tenancy.md), [content repository foundation](2026-08-26-content-foundation.md), and [document write operations](2026-08-29-document-write-operations.md), whose machine writes the `mirrored` policy wraps. Asynchronous replication and `mirrored` acknowledgement share one JGit remote adapter, ref comparison implementation, and error taxonomy.

`poketto.git.acknowledgement` defaults to `local`; `poketto.git.remote`, retry bounds, and the network timeout are configurable. A verified remote commit is persisted as `refs/poketto/mirrored/main`. The local worker keeps no per-commit queue: a wakeup reads the current `main`, pushes it with the advertised remote head as a lease, reads the remote ref again, and only then advances the checkpoint. Its state and retry schedule are isolated by `WorkspaceId`.

Strict writes insert a commit object without updating a branch, publish it under the same lease, verify the remote ref, and then move local `main`. Startup compares the refs before accepting writes. It can finish the one safe interrupted state — a locally available remote descendant — and refuses an unborn-local/populated-remote pair or any divergence. The implementation never fetches, merges, force-pushes, creates a remote repository, or accepts an external push.

## Alternatives considered

**Always require a remote push before acknowledgement.** This gives uniform off-host durability but makes network and remote availability part of every write, contrary to the default self-contained topology. `mirrored` remains an explicit strict policy.

**Commit to local `main` before synchronously pushing in strict mode.** This is simpler, but a push failure leaves visible content while the caller receives failure, and a retry may duplicate the write. An unpublished commit keeps acknowledgement failure out of visible local history.

**Persist a replication queue item for every commit.** This provides per-item state, but pushing the newest ref already carries every ancestor. Per-repository wakeup coalescing avoids redundant durable queue entries and network requests while a checkpoint still represents progress.

**Accept human writes on both local and remote repositories.** This introduces dual writes, pull, and merge policy and makes the remote more than a mirror. External collaboration requires a separate remote-authority or ingress proposal.

## Verification

- Both requirements documents describe default local acknowledgement, strict `mirrored` acknowledgement, the output-only remote boundary, and independent `committed` and `mirrored` observations.
- Filesystem integration tests use local bare remotes to prove coalesced local replication, a remote-behind update, strict first-write publication, rejected strict writes, remote-ahead crash recovery, unborn combinations, restart from a persisted checkpoint, divergence, and workspace isolation. Transport-seam tests prove bounded retry after a timeout and a permanent authentication failure that stops until explicit retry.
- The strict rejection test verifies that neither local `main` nor the stored document set changes. Successful strict writes leave local and remote `main` at the returned commit.
- Checkpoint assertions read the bare remote independently before accepting `last_mirrored_commit` as evidence.
- `./gradlew test`, `./gradlew repoCheck`, and `git diff --check` cover the implementation and this record. The bare-remote cases run in the unit test suite because they need only filesystem Git transport, not the database integration environment.

## Consequences and residual risks

`mirrored` makes remote availability part of the write success rate. Errors must distinguish an uncommitted write, a candidate stored remotely and awaiting local recovery, and a locally committed write awaiting mirroring so callers do not retry blindly.

A Git remote provides repository-level ref semantics, not a Poketto business transaction. The implementation proves durability through ref relationships and an actual remote read rather than treating an ambiguous command result as evidence. A host failure can still occur between the remote update, local ref update, and local checkpoint update; startup reconciliation is therefore part of strict-mode correctness, not only an operational convenience.
