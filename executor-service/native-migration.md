# Offline native repository import

`migrate_native_repository.py` prepares and explicitly executes one retained-copy import. This is a privileged, offline storage operation. It does not start Spring, obtain a user token, execute a repository command, recover a Git write, activate a backend, or delete the legacy source. Normal repository access continues to require current user authorization.

Build `./gradlew stageRepositoryMigrationOperator` and install `build/repositoryMigrationOperator` in a protected, app-readable directory. It contains the Java runtime, controller and support modules. The [deployment updater](../deploy/update-existing.py) owns the state and lock that the controller checks. The controller needs Linux, Python 3.12+, Java 26, systemd/cgroup v2, Docker, `findmnt`, `xfs_io`, and the existing quota-enforced XFS installation. Keep the bundle at one reviewed revision. Machine-specific requests, paths, identifiers and receipts belong in private operator storage.

## Request

The controller accepts a root-owned JSON file, closed to modification by other users. All paths are canonical absolute paths. The request has exactly these fields:

| Field | Value |
|---|---|
| `operation`, `revision` | A new UUID and the exact helper source commit |
| `installationRoot`, `legacyConfig` | Existing deployment root and protected legacy supervisor configuration |
| `appUid`, `appGid` | Existing application account, matching the supervisor configuration |
| `java`, `runtime` | Protected executable and staged helper directory |
| `metadata`, `limits` | Existing account-journal XFS directory and its configured bounds |
| `owner`, `copyId` | Exact account/workspace/read-scope/fork identity and retained copy UUID |
| `socket`, `profile` | Protected native endpoint and advertised repository profile with host calls enabled |
| `volumeBytes`, `volumeInodes` | Explicit target volume bounds |

`owner` contains `accountId`, `workspaceId`, `fullRead`, and nullable `fork`. `limits` contains `copies`, `recordBytes`, `poolBytes`, `idleMillis`, and `originalFiles`; the last contains `archiveBytes`, `expandedBytes`, and `entries`. Use the installed values rather than inferring bounds from current usage. The helper checks the real journal codec, filesystem type, owner, file modes and held lock.

## Prepare and execute

Stop the installation's application container and legacy supervisor through the existing operating procedure before either command. The controller does not stop or reconfigure them. It acquires the exact `installationRoot/.deployment/lock` used by [the updater](../deploy/update-existing.py), an operation lock, and the legacy source FLOCK. It verifies stopped services, empty descendants, source unmount, source identity/project quota and private roots. The app-UID helper holds the real account-journal lock throughout archive preparation and import. Inherited source/deployment/operation lock descriptors remain held if the parent controller disappears; a lost control pipe prevents further acknowledgements.

Run the controller with `--request` and `--operation`, naming a protected request and a new private operation directory. Without `--execute`, it fixes two archives and a plan without writing the account journal or creating native resources. Inspect the private request, source inventory, archive digests and exact plan before invoking the same command with `--execute`.

Only unexpired `READY` copies without an active execution or pending uncertain save, move or synchronization qualify. Other states are deferred with the source and journals retained. The complete old `work` and `home` become separate native roots; commands use `/workspace/work/repository` and `HOME=/workspace/home`. Host-owned copy metadata, immutable application baselines and disposable runtime files are not placed in the guest volume. Root modes must be `0700`. External Git directories or alternates, special nodes, extended attributes, special permission bits, cross-root hardlinks, hardlinks outside the captured roots and exceeded archive bounds defer the operation. Each archive is bounded to 1 GiB, with a combined 10,000-entry/1 GiB source bound.

Prepare and execute require a healthy deployment state under the updater's lock. The stopped application inventory must include its recorded image, and the configured legacy supervisor must still be installed. The operation pins the original state bytes by SHA-256. Reconcile a missing image, changed state or pending deployment through the updater before continuing.

Prepare and execute require the target service to report kernel-enforced quotas for the imported volume. Execute rechecks that service epoch, the fixed source, archives and expected journal. It uses the same volume, session and upload IDs, confirms both durable transfers and native session containment, then changes the writer once. Owner, copy ID, expiry, save state, original baseline and public projection remain unchanged. The source remains after success and must not become a writable fallback after native edits.

## Deferred copies and retirement

Keep the previous application image, supervisor, configuration and storage backup
until every retained account journal has a native binding. A deferred legacy
record still counts toward the account's copy quota. Native execution, disposal
and expiry leave it untouched and cannot complete its migration.

Before cutover, reconcile uncertain writes through the retained signed deployment
while it is the sole writer. Use its authorized disposal or expiry path for copies
that should be removed. Stop both old writers again, inventory the remaining
journals and prepare fresh import requests for eligible copies. Resolve every
deferred record before activating native authoring.

After native writes begin, never resume the old writer against mixed live
journals or delete a legacy journal to bypass its quota. The current operator has
no post-cutover retirement command for a deferred record; retain its source and
receipts for offline reconciliation. [The follow-up](https://github.com/core607/poketto/issues/411)
tracks this limitation and its account-quota impact.

## Uncertain outcomes

Timeouts observe the same helper process; the controller never starts a concurrent replacement. Failed preparation keeps candidate files for explicit reconciliation. It does not silently rebuild archives after source changes. Both archive uploads request `keepResult`. Their receipts survive the service result TTL until the journal commits, including when an upload response is lost. Waiting, receiving, publishing, failed or unknown uploads, or an incomplete old-epoch plan, require explicit operator reconciliation. They are not resent under replacement IDs. A lost journal response is checked against the same published writer and retained state.

`linuxStorageTest` covers filesystem rejection, controller disconnects, inherited locks and contention with the updater. The [journal probe](../executor-native/native_migration_journal_probe.py) injects a lost upload response. The [host rehearsal](../executor-native/legacy_import_probe.py) invokes the controller against disposable Docker, systemd, XFS and Sandcrab fixtures, compares both copies independently and checks a second execution of the same plan.
