# Existing-installation Image Delivery

Date: 2026-09-08

[Consumer identity](2026-09-20-consumer-identity-and-site-policy.md) extends the
image-only boundary with explicit, allowlisted identity settings.
[Independent sandbox deployment](2026-10-31-independent-sandbox-deployment.md) adds
verification of the installed native dependency when execution is enabled. Other
operator settings retain this record's boundary.

## Problem and decision

An existing installation can use operator-owned Compose files, private environment
files and a separately provisioned worker. Synchronizing the generic stack would
replace those choices. Forwarding a repository password from an older CI
configuration could also invalidate the application's current content authority.

Keep the [standard entrance](2026-09-03-continuous-delivery.md) for installations
managed by the supplied templates. The `existing` layout updates app/frontend
images and the extensions above while preserving Compose files, storage, resource
settings, networking and unrelated services. Application-code delivery remains on
GitHub; the content repository's provider is an independent runtime setting, and
the existing-layout CI step never receives its password secret.

Delivery follows verified-main publication. Canonical registry pulls use the
deployment job's package-read token, so operators need no reusable registry
credential. Hosts without registry access use checksummed archive transfer;
registry credentials are used only for pulls and removed afterwards. Both modes
invoke the same installed privileged updater. Transfer never replaces that
executable. Existing Docker access is already root-equivalent; this entrance does
not create an unprivileged container-management boundary.

## Operator setup

The [deployment reference](../../deploy/README.md) owns installation, protected
configuration, source updates and recovery commands. Operator-owned environment
files, mounts and resource settings remain authoritative. Images must carry the
requested full commit label, and declared environment values must agree with the
running services before an update begins.

## Update and recovery

The updater serializes delivery with a filesystem lock. It validates the rendered
candidate and records protected pending state before changing services. An
image-only overlay retains the original Compose files. Success requires matching
image identities, runtime fingerprints, unchanged unrelated container identities,
rendered configuration and configured HTTP health responses. State stores hashes
of environment-bearing data, not credentials.

Fingerprints include image-provided environment defaults, user and working
directory. Changing those contracts requires a separate operator-reviewed
configuration update; image delivery cannot silently approve it. HTTP health
checks use loopback directly without proxies or redirects.

A failed or interrupted attempt remains pending. Only the same images, revision
and configuration can reconcile it. A contract failure may be discovered after
containers change, so previous image identities are retained for deliberate
recovery; automatic rollback cannot assume persistent-format compatibility.
Deployment health does not replace product-flow acceptance or backups.

Image retirement applies only to generations this installation recorded, retains
running and recovery images by immutable identity, and never forces removal.
An unresolved retained-image lookup stops retirement because its safe set is
incomplete. Failures remain recorded for retry and do not fail an otherwise
healthy deployment. The [updater](../../deploy/update-existing.py) owns retention,
interrupted-removal reconciliation and result fields.

## Alternatives and verification

Converting every existing installation to the generic layout would alter
configuration and processes outside image delivery. An independent release
watcher would duplicate GitHub's verified-main trigger.

`deployScriptTests` pins workflow routing and the absence of repository-secret
forwarding. `existingDeploymentTests` pins protected updates and reconciliation.
Real host preflight, workflow deployment and product acceptance remain distinct
evidence.
