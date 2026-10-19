# Monitoring-Ready Deployment

Date: 2026-10-19

## Problem

The application registered executor and image-admission meters but exposed only health, so no
scraper could read them, and a database pool exhausted by concurrent agents showed only in later
symptoms. The production host ran a Prometheus and a VictoriaLogs set up by hand, with no record in
the repository of what they collected or how. A self-hosting operator had no supported way to
connect monitoring, and the single maintainer has neither users nor time that would justify
watching dashboards or answering alerts.

## Decision

The deployment is ready for monitoring and runs none by default.

- `POKETTO_METRICS_ENABLED`, default false, turns on the Prometheus registry export. Management
  exposure lists `health` and `prometheus`, so with the export off the endpoint does not exist,
  and with it on `/actuator/prometheus` answers on the loopback application port. The gateway's
  404 for every `/actuator` path keeps it off the public origin. No other management endpoint is
  exposed.
- Logs keep the journald and ECS JSON contract of [service diagnostics](2026-09-14-service-diagnostics.md);
  nothing new is emitted.
- [deploy/observability](../../deploy/observability/README.md) is a single-host example: Prometheus
  with example alert rules and no notification channel, node exporter with Poketto's systemd
  units, VictoriaLogs, and Fluent Bit shipping only Poketto's journal records. `deploy.sh` and CI
  delivery never copy or start it.
- `observabilityConfigCheck` validates the example with its pinned images and ships one Poketto
  record and one unrelated record through the real filter into VictoriaLogs. Tests require every
  application metric the example names to appear in the application's output.

## Alternatives

- **Deploy the stack with Poketto.** It would cost host memory and upkeep for observations nobody
  reads, and would make one monitoring product part of the deployment contract.
- **Expose metrics unconditionally.** Loopback exposure is cheap, but an opt-in keeps the
  management surface at health for operators who never scrape.
- **A separate management port.** The gateway already refuses `/actuator`, and a second port would
  add a Compose mapping and a health-check change for no extra isolation on one host.
- **Dashboards and tracing.** Dashboard JSON that nobody runs drifts silently, and tracing has no
  second service to follow yet.
- **Vector or `systemd-journal-upload` as shipper.** Fluent Bit reads journal files without a
  `journalctl` binary in its image, and VictoriaLogs documents its JSON-lines intake.

## Consequences

An operator connects monitoring with one setting and either this directory or their own stack.
The example's node exporter systemd collector and journal reading run only on a real systemd host;
CI proves configuration and the shipping path, not those host integrations. Pinned images need
deliberate bumps. Prometheus exports the executor's `sessions.created` counter as
`poketto_executor_sessions_total`, because the exporter drops a trailing `created`.

## Verification

`PokettoApplicationTests` pins the endpoint's absence by default. `PostgresIntegrationIT` scrapes
it with the export on, and with `WorkerSocketTests` checks the metric names in the example.
`observabilityConfigCheck` covers the example configuration and shipping path, and the deploy
script and existing-installation tests carry the setting through both layouts.
