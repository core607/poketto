# Source-Encrypted Backup Recovery

Date: 2026-09-01
Status: Proposed

This remains future work. [Phase-one delivery](2026-09-05-phase-one-daily-use.md) excludes backups and restore drills and retains all acknowledged managed originals without claiming off-host recovery.

## Problem

The [off-host backup and restore proposal](2026-08-27-off-host-backup-and-restore.md) originally left backup payloads unencrypted in v1 and trusted private target access control. That makes every recovery provider an additional plaintext trust domain for remote repository history, managed images, and relational account data. A consumer service needs a confidentiality boundary that survives disclosure of backup-target credentials without pretending that lost encryption keys are harmless.

## Proposal

Every backup artifact is encrypted and authenticated before it leaves the source trust boundary. This reverses only the earlier confidentiality choice; the off-host backup proposal continues to own media selection, retention, manifests, restore ordering, and drills.

Encryption uses versioned operator-controlled recovery material independent from backup-target credentials. Recovery material never enters application or content repositories, backup bundles, logs, metrics, or the target credential store. At least one tested copy exists outside the application host and outside the backup target's credential boundary. Rotation retains the material required by every recovery point still within retention.

Restore fails explicitly when recovery material is missing, incorrect, or cannot authenticate an artifact. A freshness signal cannot report a backup as healthy until an isolated restore drill has decrypted, authenticated, and verified the current format. Losing every retained copy of required recovery material makes the affected backups unrecoverable.

This decision protects backup artifacts, not live authorities. The remote Git provider, ManagedBlobStore, and PostgreSQL service retain their own access-control and encryption responsibilities; this proposal does not claim end-to-end encrypted Git hosting or protect plaintext already readable on a compromised application host.

## Implementation scope and dependencies

Encryption and key-version metadata are part of each backup-medium task in the off-host backup proposal rather than a separate deployment subsystem. The implementation adds source-side streaming encryption, authenticated manifests, key-version selection, rotation-safe retention, explicit recovery failures, and empty-environment restore tests. It does not select a hosted key-management provider, require a hardware security module, or automate recovery-material escrow.

## Alternatives considered

**Rely on private target access control.** This keeps restore dependent on one target credential, but compromise of that credential exposes every retained workspace and history snapshot in plaintext.

**Use only provider-managed encryption at rest.** This protects storage media while leaving the provider and target credentials inside the plaintext trust boundary. Source-side encryption keeps artifact contents independent from those credentials.

**Encrypt only managed objects and PostgreSQL dumps.** Repository history can contain the same private content and repository-managed images. Mixed confidentiality guarantees make target selection and incident response harder to reason about.

## Acceptance

- Every repository, ManagedBlobStore, and PostgreSQL backup artifact is encrypted and authenticated before transfer to its recovery target.
- Target credentials alone cannot read backup contents. Recovery material alone cannot locate or mutate the target.
- Missing, incorrect, retired-too-early, or corrupted recovery material fails restore explicitly and cannot produce a healthy freshness signal.
- Rotation preserves the ability to restore every recovery point still covered by retention, and an empty-environment drill proves the selected artifact and key versions together.
- Logs, metrics, manifests visible at the target, committed examples, and diagnostics expose no recovery secret or plaintext content.
- `./gradlew repoCheck`, the relevant backup tests, and `git diff --check` pass.

## Risks

Source-side encryption makes key custody part of availability. A valid backup with a lost key is intentionally unreadable, so recovery-material inventory, rotation, off-host copies, and restore drills are production obligations.

Encryption does not hide artifact size, timing, target access patterns, or all metadata. It also does not protect live plaintext from a compromised source host.
