# Existing-installation deployment

Set `POKETTO_DEPLOY_LAYOUT=existing` to update an operator-owned Compose project.
`transfer.sh --existing` invokes the installed protected updater; it never
replaces that executable. The deployment account needs Docker access and
noninteractive sudo for `/usr/local/sbin/poketto-update-existing`.

Every installation, including one with execution disabled, must install
[update-existing.py](update-existing.py) as `/usr/local/sbin/poketto-update-existing`
and [worker_release.py](worker_release.py) beside it. Both files must be root-owned
and unwritable by other accounts.

Keep the deployment root and its `deployment.json` root-owned and unwritable by
other users. Use [existing.example.json](existing.example.json) for the project,
ordered relative Compose files and loopback HTTP health checks. The worker
extension below is required when `POKETTO_EXECUTOR_ENABLED=true`.

The updater accepts `--root`, `--app-image`, `--frontend-image`, and the full
`--app-revision`. Both loaded images must carry that revision label. `--check`
validates the candidate without replacing services. Image delivery uses the
canonical registry (`POKETTO_DEPLOY_MODE=pull`) or checksummed SSH archives
(`transfer`). Registry credentials are used only for pulling images and removed
afterwards. [Usage](../docs/usage.md#deployment) owns supported identity settings
and CI configuration.

Original Compose files stay in place. Manual Compose commands must include the
protected `.deployment/images.json` overlay last. An interrupted update remains
pending: retry the same images, revision and configuration. A different target
requires inspection of protected state and running services. There is no automatic
application rollback across unknown persistent-format changes.

## Worker source updates

Existing-layout deployments update the configured worker from `/opt/poketto-worker`
in the selected application image. In the protected
`deployment.json`, add an `executor` object like this, using the installed paths:

```json
{
  "sourceDirectory": "/opt/poketto-executor",
  "configFile": "/etc/poketto-executor/config.json",
  "service": "poketto-executor.service",
  "healthUrl": "http://127.0.0.1:8080/actuator/health/executor",
  "probeTokenFile": "/etc/poketto-executor/deployment-token",
  "probeCopyId": "00000000-0000-4000-8000-000000000001"
}
```

The service must invoke `sourceDirectory/poketto-supervisor --config configFile`;
`ExecStopPost` uses the same command with `--cleanup` appended.
The worker configuration's `launcher` must be `sourceDirectory/launcher.py`.
Provision the native service before enabling automatic updates. Adopting it from a
Python service is a separate operator action: preserve the old unit, runtime and
protected configuration for recovery, install the native bundle and matching unit,
then verify startup and execution. The updater rejects the Python service contract;
it never rewrites a unit or private configuration. Install the matching protected
updater before changing the bundle's file set. Missing executor configuration blocks
an execution-enabled deployment.

Replace the example copy ID with an existing copy reserved for deployment checks.
Store its workspace API key in the root-owned `probeTokenFile` with mode 0600;
it needs repository execution and read access to that copy's scope. After protocol
health passes, the updater calls `/mcp` on the same loopback port and runs only
`poketto --help` plus a fixed confirmation marker. It neither creates nor discards
copies. Failed execution leaves deployment pending. Tokens never enter images,
CI settings, arguments or output; reconfigure the probe when its key or copy is retired.

The updater verifies the bundle, stages complete generations in
`sourceDirectory/releases/`, and converts the runtime source files to links
through `releases/current`. It stops the service before atomically switching
generations and starting it. Private configuration, keys, tools, quotas and copy
storage are untouched. The supervisor lives beside the launcher; sandbox programs live in its protected
`bin/` directory, resolved beside its launcher; the updater verifies their checksums,
x64 ELF format and executable permissions. Unchanged runtime files keep the running
worker. `--check` validates and stages files without changing the selector or restarting services.

A failed start restores the previous generation; a failed application update or
health check remains pending. Retry the same images and configuration to finish
an interrupted deployment. Prior source generations are retained; recovery across
application or persistent-format changes requires operator inspection. Readiness
and `/actuator/health/executor` check the application's live worker handshake,
without creating copies or running user commands. They are `DOWN` on an unavailable
or incompatible worker; disabled execution is `UP`. See
[coordinated delivery](../notes/implemented/2026-10-19-coordinated-worker-delivery.md)
for the delivery boundary.

