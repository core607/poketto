# Continuous Delivery for a Single Host

Date: 2026-08-26
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) require application images in GHCR and a fallback that transfers a `docker save` artifact over SSH when registry access is unreliable. The [development baseline](../implemented/2026-08-26-development-baseline.md) currently runs complete verification for pull requests and `main` pushes but builds no deployable image and provides no repeatable production Compose or deployment script.

Poketto targets a self-hosted single machine. Its first delivery path needs only to turn a verified commit into immutable images and deploy them to a Linux Docker Compose host through a constrained SSH entrance. Automatic rollback, artifact promotion, and release attestation remain outside the first implementation until operating evidence justifies them.

## Proposal

### Verification and image publication

- Keep the existing pull-request `check`. After the canonical repository's `main` passes `check`, the same workflow builds the application image and the PostgreSQL 17 + zhparser OCI image and publishes both to GHCR.
- Images carry a source-commit tag and standard revision metadata. Deployment uses immutable digests returned by the registry; moving tags aid discovery but never select the production version.
- The publication job receives only `contents: read` and `packages: write`. Verification, pull-request, and deployment jobs do not inherit package-write authority. Every third-party Action is pinned to a full commit SHA.
- Images contain no `.env`, API key, content repository, database volume, blob directory, or other runtime state.

### Optional automatic deployment

- Without a configured production target, the workflow succeeds after publication. Production deployment runs only when a repository variable explicitly enables it and a GitHub `production` environment exists. Open-source CI must not depend on a maintainer's private server.
- The deployment job uses a fixed concurrency group and does not cancel an in-progress deployment. Consecutive `main` pushes cannot mutate the same host concurrently.
- The `production` environment stores the SSH private key, pinned host key, and required registry credentials. The workflow uses neither a personal SSH key, `StrictHostKeyChecking=no`, nor a self-hosted Actions runner on the production host.
- The GitHub Actions summary records the source commit, image digests, target environment, and health result without printing secrets, remote environment files, or sensitive command arguments.

### Compose and SSH scripts

- The repository provides a generic production Compose file and non-interactive deployment script. Hostname, port, domain, persistent paths, and secrets exist only in the operator environment or GitHub environment.
- A dedicated Poketto deployment account has only the authority needed to invoke the deployment entrance. The remote script obtains a deployment lock and validates Compose, the environment file, persistent directories, and available disk space before replacing containers. It never deletes, recreates, or rolls back data volumes.
- By default the production host pulls exact digests from GHCR. The restricted-network path lets an operator or GitHub-hosted runner pull those same digests, transfer `docker save` output over SSH, run `docker load`, and invoke the same remote deployment entrance.
- The application and database both expose Compose health checks. Deployment succeeds only after the real service health entrance passes; a successful `docker compose up` exit code is insufficient.
- A failed deployment returns failure and preserves diagnostics but does not guess which old image is safe. The operator may rerun the same script with a previously recorded digest. The first implementation has no candidate manifest, automatic promotion, or rollback state machine.
- Repeating the script with the same digests is idempotent. A retry after interruption derives its next action from actual host image and container state rather than prose recorded by the previous workflow.

### Persistence boundary

Container deployment changes only images and Compose-managed processes. It neither restores nor migrates content repositories, blobs, or authoritative PostgreSQL tables. A feature proposal that makes an incompatible persistent change must define its own migration, failure recovery, and old-version behavior; the deployment script cannot infer compatibility from file differences.

Production automatic deployment remains disabled until the [off-host backup and restore proposal](2026-08-27-off-host-backup-and-restore.md) supplies a machine-readable freshness signal. Image publication and manual deployment do not depend on that proposal, but an operator must explicitly accept the absence of an automated backup gate.

## First implementation scope and dependencies

The first implementation includes an application container, production Compose, an application health entrance, publication of both OCI images to GHCR, digest-pinned SSH deployment, a deployment lock, health confirmation, the restricted-network transfer script, and focused tests.

It excludes domains, TLS, reverse proxies, a logging platform, provenance attestation, deployment-manifest promotion, automatic rollback, blue-green stacks, an arbitrary-version selector, and a database migration framework. Image publication may build on the current development baseline independently. Automatic deployment waits for the off-host backup gate.

## Alternatives considered

**Run a self-hosted GitHub Actions runner on the production host.** It provides direct Docker access but gives repository workflows an execution surface close to host root. A constrained SSH account is easier to audit and revoke.

**Let Watchtower poll a moving tag.** This is easy to configure, but a deployment decision no longer maps reliably to the commit that passed CI. The workflow passes immutable digests instead.

**Add candidate manifests, automatic promotion, and automatic rollback.** These mechanisms can improve unattended recovery but require persistent-compatibility rules and additional state. The first implementation stops on failure and reruns a known digest; later operating evidence may justify a separate proposal.

**Publish images without a deployment script.** Every operator would still improvise SSH, Compose, locking, and health checks. One generic script is the minimum maintainable delivery surface.

**Use Kubernetes or blue-green deployment.** The default target is a two-core, 4 GB machine; another orchestration surface and duplicate resident stack do not fit that constraint. Runtime platform and data authority remain independent, so a future Kubernetes deployment can consume the same image artifacts.

## Acceptance

- A pull request runs complete `check` without production secrets or package-write authority. Unmerged code cannot publish or deploy.
- After a `main` commit passes `check`, the application and PostgreSQL images appear in GHCR, and the source commit plus both immutable digests are available from the same workflow run.
- Publication succeeds and deployment is explicitly skipped when production is not configured. When deployment is enabled, missing variables or secrets fail with an actionable list.
- On a disposable Linux host, registry pull and `docker save` over SSH can start the same Compose stack from identical digests and pass the real health entrance.
- Adjacent deployments cannot mutate the host concurrently. Retry after interruption and redeployment of the same digest preserve persistent data.
- A failed health check fails the workflow and is not hidden by successful setup logs. Rerunning the same script with a previous digest can restore an older image; automatic rollback is not part of acceptance.
- SSH host keys, deployment private keys, registry credentials, and remote environment files appear in neither the repository, artifacts, Actions summary, nor test logs.
- Automated tests cover digest validation, locking, idempotency, missing configuration, transfer failure, and health timeout. One disposable-host drill covers the real Compose entrance.
- `./gradlew check` and `git diff --check` pass. Implementation does not modify remote required checks or environment settings.

## Risks

Automatic deployment from `main` moves a semantically defective change into production faster after CI passes. Health checks prove that the service runs, not that its behavior is correct. The enable switch and manual restoration of a precise digest are the first controls.

A GitHub-hosted runner temporarily holds the secret needed to reach production. A dedicated account, pinned host key, minimum permissions, and exclusion from pull-request workflows reduce the surface, but the trusted `main` workflow remains part of the security root.

Image rollback cannot undo a database or content-format change. Starting an old container with unknown compatibility may be more dangerous than downtime, so the first implementation does not roll back automatically.

Building two images on every `main` update consumes Actions time and GHCR storage. Keep one traceable path first; if measured cost becomes material, reuse the unchanged database image by build context.
