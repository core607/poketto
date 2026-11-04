# Native repository execution

With `POKETTO_EXECUTOR_ENABLED=true`, `repo_exec` uses the configured
[Sandcrab service](rust/sandcrab/README.md). The application accepts the native
backend and requires the [offline import](native-migration.md) for existing signed copies.

## Application settings

| Setting | Contract |
|---|---|
| `POKETTO_EXECUTOR_SOCKET` | Root-owned native Unix socket reachable by the application UID. Mount its protected parent directory read-only so a restarted daemon can replace the socket. |
| `POKETTO_EXECUTOR_PROFILE` | A repository profile with network disabled, host calls enabled, Git, bash and Python. The default name is `repository`. |
| `POKETTO_EXECUTOR_TEMPLATE_PROFILE` | Offline profile for the disposable Git checkout that prepares an immutable template. Defaults to the repository profile. Allow disk space for the exported bundle, Git objects and checkout to coexist. This profile can disable host calls. |
| `POKETTO_EXECUTOR_STAGING_DIRECTORY` | Private application directory for authorized Git exports. |
| `POKETTO_EXECUTOR_TRANSFERS_DIRECTORY` | Private application directory for bounded file transfers. `POKETTO_EXECUTOR_TRANSFERS_MAX_BYTES` defaults to 1 GiB. |
| `POKETTO_EXECUTOR_COPIES_METADATA_ROOT` | Application-owned account journals and acknowledged changes on a bounded XFS filesystem. |
| `POKETTO_EXECUTOR_COPIES_BYTES`, `POKETTO_EXECUTOR_COPIES_INODES` | Byte and entry ceilings for an acknowledged writable layer. The profile's transfer limit also bounds its archive. Imported volumes require kernel-enforced block and inode quotas. |

Give the daemon an overlay-capable runtime filesystem and enough template capacity
for the commits in use. Keep the game service on its own socket.
The [offline importer](native-migration.md) prepares existing signed working copies
for the native backend. Record the current image identities and a recoverable backup
of the legacy data before the write pause and import.

## New Compose installations

Install the pinned Sandcrab release as a root service with cgroup delegation in a
dedicated, bounded slice. Set its Unix socket group to the application GID. Install
Poketto's [resource-pool checker](resource_pool.py) at
`/usr/local/lib/poketto/resource_pool.py` with root ownership and mode 0644.
The deployment account needs permission to connect to the socket, for example
through the application group. The checker verifies the peer's root UID, main
PID, cgroup and slice limits before any container replacement.

Create separate mode 0700 directories for exports, account metadata and transfers
on a bounded XFS filesystem, owned by the application UID. In the
[deployment settings](../deploy/.env.example), set their host paths, the service
and socket directory, and the byte and inode limits for each copy. Enabling
`POKETTO_EXECUTOR_ENABLED` selects this service in the Compose overlay. Existing
signed installations must first follow the [offline import](native-migration.md).
After verifying the import, remove `POKETTO_EXECUTOR_SIGNING_KEY_HOST`,
`POKETTO_EXECUTOR_OPEN_TIMEOUT_SECONDS` and `POKETTO_EXECUTOR_CLOSE_TIMEOUT_SECONDS`
from the deployment configuration. The entrance rejects these retired keys.

## Existing-installation delivery

For `POKETTO_EXECUTOR_BACKEND=native`, reserve a repository copy for the [read-only MCP probe](../deploy/README.md#repository-service). Add a
`nativeExecutor` object to `deployment.json` with `service`, `healthUrl`,
`probeTokenFile` and `probeCopyId` fields. Set `service` to
the Sandcrab unit. Supply the absolute paths `binary`, `configFile` and `socket`,
and the SHA-256 of the installed configuration in `configSha256`.

The unit must run `binary serve --config configFile`. The updater checks the running
executable against the release pin in the candidate application image. Before and
after replacing containers it verifies the systemd process, socket peer and mount,
and queries the selected offline profile through the application container. The
operator owns Sandcrab installation and restarts. An interrupted deployment needs
the same release, configuration and probe settings.

For an [offline import](native-migration.md), create
`.deployment/maintenance` under the installation lock before stopping writers.
Both preflight and delivery refuse work while that file exists. Use the same lock
to remove it once the imported journals, native Compose bindings and running app
are ready for normal deployment verification. After native adoption, the updater
refuses to re-enable signed repository writes.

## Working copies

A new full copy starts at its authorized commit. A public copy starts at a separate
root commit containing its published projection. Copies of the same commit and
reading scope can share their immutable template. Each account copy has its own
writable layer and seven-day idle retention by default. Disposing the last copy
releases a template created by Poketto. A matching template already present in the
service remains with its operator.

Each template-backed command starts a fresh shell in `/workspace/work/repository`
with `HOME=/workspace/home` and reports `freshSandbox: true`. Keep work that the next
command needs in files. Poketto stops the command shell and its descendants before
capturing the writable layer, including deletions. A command result is acknowledged
after the account journal durably records the verified archive. A new application
process can then restore the same copy from that template and archive. A lost
response requires inspecting the copy and its host receipts before another write.
An imported volume continues to hold its files until explicit disposal or expiry.

## Repository commands

Use shell or Python for local edits. Write repository-relative paths in `poketto`
commands. `poketto status` reads the host's acknowledged baseline and receipts.
Add `--remote` to compare that baseline with remote main. A change in the published
projection fences the old public copy. Request `new` for the current projection.

`poketto sync prepare` and `poketto move prepare` produce candidates for inspection.
Use `poketto plan show` to list them, edit the working files, then use `accept` with
their expected hashes or explicit absences. Media `import` and `link` return an
index entry to add to `.poketto/assets.json`. Save it with the referring text.

`poketto save` passes the SHA-256 of every selected write to the host. If a
background writer changes a selected file before capture, the command returns
`COPY_CHANGED` and commits none of the selection. Check the current files before
submitting a new save. Remote Git grants and the per-path revision checks still
apply to each save.

An artifact belongs to its account, workspace, copy and reading scope. Its bytes
use the application transfer pool. A replacement shell can read the same handle
until removal, expiry, copy disposal or application restart. Each page rechecks
the caller's grant and the current public projection.
