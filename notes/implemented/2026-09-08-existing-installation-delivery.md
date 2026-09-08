# Existing-installation Image Delivery

Date: 2026-09-08

## Problem and decision

An existing installation can use operator-owned Compose files, private environment files and a separately installed worker. Synchronizing the generic deployment stack into that directory replaces those choices. Streaming a repository password from an older CI configuration can also invalidate the application's current content authority.

Keep the [standard deployment entrance](2026-09-03-continuous-delivery.md) for installations managed by the supplied Compose templates. Add an `existing` layout for updating only the `app` and `frontend` images of an already configured Compose project. This layout uses the same verified-main publication and checksummed archive transfer. It does not synchronize Compose, forward environment settings, restart dependencies or update the external worker.

## Operator setup

Install `deploy/update-existing.py` as the root-owned executable `/usr/local/sbin/poketto-update-existing`. The existing deployment account needs Docker access for image transfer and permission to invoke that fixed executable through noninteractive sudo. The deployment account's existing Docker access is already root-equivalent; this entrance does not create an unprivileged container-management boundary.

Place a root-owned `deployment.json` in the deployment root using [existing.example.json](../../deploy/existing.example.json). Set the current Compose project name, the ordered relative Compose filenames, and the installation's loopback health entrances. The root and configuration must not be writable by other users. Compose continues to resolve the operator's original environment files, volumes, resource settings and network configuration.

Use `--check` with the same image and revision arguments to validate the target without changing containers. The updater requires loaded images whose revision labels match the requested full commit. It refuses a declared environment that disagrees with the running app or frontend.

Set production environment variables `POKETTO_DEPLOY_LAYOUT=existing`, `POKETTO_DEPLOY_MODE=transfer` and `POKETTO_DEPLOY_ROOT` to the configured root. Existing SSH target, key and host-key secrets remain in use. After target validation, `POKETTO_DEPLOY_ENABLED=true` enables the GitHub deployment job. The existing-layout step does not receive the repository-password secret. Application-code delivery remains on GitHub; the content repository's provider is an independent runtime setting.

## Update and recovery

The updater holds a nonblocking filesystem lock and writes an image-only override to `.deployment/images.json`. It checks the rendered candidate changes only the two selected images, then records a protected pending state before applying it. `compose up --no-deps` waits for app and frontend health without recreating other services. Final verification compares image identities, runtime configuration fingerprints, unrelated container IDs, rendered configuration and configured HTTP health responses. Only then does the state become healthy. State files contain hashes of environment-bearing data, not credential values.

Runtime fingerprints intentionally cover the effective environment, user and working directory, including image-provided defaults. A release that changes those settings needs a separate operator-reviewed configuration update; this entrance cannot silently approve it as an image-only change. HTTP health checks connect directly to loopback without environment proxies or redirects.

Original Compose files are retained. After the first update, manual Compose commands must include `.deployment/images.json` last to use the selected image pins. Use the updater for normal image releases; synchronizing an old generic deployment entrance into this root is not an upgrade path.

A failed or interrupted attempt remains pending. Retrying the same images, source commit and configuration reconciles it; a different target is refused until the operator inspects the protected state and actual containers. A runtime-contract failure can be detected after containers change and is not a rollback. Previous image references remain in the state for deliberate recovery. There is no automatic rollback across unknown persistent-format compatibility, and this mode does not add backups or final product-flow acceptance.

## Alternatives and verification

Converting every existing installation to the generic Compose layout changes configuration and processes outside an image update. An independent release watcher duplicates GitHub's verified-main trigger. The selected adapter preserves both the existing installation and the standard delivery path.

`deployScriptTests` covers the exclusive workflow branches, absence of repository-secret forwarding and refusal to combine existing mode with configuration synchronization or registry-pull settings. `existingDeploymentTests` runs the Python updater tests on Linux in Docker, covering image-only updates, retained settings, preflight, interrupted retries, target mismatch, runtime drift, wrong image revisions and protected configuration paths. Real installation preflight and successful workflow deployment are separate operator evidence.
