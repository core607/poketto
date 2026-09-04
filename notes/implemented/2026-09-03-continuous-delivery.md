# Continuous Delivery for a Single Host

Date: 2026-09-03
Status: Implemented

## Problem

The [requirements](2026-08-25-requirements-and-architecture.md) require application images in GHCR and a fallback that transfers a `docker save` artifact over SSH when registry access is unreliable. The [development baseline](2026-08-26-development-baseline.md) verified every commit but built no deployable image and provided no repeatable production Compose or deployment script.

Poketto targets a self-hosted single machine. Its delivery path needs only to turn a verified commit into an immutable image and deploy it to a Linux Docker Compose host through a constrained SSH entrance. Automatic rollback, artifact promotion, and release attestation stay outside until operating evidence justifies them.

## Decision

### Verification and image publication

The `CI` workflow keeps the pull-request `check`. After a `main` push of the canonical repository passes `check`, the `publish` job builds the application image with Buildx and pushes it to `ghcr.io/core607/poketto` tagged `sha-<commit>` and `main`. The moving tag aids discovery only; deployment selects an image by its registry digest or by the per-commit tag carried in a transferred archive, and always verifies the `org.opencontainers.image.revision` label against the commit being deployed. Provenance attestations are disabled so the published digest names one `linux/amd64` manifest.

The publish job alone holds `contents: read` and `packages: write`. Verification, pull-request, and deployment jobs hold no package-write authority. Every third-party action is pinned to a full commit SHA. The workflow summary records the source commit, the application digest, and the database pin.

The [Dockerfile](../../Dockerfile) builds the boot jar on the pinned Temurin 26 JDK image, extracts Spring Boot layers, and runs them on the pinned Temurin 26 JRE image as the non-root user `poketto` (uid 10001) with `curl` for the health check. The image contains no `.env`, credential, content repository, database volume, or data directory.

On the pull path the host must be able to pull the application image: GHCR publishes a new package as private, so the automatic deployment streams the run's `GITHUB_TOKEN` to the entrance as `REGISTRY_USERNAME` and `REGISTRY_PASSWORD` lines, which log in for that pull inside a Docker configuration directory that exists only for that run and are never recorded, and a manual pull-path deployment needs either a public package or a prior `docker login ghcr.io` on the host with a read-only token. The transfer path needs no registry access on the host.

Production pins the official `postgres:17` image by digest in `deploy/.env.example`; the deployment script refuses a database reference without a digest. The custom zhparser image remains an integration-test concern until [repository-native retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) removes it.

### Optional automatic deployment

Without a configured target the workflow succeeds after publication. The `deploy` job runs only when the repository variable `POKETTO_DEPLOY_ENABLED` is `true`; it uses the GitHub `production` environment, a fixed `production-deploy` concurrency group that never cancels an in-progress deployment, and workflow-level cancellation only for pull requests. Open-source CI never depends on a maintainer's private server.

The environment supplies the variables `POKETTO_DEPLOY_ROOT` and optional `POKETTO_DEPLOY_MODE` (`pull` by default, or `transfer`) and the secrets `POKETTO_DEPLOY_TARGET` (`user@host`, a secret because it names the private host), `POKETTO_DEPLOY_SSH_KEY`, `POKETTO_DEPLOY_HOST_KEY` holding the pinned `known_hosts` line, and optionally `POKETTO_REPOSITORY_PASSWORD`. Missing configuration fails with the list of what is absent. The job uses `StrictHostKeyChecking=yes` and `BatchMode`, never a personal key, and never a self-hosted runner on the production host. When the repository credential secret is set, the job streams it to the entrance's standard input, which records it into the host's `.env` once the deployment is healthy; it never appears as a command-line argument or in the summary. The summary records the commit, image, mode, and result.

### Compose and SSH scripts

[deploy/compose.yaml](../../deploy/compose.yaml) is the generic production stack: the application bound to the loopback interface by default with a real `/actuator/health` check, and PostgreSQL with `pg_isready`. Both services carry memory limits sized for a two-core, 4 GB host, a process limit (512 for each service), a 30-second stop grace period, and `json-file` logging capped at five files of 10 MB. The application container drops every capability and forbids privilege escalation; the official `postgres` image starts as root and drops privileges itself, so the database keeps its default capabilities. Neither root filesystem is read-only. Hostname, port, persistent paths, image pins, and secrets live only in the operator's `.env` beside it; [deploy/.env.example](../../deploy/.env.example) lists every value.

