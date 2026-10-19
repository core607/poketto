# Coordinated Worker Delivery

Date: 2026-10-19

## Problem

[Existing-installation delivery](2026-09-08-existing-installation-delivery.md)
preserves host configuration but excludes worker source updates. An application
protocol change can therefore deploy successfully while repository execution
rejects the installed worker.

## Decision

An execution-enabled existing installation delivers the worker sources carried
by the selected application image. The protected updater validates the bundle's
revision, complete file set and checksums, and the installed Python dependencies,
before changing either service. Host configuration, keys, tools, storage and
resource limits remain operator-owned. Missing worker deployment configuration
prevents an application update.

Complete source generations live in a protected directory. Existing source
entrances point through one atomically replaced selector, so an interrupted
installation cannot expose half of two generations. Initial adoption keeps all
entrances on the previous bytes until every link exists. The updater stops the
supervisor before switching and starts it before replacing the application;
unchanged sources do not require another worker restart.

The deployment remains pending until both application images, installed worker
sources, unchanged private configuration and the application's live worker
handshake and a read-only MCP command on an operator-reserved copy pass
verification. Its API key stays in a private host file. A failed worker start restores the previous source
generation. Failure after application replacement retains the existing pending
deployment reconciliation rule; it does not imply persistent formats can be
rolled back. The [deployment reference](../../deploy/README.md#worker-source-updates)
owns installation and recovery instructions.

## Alternatives

Manual source installation retains a coordination gap at every protocol change.
A separate source transfer introduces another artifact that must be matched to
the image already being delivered. Replacing the entire host installation would
overwrite configuration and provisioned tools outside a source update's scope.

## Consequences

The application image includes public Python sources. The privileged updater
requires a one-time installation and explicit host worker configuration; initial
sandbox provisioning and dependency upgrades remain separate operations. A
source-changing worker restart ends leases and artifacts while retaining account
copies. Source generations remain available for deliberate recovery.

`existingDeploymentTests` pins update ordering, source/configuration separation,
interrupted adoption, startup recovery and pending-state reconciliation.
`WorkerSocketTests` pins the live protocol health check without creating copies;
`appImageIdentityCheck` verifies the image's source bundle. The native probe's
`--source-layout managed` checks generation links through systemd, SRT and the CLI.
