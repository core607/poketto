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
Containers log under `SYSLOG_IDENTIFIER=poketto-<container>`. The executor and game runner are the
`poketto-executor.service` and `sandcrab-games.service` units, and each command sandbox is a
`poketto-exec-…` unit in their slice.

## Executor command timings

Command result timings appear in the application's `repo_exec stages` records as
`workerRoomMs`, `workerLockMs`, `workerPrepareMs`, `workerRunMs`, `workerReleaseMs`
and `workerFlushMs`.

`workerRunMs` includes a fresh sandbox's startup. Within it, `workerReadyMs` measures
the wait for the shell's greeting (zero on reuse), and `workerCommandMs` measures
command delivery and response handling after that greeting. A failure before the
greeting reports the ready wait and omits `workerCommandMs`. Both share the command's
timeout budget; these are elapsed times, not command CPU time.

## Executor request events

The application's `poketto.WorkerRequestPreparation` JFR event records signed-request preparation:
operation/type, payload byte count, serialization/signing elapsed time and whether an envelope was
prepared. Preparation does not prove a request was sent; unsigned handshakes are excluded.

`poketto.WorkerBridgePoll` separates authorization and request elapsed time and records whether a
reply arrived, reported success or contained a bridge request. These flags precede lease validation.
Its request time includes preparation and the socket exchange, and a poll can finish after its
command. These events contain no payloads, keys or caller identities. Their elapsed durations
overlap across requests and are not CPU time.

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

Live command sandboxes, in PromQL:

```
count(node_systemd_unit_state{name=~"poketto-exec-.+\\.service", state="active"} == 1) or vector(0)
```

Application warnings, the slowest `repo_exec` calls and the most frequent problems, in LogsQL:

```
SYSLOG_IDENTIFIER:~"^poketto-app" | unpack_json | log.level:WARN | fields _time, message
SYSLOG_IDENTIFIER:~"^poketto-app" "repo_exec stages" | unpack_json | stats quantile(0.95, totalMs) p95, count() calls
_time:1h SYSLOG_IDENTIFIER:~"^poketto-app" | unpack_json | log.level:in(WARN, ERROR) | stats by (message) count() hits | sort by (hits desc)
```

The [load suite](../../acceptance/README.md) takes these sources directly: `--prometheus
http://127.0.0.1:9090` with the sandbox query above as `--live-units`, and `--logs
http://127.0.0.1:9428` with `--app-logs 'SYSLOG_IDENTIFIER:~"^poketto-app"'`.

## Verification

`./gradlew observabilityConfigCheck` validates the Compose file, the Prometheus configuration and
rules, and the Fluent Bit configuration with the pinned images. It then ships a Poketto record and
an unrelated record through the real filter into VictoriaLogs and requires only the first to arrive.
`PostgresIntegrationIT` and `WorkerSocketTests` require every application metric that `alerts.yml`
or this file names to appear in the application's output.
