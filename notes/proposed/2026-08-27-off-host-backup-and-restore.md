# Off-Host Backup and Restore

Date: 2026-08-27
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) require off-host copies of content history, images, and non-derived PostgreSQL tables but do not define confidentiality boundaries, retention, recovery points, failure visibility, or restore drills. Running a second `git push`, copying object-storage keys, or invoking `pg_dump` does not prove that data survives provider loss and may propagate a deletion into the supposed backup.

Under [remote repository authority](2026-09-01-remote-repository-authority.md), Markdown and repository images already live off the application host, while local and OSS [BlobStores](2026-09-01-repository-asset-blob-store.md) hold only derived image copies. Backup must protect the authoritative remote repository independently from its serving provider and protect non-derived PostgreSQL state without treating a disposable cache as recovery data.

## Proposal

### Common rules

- Production configuration names at least one recovery target independent from the authoritative Git provider for repository history and one off-host target for non-derived PostgreSQL data. A target may be a self-hosted service or operator-selected third-party storage. Normal application operation does not require continuous backup-target availability.
- Backups are not encrypted in v1. Confidentiality relies on the operator choosing private, access-controlled targets; a public or shared-tenant target is a configuration error. Remote credentials and recovery material never enter the code repository, content repositories, backup bundles, logs, or metrics.
- Each medium records the last successful time, covered workspaces, source checkpoint, target identifier, byte count, and sanitized failure category. A new failure cannot erase the latest successful recovery point.
- Backups retain versions for a defined period. The default flow does not immediately propagate a source deletion into every off-host copy; cleanup is a separate delayed and auditable retention task.
- Documentation starts a restore from an empty data directory. Automated checks or scheduled drills for each medium prove that artifacts are readable, checksums match, and the application can reach the declared checkpoint.

### Remote content repositories

- The backup job reads the authoritative remote repository and copies all required refs and reachable objects into an independent retention boundary. The target forbids routine force pushes and retains enough history to recover from repository deletion, credential compromise, or an incorrect ref update.
- Restore creates or selects a replacement private remote repository, verifies its objects and refs, and atomically binds the workspace authority to it through an operator-controlled recovery procedure. No application cache is promoted into authority by guessing that it is newer.
- Repository images are covered with the commits that refer to them. A recovery point is invalid if reachable image blobs are missing.

### Derived asset BlobStores

- The single-server local BlobStore and serverless OSS BlobStore are rebuildable materializations of authoritative Git blobs. They are not backup media and are not copied into recovery artifacts.
- Restore starts with an empty BlobStore and rematerializes images on demand from the restored remote Git authority. Missing or corrupt authoritative image blobs fail the repository recovery check rather than producing placeholders.

### PostgreSQL

- Accounts, memberships, invitations, API-key metadata, audits, budgets, and the workspace catalog are the non-derived data that must be recoverable. Secrets retain their hashed or encrypted storage contract.
- The implementation may create a whole-database dump, but PostgreSQL contains no document or content-search projection under [Repository-native retrieval and sandboxed agent execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). Disposable read caches and execution snapshots are rebuilt from the restored content repositories rather than backed up.
- A dump manifest records database schema version, creation time, and covered workspaces. Restore verifies schema, row-count invariants, and critical foreign keys in an isolated database before replacing production data.

## Implementation scope and dependencies

Content backup depends on implemented remote repository authority, and database backup depends on the first non-derived tables and schema-management mechanism. These media have no common implementation prerequisite and therefore become separate tasks behind their own dependencies. Production automatic deployment may check a recent verified backup only after both exist and pass one combined restore drill. Derived repository caches and BlobStores add no backup task.

The first implementation provides backup commands, retention rules, machine-readable state, restore commands, and restore tests in disposable environments for the available media. It excludes multi-region hot standby, automatic failover, continuous database archiving, and a zero recovery-point objective.

## Alternatives considered

**Treat the authoritative Git provider as the backup.** Git contains repository text, images, and history, but provider deletion, credential compromise, or a mistaken ref update can damage the same authority. It also contains no authoritative PostgreSQL rows.

**Run `rclone sync` over the whole data directory.** This is simple but copies temporary files, runtime locks, and derived state and may propagate deletions. Verifiable artifacts per medium make the recovery boundary explicit.

**Back up disposable read caches and execution snapshots.** This could reduce first-read latency after restore, but it copies private transient data and sandbox state without adding durability. Rebuilding both from restored repositories keeps recovery artifacts smaller and removes stale execution state.

**Rely only on a manual restore guide.** Documentation cannot prove that current commands still read current formats. Disposable restore tests expose drift in schemas, manifests, and paths.

**Encrypt backups before they leave the host.** Client-side encryption would protect content from the storage provider, but key custody becomes an independent recovery prerequisite — a lost key silently turns every recovery point unreadable — and the content git mirror would still carry plaintext, leaving mixed guarantees. v1 keeps backups readable with the operator's target credentials alone; a later proposal may add encryption together with a key-custody and restore-drill story.

## Acceptance

- Remote repository authority and non-derived PostgreSQL data can retain versioned, verified recovery points in independent failure domains. A missing target fails explicitly and cannot report success.
- Consecutive failures do not delete the latest successful recovery point. Status exposes the last success and current lag without revealing paths, content, or credentials.
- A restore into an empty application data directory produces a replacement remote repository whose commits and reachable image blobs match the backup manifest, plus non-derived database constraints matching their manifest.
- Restore excludes disposable repository caches, asset BlobStores, read caches, and execution snapshots. The first repository-backed read resolves restored remote `main`, and the first image request rematerializes exact Git bytes without a database indexing prerequisite.
- After a source deletion or ref move, at least one recovery point covered by retention remains retrievable.
- Backup and restore tests use disposable repositories, object storage, and PostgreSQL and never read developer data.
- `./gradlew repoCheck`, the relevant automated tests, and `git diff --check` pass.

## Risks

Unencrypted backups place private content inside each target's trust domain. A compromised target account discloses content as well as history, so target credentials are secrets and target selection is a privacy decision; operations documentation must say both.

Backup freshness does not prove data correctness. Only a restore drill validates formats, keys, and dependencies together. An automatic-deployment gate must inspect the latest verified backup rather than only the exit code of the latest upload command.
