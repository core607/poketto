# Poketto

A repository-native personal knowledge service whose public face is a blog. Its accepted target keeps each workspace's Markdown, repository-managed images, and history in remote Git while storing images uploaded through Poketto in ManagedBlobStore. The two image types share safe rendering but never synchronize. The same content serves public publishing and, over MCP, the long-term memory of trusted AI agents. Poketto targets one cloud server first while keeping an [optional serverless profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md) behind configuration-selected infrastructure.

[中文说明](README.zh.md)

## Status

Development. [Repository authoring foundations](notes/implemented/2026-09-05-repository-authoring-foundations.md) implement arbitrary-path Markdown discovery, file diagnostics, bounded public and private search, publication policy, atomic text patches, browser login, invitations, scoped keys, and a durable local image-storage port. Remote `main` remains authoritative; the local Git cache is disposable. Public requests use a verified snapshot and never fetch remotely.

The source includes the Next.js blog/admin, exact-version image delivery, and four MCP tools, with `repo_exec` enabled only through the separate [Linux execution service](executor-service/README.md). The [isolated acceptance stack](acceptance/README.md) and [actual MCP client workflows](acceptance/clients/README.md) supply reproducible entrances; local verification covers real PostgreSQL and Linux storage, while complete browser and five-tool client workflows and final HTTPS installation remain acceptance requirements. The [phase-one proposal](notes/proposed/2026-09-05-phase-one-daily-use.md) and broader proposals remain proposed until their complete criteria are met. [Continuous delivery](notes/implemented/2026-09-03-continuous-delivery.md) publishes verified `main` commits to GHCR; automatic deployment is separately enabled. Consumer provisioning, backups, visitor Q&A, and serverless are outside this delivery. The [requirements](notes/implemented/2026-08-25-requirements-and-architecture.md) distinguish current behavior from historical and proposed choices.

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
notes/               Decision records: proposed / implemented / rejected / archived
```

## Development

Use Java 26 and the checked-in Gradle Wrapper. Docker is required for database integration tests and the complete check; the faster unit and repository checks do not require it. `check` also uses the [pinned frontend runtime](frontend/README.md). On Linux, executor tests require Python 3.10+ with venv and pip support and install their pinned dependencies into a private build environment; Windows runs them in a pinned Linux container. The complete check validates the actual Caddy configuration in its pinned container as well as running the deployment script fixtures.

Application startup requires a PostgreSQL data source, an absolute `POKETTO_DATA_DIR`, and one pre-provisioned private HTTPS Git repository. Set `SPRING_DATASOURCE_URL`, database credentials, `POKETTO_REPOSITORY_REMOTE_URI`, `POKETTO_REPOSITORY_USERNAME`, and `POKETTO_REPOSITORY_PASSWORD` before `bootRun`. Flyway creates the default workspace; the application binds it to remote `main` and materializes only a disposable cache below `<data-dir>/workspaces/<workspace-id>/content`. `POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` and `POKETTO_REPOSITORY_TIMEOUT_SECONDS` optionally change the defaults of 32 workspaces and 30 seconds; `POKETTO_REPOSITORY_REFRESH_SECONDS` sets how often served content is re-validated against remote `main` (default 30), and `POKETTO_REPOSITORY_STALE_AFTER_SECONDS` sets how long served content may go without a successful re-validation before health reports it out of service (default 3600). Unavailable content keeps the process and refresh loop running, but readiness reports out of service and public reads fail closed. Snapshot expiry also stops public reads; the maximum stale lifetime is one hour. A running instance answers `GET /actuator/health` for deployment checks and serves the default workspace's public documents at `GET /api/public/documents`; a write through Poketto is visible immediately, and a valid direct push after the next refresh.

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

On Windows, `check` also runs `linuxStorageTest` in a pinned Linux container on a disposable native disk volume; directory-sync support is required for authoritative storage. Set the same names through `$env:...`, make `POKETTO_DATA_DIR` absolute, and use `.\gradlew.bat`. See [AGENTS.md](AGENTS.md#commands) for the command table and contribution rules.

To publish, create `.poketto/publishing.yaml` in the content repository:

```yaml
enabled: true
mode: public-by-default
exclude:
  - drafts/**
```

Missing or disabled policy publishes nothing; invalid policy closes public content service. Root `private/` and configured exclusions remain private. Markdown metadata is optional, and unchanged source bytes are retained. The public detail endpoint is `GET /api/public/document?route=...`; list/search and tags return snapshot metadata with their page of results.

Set a private `POKETTO_AUTH_INITIALIZATION_TOKEN` for one-time owner setup. Browser clients obtain `/api/auth/csrf` before initialization or login and fetch a new token after login/logout. Configure `POKETTO_SECURITY_ALLOWED_ORIGINS` for the exact site origin. Sessions use secure cookies by default; local HTTP development explicitly sets `POKETTO_SESSION_COOKIE_SECURE=false`. Authenticated repository endpoints under `/api/admin/repository` provide tree, file, search, and atomic patch operations. A patch carries the base commit and a revision or explicit absence for every path; a conflict or uncertain outcome requires a fresh read before retrying.
## Deployment

Every verified `main` commit publishes separate Spring and frontend images from the same source commit. Copy the files under `deploy/` and a filled-in `.env.example` as `.env` into the host's deployment root. Supply the private domain/DNS configuration, one-time owner initialization token, repository/database credentials, separate data directories and four image pins. Run `deploy.sh --app-image <application-image> --app-revision <commit> --frontend-image <frontend-image>`; later runs without options redeploy the recorded pins. Both application revision labels must match, and PostgreSQL/Caddy references must carry registry digests.

Caddy owns public HTTPS, forwards `/api` and `/mcp` to Spring and other paths to Next.js, and blocks the management entrance. Success requires healthy containers plus the local certificate-verified website and API. `deploy/transfer.sh` transfers both application images when the host cannot reach GHCR; the host still needs Docker Hub access or the exact cached database/gateway digests. Its `--pull --sync` mode synchronizes current stack files while acquiring application images on the host. Automatic deployment stays separately enabled through the production environment. The host executor is installed and tested independently before setting `POKETTO_EXECUTOR_ENABLED=true`; missing isolation prerequisites fail closed. See the [stack delivery record](notes/implemented/2026-09-05-blog-stack-delivery.md) for image identity, configuration, persistence and remaining real-installation acceptance.

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
