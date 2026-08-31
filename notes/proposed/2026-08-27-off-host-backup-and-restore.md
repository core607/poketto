# Off-Host Backup and Restore

Date: 2026-08-27
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) require off-host copies of content history, image blobs, and non-derived PostgreSQL tables but do not define confidentiality boundaries, retention, recovery points, failure visibility, or restore drills. Running `git push`, `rclone sync`, or `pg_dump` does not prove that data survives host loss and may propagate a deletion into the supposed backup.

The default self-contained topology keeps authoritative state on one machine, so production automatic deployment requires a verified off-host recovery path. Backup provides disaster recovery and does not participate in normal request consistency.

## Proposal

### Common rules

- Production configuration names at least one off-host target for content repositories, blobs, and non-derived PostgreSQL data. A target may be a self-hosted service or operator-selected third-party object storage. Normal application operation does not require continuous target availability.
- Backups are not encrypted in v1. Confidentiality relies on the operator choosing private, access-controlled targets; a public or shared-tenant target is a configuration error. Remote credentials and recovery material never enter the code repository, content repositories, backup bundles, logs, or metrics.
- Each medium records the last successful time, covered workspaces, source checkpoint, target identifier, byte count, and sanitized failure category. A new failure cannot erase the latest successful recovery point.
- Backups retain versions for a defined period. The default flow does not immediately propagate a source deletion into every off-host copy; cleanup is a separate delayed and auditable retention task.
- Documentation starts a restore from an empty data directory. Automated checks or scheduled drills for each medium prove that artifacts are readable, checksums match, and the application can reach the declared checkpoint.

### Content repositories

- [Git replication and write acknowledgement](2026-08-27-git-durability-modes.md) provides per-commit remote mirroring and lag status. The backup target also protects remote refs and reachable objects, forbids force pushes, and retains enough history to recover from an accidental deletion or incorrect ref update.
- Restore creates and validates each workspace's local repository before restoring other authoritative stores. Content `main` is immediately available to repository-backed reads; remote-mirror state cannot overwrite a newer valid offline backup.

### Blobs

- Blobs remain SHA-256 content-addressed inside a workspace namespace. Backup uploads immutable objects and a verifiable manifest containing workspace, hash, size, and media type.
- Transfer may use an object store supported by `rclone`, but the default uses append or copy semantics with target-side version retention. A bare `sync` that immediately deletes target objects cannot be the only backup.
- Restore verifies every file hash. Missing or corrupt blobs are reported explicitly; an empty file, placeholder image, or re-encoded byte sequence cannot stand in for the original object.

### PostgreSQL

- Accounts, memberships, invitations, API-key metadata, audits, budgets, and the workspace catalog are the non-derived data that must be recoverable. Secrets retain their hashed or encrypted storage contract.
- The implementation may create a whole-database dump, but PostgreSQL contains no document or content-search projection under [Repository-native retrieval and sandboxed agent execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). Disposable read caches and execution snapshots are rebuilt from the restored content repositories rather than backed up.
- A dump manifest records database schema version, creation time, and covered workspaces. Restore verifies schema, row-count invariants, and critical foreign keys in an isolated database before replacing production data.

## Implementation scope and dependencies

Content backup depends on Git replication, blob backup depends on local blob storage, and database backup depends on the first non-derived tables and schema-management mechanism. These media have no common implementation prerequisite and therefore become separate tasks behind their own dependencies. Production automatic deployment may check a recent verified backup only after all three exist and pass one combined restore drill.

The first implementation provides backup commands, retention rules, machine-readable state, restore commands, and restore tests in disposable environments for the available media. It excludes multi-region hot standby, automatic failover, continuous database archiving, and a zero recovery-point objective.

## Alternatives considered

**Treat the Git remote as the complete backup.** Git protects content text and history but contains neither blobs nor authoritative PostgreSQL rows. A mistaken or malicious ref update can also damage the mirror.

**Run `rclone sync` over the whole data directory.** This is simple but copies temporary files, runtime locks, and derived state and may propagate deletions. Verifiable artifacts per medium make the recovery boundary explicit.

**Back up disposable read caches and execution snapshots.** This could reduce first-read latency after restore, but it copies private transient data and sandbox state without adding durability. Rebuilding both from restored repositories keeps recovery artifacts smaller and removes stale execution state.

**Rely only on a manual restore guide.** Documentation cannot prove that current commands still read current formats. Disposable restore tests expose drift in schemas, manifests, and paths.

**Encrypt backups before they leave the host.** Client-side encryption would protect content from the storage provider, but key custody becomes an independent recovery prerequisite — a lost key silently turns every recovery point unreadable — and the content git mirror would still carry plaintext, leaving mixed guarantees. v1 keeps backups readable with the operator's target credentials alone; a later proposal may add encryption together with a key-custody and restore-drill story.

## Acceptance

- All three authoritative media can retain a versioned, checksummed recovery point off-host. A missing target fails explicitly and cannot report success.
- Consecutive failures do not delete the latest successful recovery point. Status exposes the last success and current lag without revealing paths, content, or credentials.
- A restore into an empty data directory produces content commits, blob hashes, and non-derived database constraints matching the backup manifest.
- Restore excludes disposable read caches and execution snapshots. The first repository-backed read resolves the restored workspace `main` without a database indexing prerequisite.
- After a source deletion or ref move, at least one recovery point covered by retention remains retrievable.
- Backup and restore tests use disposable repositories, object storage, and PostgreSQL and never read developer data.
- `./gradlew repoCheck`, the relevant automated tests, and `git diff --check` pass.

## Risks

Unencrypted backups place private content inside each target's trust domain. A compromised target account discloses content as well as history, so target credentials are secrets and target selection is a privacy decision; operations documentation must say both.

Backup freshness does not prove data correctness. Only a restore drill validates formats, keys, and dependencies together. An automatic-deployment gate must inspect the latest verified backup rather than only the exit code of the latest upload command.
