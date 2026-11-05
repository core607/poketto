# Existing-installation deployment

Set `POKETTO_DEPLOY_LAYOUT=existing` to update an operator-owned Compose project.
`transfer.sh --existing` invokes the installed protected updater; it never
replaces that executable. The deployment account needs Docker access and
noninteractive sudo for `/usr/local/sbin/poketto-update-existing`.

Every installation, including one with execution disabled, must install
[update-existing.py](update-existing.py) as `/usr/local/sbin/poketto-update-existing`,
with [native_repository.py](native_repository.py)
beside it. Both files must be root-owned and unwritable by other accounts.

Keep the deployment root and its `deployment.json` root-owned and unwritable by
other users. Use [existing.example.json](existing.example.json) for the project,
ordered relative Compose files and loopback HTTP health checks. Enabled repository
execution requires the native service configuration.

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

## Repository service

Enabled execution requires the [independent native service](../executor-service/native-repository.md#existing-installation-delivery)
and its `nativeExecutor` configuration. The updater verifies the installed
dependency before and after replacing application containers. A pending legacy
worker deployment requires operator reconciliation before native adoption.

Reserve an existing copy for deployment checks. Store its workspace API key in a
root-owned `probeTokenFile` with mode 0600 and configure its `probeCopyId`.
The grant needs repository execution and read access to that copy's scope.
After readiness passes, the updater calls `/mcp` on the configured loopback port
and runs `poketto --help` with a fixed confirmation marker on that copy.
Failed execution leaves deployment pending. Reconfigure the probe when its key
or copy is retired. Keep the token in protected host configuration.

An interrupted update requires the same images, configuration and native
dependency. The operator owns service installation, upgrades and recovery.
The application's `/actuator/health/executor` checks the live native API and
selected profile. Enabled execution is `DOWN` when that dependency is unavailable;
disabled execution is `UP`.
