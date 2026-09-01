# Git Replication and Write Acknowledgement Modes

Date: 2026-08-27
Status: Rejected
Rejected: 2026-09-01

## Rejection

[Remote repository authority](../proposed/2026-09-01-remote-repository-authority.md) selects remote `main` as the sole authority for both supported production profiles. A local-acknowledgement mode and a mirrored-acknowledgement mode would preserve two correctness, recovery, and deployment models, so Poketto will use a disposable local cache instead. The analysis below is retained to prevent local-first replication from being reintroduced as a harmless transport option.

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) make the local content repository's `main` the source of truth and acknowledge a machine write after its local commit succeeds. They describe the remote only as an optional backup and do not define replication timing, failure recovery, observability, or a deployment mode that waits for off-host durability before acknowledging a write.

Local authority must remain writable without a network while allowing an operator to put remote confirmation in the write path through one durability setting. Both modes share the same content model and Git replication mechanism rather than forming separate business implementations.

## Proposal

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
- Temporary network failures, authentication-service failures, and remote rate limits use bounded exponential backoff while remaining observable. Authentication failure, permission denial, a missing repository, and non-fast-forward rejection have distinct classifications. An endless retry loop must not hide a permanent failure.
- Write outcomes expose `commit_sha` when known and the independently observed `committed` and `mirrored` booleans. `committed` means local `main` contains the candidate, and `mirrored` means the remote contains it. These observations do not change success under the selected acknowledgement policy. Content reads resolve committed repository state directly under the [repository-native retrieval proposal](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), so there is no independent indexed state.
- Per-workspace operational state exposes `local_head`, `last_mirrored_commit`, mirror lag in commits and duration, the last attempt time, and a sanitized failure category. Ordinary members cannot read remote addresses, credentials, or another workspace's state.
- A commit is mirrored only when the remote contains it. Uploading objects, starting a push, or recording a successful task does not advance `last_mirrored_commit`.

### Relationship to backup

A remote mirror reduces the content recovery point after host loss but does not replace an independent backup policy. Credential compromise, accidental deletion, and repository corruption can propagate to the remote. The [off-host backup and restore proposal](../proposed/2026-08-27-off-host-backup-and-restore.md) owns retention, confidentiality boundaries, and recovery drills.

## Implementation scope and dependencies

This proposal depends on the implemented [workspace and tenant boundary](../implemented/2026-08-27-workspace-tenancy.md), [content repository foundation](../implemented/2026-08-26-content-foundation.md), and [document write operations](../implemented/2026-08-29-document-write-operations.md), whose machine writes the `mirrored` policy wraps; asynchronous replication in `local` mode needs only committed repositories. Asynchronous replication and `mirrored` acknowledgement share one Git remote adapter, ref comparison implementation, and error taxonomy and may be implemented in one task.

The first implementation includes configuration binding, startup consistency checks, candidate publication, per-repository replication workers, checkpoints, status queries, and failure tests against local bare remotes. It excludes GitHub-specific APIs, external push ingestion, automatic merge, remote-repository creation, and conflict arbitration.

## Alternatives considered

**Always require a remote push before acknowledgement.** This gives uniform off-host durability but makes network and remote availability part of every write, contrary to the default self-contained topology. `mirrored` remains an explicit strict policy.

**Commit to local `main` before synchronously pushing in strict mode.** This is simpler, but a push failure leaves visible content while the caller receives failure, and a retry may duplicate the write. An unpublished commit keeps acknowledgement failure out of visible local history.

**Persist a replication queue item for every commit.** This provides per-item state, but pushing the newest ref already carries every ancestor. Per-repository wakeup coalescing avoids redundant durable queue entries and network requests while a checkpoint still represents progress.

**Accept human writes on both local and remote repositories.** This introduces dual writes, pull, and merge policy and makes the remote more than a mirror. External collaboration requires a separate remote-authority or ingress proposal.

## Acceptance

- Both requirements documents describe default local acknowledgement, optional `mirrored` acknowledgement, the remote-mirror boundary, and the independent `committed` and `mirrored` result semantics.
- `local` acknowledges a write while the remote is unavailable and reports `mirrored=false`; the worker advances the remote to local HEAD after connectivity returns.
- One push may mirror several consecutive local commits, and the checkpoint identifies a commit the remote actually contains.
- A rejected `mirrored` push does not change local `main`; success leaves both local and remote refs containing the returned `commit_sha`.
- Failure tests cover a crash after remote success but before the local update, an unborn `main` start with and without an existing remote ref, remote-behind, remote-ahead, divergence, authentication failure, non-fast-forward rejection, network timeout, and process restart.
- Divergence exits automatic retry and blocks later mirrored writes. No path force-pushes or merges automatically.
- Locks, remotes, checkpoints, retries, and status remain independent for two workspaces.
- `./gradlew test`, integration tests against local bare remotes, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

`mirrored` makes remote availability part of the write success rate. Errors must distinguish an uncommitted write, a candidate stored remotely and awaiting local recovery, and a locally committed write awaiting mirroring so callers do not retry blindly.

A Git remote generally provides repository-level ref semantics, not a Poketto business transaction. The implementation must prove durability through ref relationships and an actual remote read rather than treating an ambiguous successful command log as evidence.