[deploy/deploy.sh](../../deploy/deploy.sh) is the deployment entrance on the host. It takes an operating-system file lock, with a non-reclaiming directory fallback when `flock` is unavailable, and parses `.env` as supported literal single-line `KEY=VALUE` data rather than shell code. It pulls a digest-pinned application image when absent, accepts a tag only when the transferred archive already loaded it, verifies the revision label, runs `compose up` with pulls disabled, waits for the container health check, and finally requests the health entrance from the host. A failure prints the container state and the last application log lines and exits without guessing an older image. It never issues `down`, removes a volume, or recreates data.

The `--app-image`, `--app-revision`, and `--db-image` options and the `--set-stdin` settings are candidates. The run validates and uses them, and writes them into `.env` only after the health check passes, so a failed deployment leaves `.env` byte-identical. Rerunning with the same pins is idempotent, and a rerun without options derives its action from the confirmed `.env` and the host's actual image and container state. When a deployment replaces confirmed application pins, the previous `POKETTO_APP_IMAGE`, `POKETTO_APP_REVISION`, and `POKETTO_DB_IMAGE` are saved first to `.env.previous` (mode 600) beside `.env`; passing them as options redeploys the last healthy version. A first deployment from blank pins writes no such file. A failure while writing `.env` after the stack is healthy, for example no space left in the deployment root, leaves a healthy new stack with the old pins recorded; a rerun without options then recreates the old image. This gap is open.

Validation runs before any container changes. The script requires every mandatory value, requires the database pin to be a digest, and resolves both persistent directories to canonical paths, refusing them when they coincide, nest, or equal or contain the deployment root. It confirms the Docker daemon and Compose plugin, creates the persistent directories, and checks that the data directory belongs to uid 10001. It requires `POKETTO_MIN_FREE_MB` of free space below the root, below both persistent directories, and below the Docker data root when the daemon reports one that exists on the host. Finally it validates the Compose configuration.

When `--sync-from` names a staging directory, the script first moves the staged `compose.yaml` and `deploy.sh` into the root by rename inside the lock, so a concurrent or interrupted transfer never leaves a half-written file. When the staged `deploy.sh` differs from the running script, it re-executes the installed copy with the same arguments minus `--sync-from`, before standard input is read, so one run never combines an old entrance with a new `compose.yaml`. The held lock passes to the new process through `POKETTO_DEPLOY_LOCK_FD` (flock) or `POKETTO_DEPLOY_LOCK_HELD` (directory fallback). This handover is forward-only: an entrance that does not know these variables would try to take the lock again and fail, so `--sync` cannot install an older `deploy.sh`. An older version is redeployed by passing its pins as options, never by syncing an older script.

Registry credentials streamed on standard input log in inside a temporary Docker configuration directory that is deleted at exit. Only login and pulls from the authenticated registry use it. Its fresh `config.json` contains no credential helper or store settings; an empty Docker Hub entry makes the auth map nonempty and suppresses Docker's automatic native-helper discovery. The selected Docker context and its context store remain available to those commands. Compose and other Docker operations retain the user's configuration, plugins, proxies, and credentials for other registries. Copying `credsStore` or `credHelpers` would write the temporary token into the same external keychain despite changing `DOCKER_CONFIG`. A run without streamed credentials uses the user's configuration directly.

[deploy/transfer.sh](../../deploy/transfer.sh) is the restricted-network path. It pulls the digest-pinned image locally when needed, verifies its revision label, retags it `sha-<commit>` because a digest does not survive `docker save` on every image store, streams a gzip archive over SSH, verifies the archive checksum on the host before `docker load`, and invokes the same entrance with the tag and commit. With `--sync`, it uploads `compose.yaml` and `deploy.sh` into `<root>/.incoming/<commit>` through temporary files verified by checksum and hands the directory to the entrance's `--sync-from`, so concurrent transfers of different commits never overwrite each other's staged files; only when no entrance exists yet does it install the first copy directly. When the entrance exits non-zero after staging, for example with 75 because another deployment holds the lock, the staged directory is removed so a retry starts clean. The database image is always pulled by digest on the host.

The deployment account on the host is a dedicated user that belongs to the `docker` group and owns the deployment root. Docker group membership is root-equivalent on that host, so the account is reachable only through its own SSH key, which is the boundary this first implementation offers.

### Persistence boundary

Deployment changes only images and Compose-managed processes. It neither restores nor migrates content repositories, blobs, or authoritative PostgreSQL tables. A feature that makes an incompatible persistent change must define its own migration, failure recovery, and old-version behavior; the deployment script cannot infer compatibility from file differences.

