# Optional monitoring

Poketto exposes Prometheus metrics and structured logs. This directory is a single-host stack that
collects both. The project's own deployment does not run it: `deploy.sh` and the CI delivery never
copy or start these files. Use it as is, or point an existing monitoring system at the same two
sources. The [monitoring-ready record](../../notes/implemented/2026-10-19-monitoring-ready-deployment.md)
explains that choice.

## Sources

**Metrics.** Set `POKETTO_METRICS_ENABLED=true` in the deployment's `.env`, through
`deploy.sh --set-stdin`, or through the existing-installation updater. The application then serves
`/actuator/prometheus` on its loopback port, `127.0.0.1:${POKETTO_HTTP_PORT:-8080}`. The gateway
answers 404 for every `/actuator` path, so the endpoint is never public. With the setting off, the
endpoint does not exist. The output includes:

- JVM, process and HTTP server metrics, such as `jvm_memory_used_bytes` and
  `http_server_requests_seconds_count` by status and route;
- the database pool, such as `hikaricp_connections_active` and `hikaricp_connections_pending`;
- image admission: `poketto_images_admission_reserved_bytes`, `poketto_images_admission_waiters`
  and `poketto_images_admission_rejected_total`;
- with the executor enabled: `poketto_executor_sessions_active`, `poketto_executor_operations_active`,
  `poketto_executor_sessions_total` (sessions created; the exporter drops a trailing `created`),
  `poketto_executor_sessions_released_total` and `poketto_executor_admission_rejected_total` by
  `reason`.

**Logs.** Every service writes to the host journal. Application lines are one ECS JSON record each;
[Diagnostics](../../docs/usage.md#diagnostics) owns what the records contain and never contain.
Containers log under `SYSLOG_IDENTIFIER=poketto-<container>`. The repository and game services
use the operator's configured systemd units. Native sandboxes use delegated cgroups inside those
services. Adapt node exporter's unit selector and the service alert to those unit names.

## Native execution phases

`poketto.NativeRepositoryPhase` is an optional JFR event, disabled by default. Enable it
explicitly with threshold zero and stack traces disabled. Its fixed phase names cover the
request, authorization and admission, template lookup/build, parent start/renewal, child
startup and containment, setup, command wait, upper restore/capture/validation, journal
commit/release and session containment. Parent reuse skips restoration; each command still
creates a child and persists the upper before acknowledging success.
`NATIVE_EXCHANGE` measures one socket exchange and its response reader within these phases,
separately from consumer polling waits and the whole request. It carries no endpoint or request body.

Events carry a process-local `invocation`, `phase`, `parentPhase` and `depth`. They contain
no command, path, account, copy or credential. Durations are inclusive and nested; do not
sum them. A phase completing does not establish a successful request: correlate its time
window with the actual HTTP/MCP outcome. Background containment can have its own root.

`threadCpuNanos` measures only the calling platform thread, excluding asynchronous work,
Sandcrab and the executed command. It is `-1` when unavailable, including virtual threads;
recording never enables JVM-wide thread CPU measurement. `transferredBytes` is the archive
payload for template creation, restore, capture and journal commit, or `-1` when unknown. It is not physical
disk I/O. The [isolated replay](../../acceptance/clients/README.md) records real outcomes,
cgroup CPU, throttling, I/O, pressure and memory alongside these phases and checks recording
completeness. Compare enabled, disabled and uninstrumented runtimes before attributing costs.

## Repository read events

`poketto.RepositoryReadResolution` covers read-side main resolution before the reader callback:
workspace-lock wait, remote-fetch time, and `cacheReused`, `fetchAttempted` and `resolved` flags.
Reuse can use a known commit or the configured recent-fetch window. Failed attempts retain their
elapsed time; `resolved` does not claim the subsequent reader succeeded.

This event adds only elapsed times and booleans, with stack traces disabled. It changes neither
admission nor fetch reuse. Its duration can overlap a native execution phase and must not be
added to that phase's duration.

## Running this stack

Copy this directory to the host and run `docker compose up -d` in it. Every service shares the host
network and listens on loopback only.

| Service | Address | Collects | Retention |
|---|---|---|---|
| Prometheus | `127.0.0.1:9090` | the application, node exporter and itself | `POKETTO_METRICS_RETENTION`, default `15d` |
| node exporter | `127.0.0.1:9100` | host resources and the state of every `poketto*` systemd unit | none |
| VictoriaLogs | `127.0.0.1:9428` | journal records shipped by Fluent Bit | `POKETTO_LOGS_RETENTION`, default `30d` |
| Fluent Bit | none | journal records from Poketto containers and units, starting at the current end | its cursor only |

Reach the interfaces through SSH, for example `ssh -L 9090:127.0.0.1:9090 -L 9428:127.0.0.1:9428 HOST`,
then open `http://127.0.0.1:9090` and `http://127.0.0.1:9428/select/vmui`. Change the application
target in `prometheus.yml` when `POKETTO_HTTP_PORT` is not 8080.

`alerts.yml` holds example rules. Prometheus evaluates them and lists firing alerts on its Alerts
page; nothing sends a notification until an `alerting` section in `prometheus.yml` names an
Alertmanager.

## Queries

Application parent-session admissions, in PromQL:

```
poketto_executor_sessions_active{job="poketto-app"}
```

Application warnings and the most frequent problems, in LogsQL:

```
SYSLOG_IDENTIFIER:~"^poketto-app" | unpack_json | log.level:WARN | fields _time, message
_time:1h SYSLOG_IDENTIFIER:~"^poketto-app" | unpack_json | log.level:in(WARN, ERROR) | stats by (message) count() hits | sort by (hits desc)
```

The [load suite](../../acceptance/README.md) takes these sources directly: `--prometheus
http://127.0.0.1:9090` with an operator-supplied sandbox count query as `--live-units`, and `--logs
http://127.0.0.1:9428` with `--app-logs 'SYSLOG_IDENTIFIER:~"^poketto-app"'`.

## Verification

`./gradlew observabilityConfigCheck` validates the Compose file, the Prometheus configuration and
rules, and the Fluent Bit configuration with the pinned images. It then ships a Poketto record and
an unrelated record through the real filter into VictoriaLogs and requires only the first to arrive.
`PostgresIntegrationIT` and `NativeRepositoryMetricsTests` require every application metric that `alerts.yml`
or this file names to appear in the application's output.
