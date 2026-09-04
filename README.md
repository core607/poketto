# Poketto

A repository-native personal knowledge service whose public face is a blog. Its accepted target keeps each workspace's Markdown, repository-managed images, and history in remote Git while storing images uploaded through Poketto in ManagedBlobStore. The two image types share safe rendering but never synchronize. The same content serves public publishing and, over MCP, the long-term memory of trusted AI agents. Poketto targets one cloud server first while keeping an [optional serverless profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md) behind configuration-selected infrastructure.

[中文说明](README.zh.md)

## Status

Development. The executable baseline, workspace isolation, content repository foundation, document writes, and [remote Git repository authority](notes/implemented/2026-09-01-remote-repository-authority.md) are implemented. [Continuous delivery](notes/implemented/2026-09-03-continuous-delivery.md) publishes a verified `main` commit to GHCR and deploys it to one Docker Compose host over SSH. An [HTTP entrance](notes/implemented/2026-09-03-http-entrance-baseline.md) exposes health, RFC 9457 problem responses, and a read-only public document API over the default workspace, served from a [validated content snapshot](notes/implemented/2026-09-04-validated-content-snapshot.md) that never puts the remote on the request path. The primary single-server deployment keeps a disposable local Git cache while remote `main` is the only repository acknowledgement point. Accepted proposals add [repository-native Markdown and read-only sibling-image galleries](notes/proposed/2026-09-01-repository-native-publishing-and-assets.md) and an authoritative [local ManagedBlobStore while treating repository-image copies as disposable](notes/proposed/2026-09-01-repository-asset-blob-store.md). [Consumer accounts](notes/proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md), [repository-native retrieval](notes/proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), the [Next.js frontend](notes/proposed/2026-08-30-nextjs-frontend.md), and MCP entry points remain proposed. Serverless stays optional and waits for real OSS, shared-database, and remote-SRT infrastructure. The [requirements](notes/implemented/2026-08-25-requirements-and-architecture.md) record the implemented baseline; proposals identify target decisions that have not shipped.

## Who this is for

- People who want their notes, clippings, and blog to remain Markdown with Git history while relational application state stays outside the content repository.
- People who want their AI assistants to read and write that content over MCP with scoped API keys, instead of handing out shell access.
- People who want a small-server deployment to remain a first-class production path without closing the path to isolated workspaces in an optional shared service.
- Anyone curious about a repository designed to be developed by agents — start with [AGENTS.md](AGENTS.md).

## Layout

```
AGENTS.md            Rules for agents working in this repository (start here)
.agents/skills/      Reusable workflows: prose, review, checks, notes lifecycle
build.gradle.kts     Gradle build and verification entry points
Dockerfile           Application image: Gradle build stage, JRE runtime stage
deploy/              Production Compose, deployment entrance, transfer script, script tests
src/                 Application modules and their tests
infra/postgres/      Reproducible PostgreSQL 17 + zhparser test image
notes/               Decision records: proposed / implemented / rejected / archived
```

## Development

Use Java 26 and the checked-in Gradle Wrapper. Docker is required for database integration tests and the complete check; the faster unit and repository checks do not require it.

Application startup requires a PostgreSQL data source, an absolute `POKETTO_DATA_DIR`, and one pre-provisioned private HTTPS Git repository. Set `SPRING_DATASOURCE_URL`, database credentials, `POKETTO_REPOSITORY_REMOTE_URI`, `POKETTO_REPOSITORY_USERNAME`, and `POKETTO_REPOSITORY_PASSWORD` before `bootRun`. Flyway creates the default workspace; the application binds it to remote `main` and materializes only a disposable cache below `<data-dir>/workspaces/<workspace-id>/content`. `POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` and `POKETTO_REPOSITORY_TIMEOUT_SECONDS` optionally change the defaults of 32 workspaces and 30 seconds; `POKETTO_REPOSITORY_REFRESH_SECONDS` and `POKETTO_REPOSITORY_STALE_AFTER_SECONDS` change how often served content is re-validated against remote `main` (default 30) and after how long without re-validation health stops reporting it (default 3600). Startup fails without content it can serve. A running instance answers `GET /actuator/health` for deployment checks and serves the default workspace's public documents at `GET /api/public/documents`; a write through Poketto is visible immediately and a direct push within one refresh interval.

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

On Windows, set the same names through `$env:...`, make `POKETTO_DATA_DIR` absolute, and use `.\gradlew.bat`. See [AGENTS.md](AGENTS.md#commands) for the command table and contribution rules.

## Deployment

Every verified `main` commit publishes `ghcr.io/core607/poketto` tagged `sha-<commit>`. On the host, copy `deploy/compose.yaml`, `deploy/deploy.sh`, and a filled-in copy of `deploy/.env.example` into one deployment root, then run `deploy.sh --app-image <image> --app-revision <commit>`; a later `deploy.sh` without options redeploys the recorded pins. The script validates configuration, pins, directories, and disk space, verifies the image revision label, and succeeds only after `/actuator/health` answers `UP`. The host must be able to pull the image on that path: a private GHCR package needs a prior `docker login ghcr.io` with a read-only token, or make the package public. Where the host cannot reach GHCR at all, `deploy/transfer.sh` streams the image over SSH and invokes the same entrance. Automatic deployment from GitHub Actions stays off until the repository variable `POKETTO_DEPLOY_ENABLED` and a `production` environment are configured; see the [continuous-delivery note](notes/implemented/2026-09-03-continuous-delivery.md).

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