Production automatic deployment stays disabled until the [off-host backup and restore proposal](../proposed/2026-08-27-off-host-backup-and-restore.md) supplies a machine-readable freshness signal. Publication and manual deployment do not depend on it, and an operator who enables automatic deployment before it exists accepts the absence of a backup gate explicitly.

## Alternatives considered

**Run a self-hosted GitHub Actions runner on the production host.** It provides direct Docker access but gives repository workflows an execution surface close to host root. A constrained SSH account is easier to audit and revoke.

**Let Watchtower poll a moving tag.** Easy to configure, but a deployment decision no longer maps reliably to the commit that passed CI. The workflow passes immutable digests instead.

**Select images by image ID instead of by digest and label.** An image ID is store-dependent: Docker's classic store reports the config digest while the containerd store reports the manifest digest, so one recorded identity cannot be verified on every host. The registry digest verifies the pull path, the archive checksum verifies the transfer path, and the revision label verifies both.

**Add candidate manifests, automatic promotion, and automatic rollback.** These can improve unattended recovery but require persistent-compatibility rules and additional state. The first implementation stops on failure and reruns a known pin; later operating evidence may justify a separate proposal.

**Publish images without a deployment script.** Every operator would still improvise SSH, Compose, locking, and health checks. One generic script is the minimum maintainable delivery surface.

**Use Kubernetes or blue-green deployment.** The default target is a two-core, 4 GB machine; another orchestration surface and a duplicate resident stack do not fit. Runtime platform and data authority remain independent, so a future Kubernetes deployment can consume the same image artifacts.

## Consequences

Automatic deployment from `main` moves a semantically defective change into production faster after CI passes. Health checks prove that the service runs, not that its behavior is correct. The enable switch and manual redeployment of a confirmed pin, including the one saved in `.env.previous`, are the controls.

A GitHub-hosted runner temporarily holds the key that reaches production. A dedicated account, pinned host key, minimum permissions, and exclusion from pull-request workflows reduce the surface, but the trusted `main` workflow remains part of the security root.

Image rollback cannot undo a database or content-format change. Starting an old container with unknown compatibility may be more dangerous than downtime, so nothing rolls back automatically.

Every `main` update builds an image and consumes Actions time and GHCR storage. If measured cost becomes material, add retention or build-cache policy without weakening digest selection.

Domains, TLS, reverse proxies, a logging platform, provenance attestation, deployment-manifest promotion, automatic rollback, blue-green stacks, an arbitrary-version selector, and a database migration framework remain outside this implementation.

## Verification

- `deploy/tests/run.sh`, wired into `check` as `./gradlew deployScriptTests`, runs the scripts against fake `docker`, `curl`, `ssh`, and `df` commands. On Windows the task invokes Git for Windows as a login shell so its `usr/bin` tools are available. It covers the missing-configuration list, literal metacharacters and unsupported keys in `.env`, digest and revision validation, persistent directories that coincide, nest, or cover the deployment root, a live and a stale lock, the registry pull path, the transfer-only tag path, revision mismatch, pull failure, insufficient space below the root, either persistent directory, or the Docker data root, health timeout, an unhealthy container, an unanswering or non-UP entrance, pins and settings recorded only after health with `.env.previous` written when pins change, a failed candidate that leaves both files byte-identical, an idempotent rerun, redeployment of the saved previous pins, a staged sync that waits for the lock and then swaps both files while another revision's staging directory stays untouched, a changed entrance that re-executes itself under the held lock with its standard input intact, an isolated registry login that leaves the original configuration and simulated external credential store untouched while preserving the selected context and Compose configuration, archive transfer with checksum and per-commit staged sync, staging removed after a failed entrance, a remote load failure, and a local image with the wrong revision. The fakes refuse every destructive Compose command.
- A drill ran the transfer path against a real Ubuntu 22.04 host with Docker 27: the locally built image streamed over SSH, its checksum verified on the host, `docker load` succeeded, `compose.yaml` and `deploy.sh` synced, and the entrance recorded the pins and then refused with the list of values still missing from `.env`. The same host received a different image ID than the building machine reported for the same archive, which is the store-dependent behavior the revision label exists to absorb. The drill predates the hardening of the entrance: the re-exec handover, the canonical-path directory checks, the Docker-root free-space check, and the temporary Docker configuration have run only under the script tests, not on a host.
- `./gradlew check` and `git diff --check` pass. The implementation changes no remote required checks or environment settings.
