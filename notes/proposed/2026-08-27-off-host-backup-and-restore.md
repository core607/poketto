# Off-Host Backup and Restore

Date: 2026-08-27
Status: Proposed

This remains future work. [Phase-one delivery](../implemented/2026-09-05-phase-one-daily-use.md) excludes backups and restore drills and does not require them as an implementation or deployment gate.

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) require off-host copies of content history, images, and non-derived PostgreSQL tables but do not define confidentiality boundaries, retention, recovery points, failure visibility, or restore drills. Running a second `git push`, copying the data directory, or invoking `pg_dump` does not prove that data survives provider loss and may propagate a deletion into the supposed backup. Encryption and key custody are decided here; an earlier choice to leave payloads unencrypted behind target access control was reversed on 2026-09-01.

[Remote repository authority](../implemented/2026-09-01-remote-repository-authority.md) keeps Markdown and repository images off the application host. Images and files uploaded through Poketto are managed originals: the [local store](../implemented/2026-09-05-repository-authoring-foundations.md#managed-originals-and-image-delivery) keeps their bytes and an fsynced operation ledger per workspace on the application host, and the [logical media index](../implemented/2026-09-09-logical-media-index.md) records their references in each repository's `.poketto/assets.json`. Backup must protect the remote repository, the managed-original store, non-derived PostgreSQL state, and the deployment key needed to read encrypted rows, in independent recovery boundaries; disposable caches add no recovery source.

## Proposal

### Common rules

- Production configuration names recovery targets independent from the authoritative Git provider, the application host, and PostgreSQL. A target may be a self-hosted service or operator-selected third-party storage. Normal application operation does not require continuous backup-target availability.
- Every backup is encrypted and authenticated before it leaves the source trust boundary. Encryption uses operator-controlled recovery material independent from target credentials. That material never enters the code repository, content repositories, backup bundles, logs, or metrics and must have a tested copy outside the application host; losing every copy makes the backup unrecoverable.
- Recovery material is versioned: each artifact and manifest names the key version it needs, and rotation retains the material required by every recovery point still within retention. Restore fails explicitly when recovery material is missing, incorrect or cannot authenticate an artifact, and a freshness signal cannot report a backup as healthy until an isolated restore drill has decrypted, authenticated and verified the current format. This protects backup artifacts, not live authorities: it does not select a hosted key-management provider, require a hardware security module or automate recovery-material escrow.
- Each medium records the last successful time, covered workspaces, source checkpoint, target identifier, byte count, and sanitized failure category. A new failure cannot erase the latest successful recovery point.
- Backups retain versions for a defined period. The default flow does not immediately propagate a source deletion into every off-host copy; cleanup is a separate delayed and auditable retention task.
- Documentation starts a restore from an empty data directory. Automated checks or scheduled drills for each medium prove that artifacts are readable, checksums match, and the application can reach the declared checkpoint.

### Remote content repositories

- The backup job reads the authoritative remote repository and copies all required refs and reachable objects into an independent retention boundary. The target forbids routine force pushes and retains enough history to recover from repository deletion, credential compromise, or an incorrect ref update.
- Restore creates or selects a replacement private remote repository, verifies its objects and refs, and atomically binds the workspace authority to it through an operator-controlled recovery procedure. No application cache is promoted into authority by guessing that it is newer.
- Repository images and each repository's `.poketto/assets.json` are covered with the commits that contain them. Their disposable materialization caches are excluded from backup.

### Managed originals

- Backup copies each workspace's managed-original directory, the immutable objects together with the operation ledger that maps upload keys and revisions to them, plus a verifiable manifest of workspace, identity and revision, content hash, byte size and media type. Every acknowledged original is retained today, so no reclamation hold is needed until physical cleanup exists.
- Restore verifies every managed object hash before making it available. A restored repository is consistent only when every revision its `.poketto/assets.json` references is present; missing or corrupt managed bytes are reported rather than replaced with a placeholder or re-encoded image.
- A bare storage `sync` that immediately propagates deletions cannot be the only backup. Any future asset cleanup and backup retention are separately delayed and auditable.

### PostgreSQL

- Non-derived tables must be recoverable: accounts, site groups and their change history, external identities, memberships, workspace invitations, API-key and OAuth connection metadata, the workspace catalog, repository bindings with their encrypted credentials, GitHub App grants, creation attempts and revocation epochs, community interactions, reader corrections and view counts. Secrets retain their hashed or encrypted storage contract. Browser sessions, email challenges and rate-limit rows may be lost; a restore then signs people out and expires outstanding codes.
- Repository credentials and GitHub App grants are encrypted with `POKETTO_REPOSITORY_CREDENTIAL_KEY`. A database backup is usable only with that key, so the key is recovery material under the custody rules above and never travels inside the backup it unlocks.
- Audit records are `poketto.audit` log output, not database rows; retaining them is a logging decision outside this proposal.
- The implementation may create a whole-database dump, but PostgreSQL contains no document or content-search projection under [repository-native retrieval and sandboxed agent execution](../implemented/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). Disposable read caches and execution snapshots are rebuilt from the restored content repositories rather than backed up.
- A dump manifest records database schema version, creation time, and covered workspaces. Restore verifies schema, row-count invariants, and critical foreign keys in an isolated database before replacing production data.

## Implementation scope and dependencies

Content backup depends on remote repository authority, managed-original backup on the local store and its ledger, and database backup on the schema-management mechanism; all three exist. The media have no common implementation prerequisite and therefore become separate tasks. Production automatic deployment may check a recent verified backup only after all three exist and pass one combined restore drill. Derived repository, repository-image, read, and execution caches add no backup task. [Account working copies](../implemented/2026-09-14-account-working-copies.md) hold unsaved edits on the host and are excluded, so a host loss loses unsaved work.

The first implementation provides backup commands, retention rules, machine-readable state, restore commands, and restore tests in disposable environments for the available media. It excludes multi-region hot standby, automatic failover, continuous database archiving, and a zero recovery-point objective.

## Alternatives considered

**Treat the authoritative Git provider as the backup.** Git contains repository text, repository images, and history, but provider deletion, credential compromise, or a mistaken ref update can damage the same authority. It contains neither managed originals nor authoritative PostgreSQL rows.

**Run `rclone sync` over the whole data directory.** This is simple but copies temporary files, runtime locks, and derived state and may propagate deletions. Verifiable artifacts per medium make the recovery boundary explicit.

**Back up disposable read caches and execution snapshots.** This could reduce first-read latency after restore, but it copies private transient data and sandbox state without adding durability. Rebuilding both from restored repositories keeps recovery artifacts smaller and removes stale execution state.

**Rely only on a manual restore guide.** Documentation cannot prove that current commands still read current formats. Disposable restore tests expose drift in schemas, manifests, and paths.

**Rely only on target access control or provider-side encryption.** This would let target credentials expose every retained private workspace and would couple confidentiality to provider configuration. Source-side encryption keeps backup contents outside that trust boundary; tested recovery material is an explicit operational prerequisite rather than an omitted one.

**Encrypt only managed originals and PostgreSQL dumps.** Repository history can contain the same private content and repository images; mixed confidentiality guarantees make target selection and incident response harder to reason about.

## Acceptance

- Remote repository authority, managed originals, and non-derived PostgreSQL data can retain encrypted, versioned, verified recovery points in independent failure domains. A missing target or recovery key fails explicitly and cannot report success.
- Consecutive failures do not delete the latest successful recovery point. Status exposes the last success and current lag without revealing paths, content, or credentials.
- A restore into an empty application data directory produces a replacement remote repository whose commits match the repository manifest, managed originals whose hashes match their manifest, and non-derived database constraints matching their manifest; restored repository bindings decrypt with the recovered credential key.
- Restore excludes disposable repository, repository-image, read, and execution caches. The first repository-backed read resolves restored remote `main`; a repository image rematerializes from its exact Git blob, and a reference in `.poketto/assets.json` resolves its restored original.
- After a source deletion or ref move, at least one recovery point covered by retention remains retrievable.
- Backup and restore tests use disposable repositories, storage, and PostgreSQL and never read developer data.

## Risks

Encrypted backups still expose sizes, timing, and target access patterns, and a compromised application host can read live plaintext. Target credentials and recovery material are separate secrets; neither replaces access control on the authoritative stores.

Lost recovery material makes otherwise healthy backup snapshots unreadable. Key availability, rotation, and an empty-environment restore drill are part of backup health rather than operator folklore.

Backup freshness does not prove data correctness. Only a restore drill validates formats, keys, and dependencies together. An automatic-deployment gate must inspect the latest verified backup rather than only the exit code of the latest upload command.
