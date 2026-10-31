# Independent Sandbox Deployment

Date: 2026-10-31

## Problem

Coupling sandbox installation to the application image makes a generic service
follow one consumer's release cadence. Automatic source replacement can restart
other work, while deployment state that assumes image-matched worker generations
cannot safely describe an independently installed dependency.

## Decision

Sandcrab installation, upgrades and recovery belong to the operator. Application
images contain no host sandbox programs. Deployment verifies pinned installed
binary, configuration and resource-checker files, the actual running executable,
root socket peer and finite resource pool; it never installs or restarts the
service. The configuration digest identifies disk bytes, not proof of every
setting loaded by an existing process. Application API health and a read-only
command on a reserved copy independently establish the consumer entrance.

Protected schema-2 state records the native dependency separately from image
identity. A pending attempt retains its images, rendered configuration and pins;
process restart with unchanged pins requires fresh verification, not reuse of
an old execution acknowledgement. Legacy pending state must be reconciled before
backend or copy-format changes. Explicit adoption of healthy legacy state keeps
its exact bytes and image history. State adoption neither migrates copies nor
approves runtime-configuration changes.

The [deployment reference](../../deploy/README.md) owns configuration and operation.
This replaces [image-bound worker delivery](../archived/2026-10-19-coordinated-worker-delivery.md).
[Existing-installation delivery](2026-09-08-existing-installation-delivery.md)
retains the configuration, image-history and unrelated-service boundaries.

## Alternatives and consequences

Bundling the worker in every application image previously guaranteed matching
versions, at the cost of shared upgrades and restarts. Merely removing that bundle
would leave service identity and compatibility unchecked. An independent pinned
dependency makes provisioning explicit while keeping failed checks ahead of
application replacement. It costs operator installation and coordinated first
adoption; it does not add persistent-format rollback guarantees.

`existingDeploymentTests`, `deployScriptTests` and `appImageIdentityCheck` pin
verification, pending-state preservation, explicit adoption and the image boundary.
Native consumer acceptance remains separate from these deployment fixtures.
