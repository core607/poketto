# Coordinated Worker Delivery

Date: 2026-10-19

## Problem

[Existing-installation delivery](2026-09-08-existing-installation-delivery.md)
preserves host configuration but excludes workers. Application deployment can
therefore leave repository execution rejecting the installed worker.

## Decision

Execution-enabled deployments take their worker runtime from the selected image. Before changing either
service, the protected updater validates revision, complete file set, checksums
and native service contract. Missing worker configuration blocks deployment;
configuration, keys, tools, storage and resource limits remain operator-owned.

Protected generations share one atomically replaced selector. Initial adoption
keeps previous bytes until every entrance link exists, preventing mixed generations.
The updater stops the worker before switching and starts it before application
replacement; unchanged runtime files require no restart. Sandbox programs resolve
beside the selected launcher in read-only `bin/`; executable ownership, permissions
and static x64 ELF format are verified. Rollback retains each generation's own
file set, including replaced source files.

Deployment stays pending until both application images, installed runtime,
unchanged private configuration, the application's live handshake and a read-only MCP command
on an operator-reserved copy pass. Its API key remains private. Worker startup
failure restores the previous generation. Failure after application replacement
uses pending-state reconciliation, without implying persistent-format rollback.
The [deployment reference](../../deploy/README.md#worker-source-updates) owns recovery.

## Alternatives

Manual worker installation leaves a coordination gap. Separate transfers add an
artifact requiring image matching. Replacing the host installation overwrites
operator configuration and tools.

## Consequences

The image carries the launcher, resource validator and static programs. Protected
updater installation, explicit host configuration, sandbox provisioning and
[native-service adoption](2026-10-21-rust-repository-supervisor.md) remain prerequisites.
Restarts end leases and artifacts, preserving copies and recoverable generations.

`existingDeploymentTests`, `WorkerSocketTests` and `appImageIdentityCheck` pin
coordination, health and image delivery. The native probe's `--source-layout
managed` checks selector links through systemd, the sandbox and CLI.
