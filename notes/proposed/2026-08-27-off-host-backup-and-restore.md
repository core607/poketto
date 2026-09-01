# Off-Host Backup and Restore

Date: 2026-08-27
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) require off-host copies of content history, images, and non-derived PostgreSQL tables but do not define confidentiality boundaries, retention, recovery points, failure visibility, or restore drills. Running a second `git push`, copying object-storage keys, or invoking `pg_dump` does not prove that data survives provider loss and may propagate a deletion into the supposed backup.

Under [remote repository authority](2026-09-01-remote-repository-authority.md), Markdown and repository-managed images already live off the application host. Local and OSS [ManagedBlobStores](2026-09-01-repository-asset-blob-store.md) are authoritative for images uploaded through Poketto. Backup must protect the remote repository, managed objects, and non-derived PostgreSQL state in independent recovery boundaries; disposable repository-image caches add no recovery source.

## Proposal

### Common rules

- Production configuration names recovery targets independent from the authoritative Git provider, ManagedBlobStore, and PostgreSQL service. A target may be a self-hosted service or operator-selected third-party storage. Normal application operation does not require continuous backup-target availability.
- Every backup is encrypted and authenticated before it leaves the source trust boundary. Encryption uses operator-controlled recovery material independent from target credentials. That material never enters the code repository, content repositories, backup bundles, logs, or metrics and must have a tested copy outside the application host; losing every copy makes the backup unrecoverable.
- Each medium records the last successful time, covered workspaces, source checkpoint, target identifier, byte count, and sanitized failure category. A new failure cannot erase the latest successful recovery point.
- Backups retain versions for a defined period. The default flow does not immediately propagate a source deletion into every off-host copy; cleanup is a separate delayed and auditable retention task.
- Documentation starts a restore from an empty data directory. Automated checks or scheduled drills for each medium prove that artifacts are readable, checksums match, and the application can reach the declared checkpoint.

### Remote content repositories

- The backup job reads the authoritative remote repository and copies all required refs and reachable objects into an independent retention boundary. The target forbids routine force pushes and retains enough history to recover from repository deletion, credential compromise, or an incorrect ref update.
- Restore creates or selects a replacement private remote repository, verifies its objects and refs, and atomically binds the workspace authority to it through an operator-controlled recovery procedure. No application cache is promoted into authority by guessing that it is newer.
- Repository-managed images are covered with the commits that contain them. Their disposable local or OSS materialization caches are excluded from backup.

### Managed asset BlobStores

- Backup copies every retained immutable managed object plus a verifiable manifest containing workspace, managed identity and revision, content hash, byte size, media type, and catalog checkpoint. It retains revisions long enough to cover delayed physical cleanup and restoration.
- Restore verifies every managed object hash before making it available. Repository-managed images are recovered from the repository backup instead; their materialized cache is rebuilt after restore. Missing or corrupt managed bytes are reported rather than replaced with a placeholder or re-encoded image.
- A bare storage `sync` that immediately propagates deletions cannot be the only backup. Asset cleanup and backup retention are separately delayed and auditable.

### PostgreSQL

- Accounts, memberships, invitations, API-key metadata, audits, budgets, workspace catalog, and the managed asset catalog are non-derived data that must be recoverable. Secrets retain their hashed or encrypted storage contract.
- The implementation may create a whole-database dump, but PostgreSQL contains no document or content-search projection under [Repository-native retrieval and sandboxed agent execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). Disposable read caches and execution snapshots are rebuilt from the restored content repositories rather than backed up.
- A dump manifest records database schema version, creation time, and covered workspaces. Restore verifies schema, row-count invariants, and critical foreign keys in an isolated database before replacing production data.

## Implementation scope and dependencies

Content backup depends on implemented remote repository authority, managed-asset backup depends on implemented BlobStore and catalog contracts, and database backup depends on the first non-derived tables and schema-management mechanism. These media have no common implementation prerequisite and therefore become separate tasks behind their own dependencies. Production automatic deployment may check a recent verified backup only after all three exist and pass one combined restore drill. Derived repository, repository-image, read, and execution caches add no backup task.

The first implementation provides backup commands, retention rules, machine-readable state, restore commands, and restore tests in disposable environments for the available media. It excludes multi-region hot standby, automatic failover, continuous database archiving, and a zero recovery-point objective.

## Alternatives considered

**Treat the authoritative Git provider as the backup.** Git contains repository text, repository-managed images, and history, but provider deletion, credential compromise, or a mistaken ref update can damage the same authority. It contains neither managed images nor authoritative PostgreSQL rows.

**Run `rclone sync` over the whole data directory.** This is simple but copies temporary files, runtime locks, and derived state and may propagate deletions. Verifiable artifacts per medium make the recovery boundary explicit.

**Back up disposable read caches and execution snapshots.** This could reduce first-read latency after restore, but it copies private transient data and sandbox state without adding durability. Rebuilding both from restored repositories keeps recovery artifacts smaller and removes stale execution state.

**Rely only on a manual restore guide.** Documentation cannot prove that current commands still read current formats. Disposable restore tests expose drift in schemas, manifests, and paths.

**Rely only on target access control or provider-side encryption.** This would let target credentials expose every retained private workspace and would couple confidentiality to provider configuration. Source-side encryption keeps backup contents outside that trust boundary; tested recovery material is an explicit operational prerequisite rather than an omitted one.

## Acceptance

- Remote repository authority, managed objects, and non-derived PostgreSQL data can retain encrypted, versioned, verified recovery points in independent failure domains. A missing target or recovery key fails explicitly and cannot report success.
- Consecutive failures do not delete the latest successful recovery point. Status exposes the last success and current lag without revealing paths, content, or credentials.
- A restore into an empty application data directory produces a replacement remote repository whose commits match the repository manifest, ManagedBlobStore objects whose hashes match the asset manifest, and non-derived database constraints matching their manifest.
- Restore excludes disposable repository, repository-image, read, and execution caches. The first repository-backed read resolves restored remote `main`; a repository-managed image rematerializes from its exact Git blob, and a managed reference resolves its restored catalog revision without a content-search index.
- After a source deletion or ref move, at least one recovery point covered by retention remains retrievable.
- Backup and restore tests use disposable repositories, object storage, and PostgreSQL and never read developer data.
- `./gradlew repoCheck`, the relevant automated tests, and `git diff --check` pass.

## Risks

Encrypted backups still expose sizes, timing, and target access patterns, and a compromised application host can read live plaintext. Target credentials and recovery material are separate secrets; neither replaces access control on the authoritative stores.

Lost recovery material makes otherwise healthy backup snapshots unreadable. Key availability, rotation, and an empty-environment restore drill are part of backup health rather than operator folklore.

Backup freshness does not prove data correctness. Only a restore drill validates formats, keys, and dependencies together. An automatic-deployment gate must inspect the latest verified backup rather than only the exit code of the latest upload command.
