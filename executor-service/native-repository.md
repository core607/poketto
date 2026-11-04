# Native repository execution

Set `POKETTO_EXECUTOR_BACKEND=native` with `POKETTO_EXECUTOR_ENABLED=true` to use a
[Sandcrab service](rust/sandcrab/README.md) for `repo_exec`. An existing installation
uses the signed worker until its operator selects the native backend.

## Application settings

| Setting | Contract |
|---|---|
| `POKETTO_EXECUTOR_SOCKET` | Root-owned native Unix socket reachable by the application UID. Mount its protected parent directory read-only so a restarted daemon can replace the socket. |
| `POKETTO_EXECUTOR_PROFILE` | A repository profile with network disabled, host calls enabled, Git, bash and Python. The default name is `repository`. |
| `POKETTO_EXECUTOR_STAGING_DIRECTORY` | Private application directory for authorized Git exports. |
| `POKETTO_EXECUTOR_TRANSFERS_DIRECTORY` | Private application directory for bounded file transfers. `POKETTO_EXECUTOR_TRANSFERS_MAX_BYTES` defaults to 1 GiB. |
| `POKETTO_EXECUTOR_COPIES_METADATA_ROOT` | Application-owned account journals and acknowledged changes on a bounded XFS filesystem. |
| `POKETTO_EXECUTOR_COPIES_BYTES`, `POKETTO_EXECUTOR_COPIES_INODES` | Byte and entry ceilings for an acknowledged writable layer. The profile's transfer limit also bounds its archive. Imported volumes require kernel-enforced block and inode quotas. |

Give the daemon an overlay-capable runtime filesystem and enough template capacity
for the commits in use. Keep the game service on its own socket.
The [offline importer](native-migration.md) prepares existing signed working copies
for the native backend. Record the current image identities and a recoverable backup
of the legacy data before the write pause and import.

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
