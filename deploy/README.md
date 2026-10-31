# Existing-installation deployment

Set `POKETTO_DEPLOY_LAYOUT=existing` to update an operator-owned Compose project.
`transfer.sh --existing` invokes the installed protected updater; it never replaces
that executable. The deployment account needs Docker access and noninteractive
sudo for `/usr/local/sbin/poketto-update-existing`.

Install [update-existing.py](update-existing.py) at that path and
[native_service.py](native_service.py) beside it, including when execution is
disabled. Both files and their ancestors must be root-owned and protected from
other accounts. Keep the deployment root and `deployment.json` protected too.
[existing.example.json](existing.example.json) defines the project, ordered
relative Compose files and loopback health checks.

The updater accepts `--root`, two loaded image references and their full
`--app-revision`. Both images must carry that revision label. `--check` validates
the candidate without replacing services. Canonical registry pulls and checksummed
SSH archives use the same entrance. Credentials are used only for pulling images;
[usage](../docs/usage.md#deployment) owns identity settings and CI configuration.

Original Compose files stay in place. Manual Compose commands must include
`.deployment/images.json` last. The installation lock serializes updates with
offline copy maintenance. There is no automatic application rollback across
unknown persistent-format changes.

## Native service dependency

Sandcrab is installed and upgraded independently. Application images contain no
host sandbox programs, and the updater never installs, starts, stops or rolls
back the native service. Provision its tools, resource slice, volumes and
host-call-enabled repository profile using the [native installation contract](../executor-service/rust/sandcrab/README.md#installation).

When execution is enabled, `deployment.json` requires an `executor` object:

```json
{
  "binary": "/opt/sandcrab/bin/sandcrab",
  "binarySha256": "<installed binary SHA-256>",
  "configFile": "/etc/sandcrab/repository.toml",
  "configSha256": "<installed configuration SHA-256>",
  "poolChecker": "/opt/sandcrab/resource_pool.py",
  "poolCheckerSha256": "<installed checker SHA-256>",
  "service": "sandcrab-repository.service",
  "socket": "/run/sandcrab-repository/control.sock",
  "healthUrl": "http://127.0.0.1:8080/actuator/health/executor",
  "probeTokenFile": "/etc/sandcrab/deployment-token",
  "probeCopyId": "00000000-0000-4000-8000-000000000001"
}
```

Replace the digest placeholders and copy ID. Paths are canonical, without
symlinks. Configuration and token files have one link and owner-only permissions.
The service runs `binary serve --config configFile`. Verification checks pinned
files, the actual root MainPID and executable bytes, the socket peer's PID and
the resource pool. The app's declared bind, actual mounted socket and peer must
identify that same service. A config digest identifies the installed file, not every byte
an existing process loaded. Live API health and consumer checks remain separate.

The probe uses an existing copy reserved for deployment checks. Its root-owned
API-key file grants execution and read access to that scope. After health passes,
the updater calls the same loopback application's `/mcp`, running `poketto --help`
and a fixed marker. It creates or discards no copies. Keep this token out of
images, arguments, CI settings and output; replace the probe when its key or copy
is retired.

## Interrupted delivery and adoption

Schema 2 records a `sandcrab-native-v1` dependency separately from application
images. Failure remains pending. Retry requires the same images, rendered
configuration and dependency pins. A daemon restart with unchanged pins is
rechecked against its current process; no old execution receipt is reused.
An incorrect retry preserves the original pending candidate, overlay and state.
Health and the dedicated-copy command must pass before state becomes healthy.

Legacy pending state requires the original updater or explicit operator recovery
before changing backends or copy formats. It is never converted automatically.
For healthy legacy state, `--adopt-native-dependency` explicitly adopts schema 2,
preserves the exact original bytes in a checksummed private archive and retains
image history. `--check` writes no adoption archive. This flag does not migrate
copies or authorize a runtime-configuration change. The [delivery decision](../notes/implemented/2026-10-31-independent-sandbox-deployment.md)
owns these boundaries.
