# Poketto

A repository-native personal knowledge service whose public face is a blog. Its accepted target keeps each workspace's Markdown, repository-managed images, and history in remote Git while storing images uploaded through Poketto in ManagedBlobStore. The two image types share safe rendering but never synchronize. The same content serves public publishing and, over MCP, the long-term memory of trusted AI agents. Poketto targets one cloud server first while keeping an [optional serverless profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md) behind configuration-selected infrastructure.

[中文说明](README.zh.md)

## Status

Development. [Repository authoring foundations](notes/implemented/2026-09-05-repository-authoring-foundations.md) provide arbitrary-path Markdown, file diagnostics, bounded public and private search, publication policy, atomic text patches, immutable local image storage and exact-version image delivery. Remote `main` remains authoritative; public requests use a verified snapshot without fetching remotely. The [identity HTTP backend](notes/implemented/2026-09-06-workspace-identity-http.md) supplies initialization, sessions, invitations, membership and scoped keys.

The [blog and browser administration](notes/implemented/2026-09-06-blog-browser-interface.md) present these HTTP APIs through server-rendered public pages and a Chinese Markdown editor with image, member and key controls. Four repository tools are available at `/mcp`; `repo_exec` is registered only with the separate [Linux execution service](executor-service/README.md) enabled. Final HTTPS installation and deployed-topology acceptance remain pending. The [phase-one proposal](notes/proposed/2026-09-05-phase-one-daily-use.md) and broader proposals remain open until their complete acceptance is met. Consumer provisioning, backups, visitor Q&A and serverless are excluded from phase one. [Continuous delivery](notes/implemented/2026-09-03-continuous-delivery.md) publishes verified `main` commits to GHCR; automatic deployment is separately enabled. The [requirements](notes/implemented/2026-08-25-requirements-and-architecture.md) distinguish implemented behavior from historical and proposed choices.

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

Use Java 26 and the checked-in Gradle Wrapper. Linux executor tests require Python 3.10+ with venv and pip support; Windows runs that required suite in a pinned Linux container. The frontend and complete check also require Node.js 24.19.0 and npm 12.0.2. Docker is required for database integration tests and the complete check; the faster unit and repository checks do not require it. `./gradlew frontendCheck` runs frontend formatting, types, tests and the production build. Use the [isolated browser entrance](acceptance/README.md) to exercise the real application with synthetic data; frontend runtime settings are documented in [frontend/README.md](frontend/README.md).

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

To initialize the first owner, set a private `POKETTO_AUTH_INITIALIZATION_TOKEN` and configure `POKETTO_SECURITY_ALLOWED_ORIGINS` with the exact browser origin. Local HTTP also needs `POKETTO_SESSION_COOKIE_SECURE=false`; HTTPS retains the secure default. Fetch `/api/auth/csrf` before initialization or login and send its named CSRF header with the session cookie. See the [identity HTTP contract](notes/implemented/2026-09-06-workspace-identity-http.md#operation) for the initialization and login sequence. The deployment profile passes these identity settings from private operator configuration.

On Windows, `check` also runs `linuxStorageTest` in a pinned Linux container using a disposable native disk volume, including durable public-marker and snapshot restoration tests. Windows development keeps public snapshots online-only: fresh remote verification permits in-memory reads, but an offline restart never restores public authorization from disk. Linux requires successful file and directory synchronization before publication-affecting writes can push; unsupported or failed synchronization closes service. Authoritative image storage requires directory synchronization; an unsupported host cannot acknowledge durable uploads. Set the same names through `$env:...`, make `POKETTO_DATA_DIR` absolute, and use `.\gradlew.bat`. See [AGENTS.md](AGENTS.md#commands) for the command table and contribution rules.

## Content and images

To publish, create `.poketto/publishing.yaml` in the content repository:

```yaml
enabled: true
mode: public-by-default
exclude:
  - drafts/**
```

Missing or disabled policy publishes nothing; invalid policy closes public service. Root `private/` and configured exclusions remain private. Markdown metadata is optional, and unchanged source bytes are retained. The public detail endpoint is `GET /api/public/document?route=...`; list/search and tags include snapshot metadata. `index.md` owns its folder route and supplies a non-recursive sibling-image gallery without repeating body images.

Authenticated `/api/admin/repository` endpoints provide tree, file, search, preview and atomic patches. Every changed path carries a revision or explicit absence against the base commit. Conflicts or uncertain outcomes require a fresh read before retry. Image uploads under `/api/admin/assets` require an `Idempotency-Key`, accept up to 16 MiB, return immutable references and do not write Git or publish.

Managed originals live under `<data-dir>/managed-originals` and are retained; `<data-dir>/derived/repository-images` is disposable. Public image grants bind the exact page snapshot for at most five minutes and never past its expiry. Withdrawal stops new grants, while private previews recheck the current identity. See the [foundations record](notes/implemented/2026-09-05-repository-authoring-foundations.md) for limits, storage guarantees and failure behavior.

## MCP and isolated execution

`/mcp` uses Spring AI 2.0.1 WebMVC Streamable HTTP and a workspace Bearer API key, independently of browser sessions. Tools are `get_file`, `get_asset`, `put_asset` and `repo_patch`. They share the HTTP services: authoritative UTF-8 reads, precise image versions, idempotent uploads and atomic revision/absence checks. Upload acknowledgement never implies publication.

Oversized MCP bodies receive 413 before tools run; transport errors contain protocol fields rather than exception internals. Request and concurrency limits are defined in the [integration record](notes/proposed/2026-09-05-local-execution-supervisor.md#mcp-and-java-integration).

`repo_exec` requires explicit `EXECUTE_REPOSITORY` capability and `POKETTO_EXECUTOR_ENABLED=true`. Configure `POKETTO_EXECUTOR_SOCKET`, `POKETTO_EXECUTOR_SIGNING_KEY` and `POKETTO_EXECUTOR_STAGING_DIRECTORY` on the Linux application, then install and verify the separate root supervisor and unprivileged SRT account as described by the [worker reference](executor-service/README.md). Defaults admit two sessions and 128 MiB bundles; align application admission and export bounds with the worker and measure production limits before use.

Each client execution session stays pinned to its selected commit and has a separate directory even when clients share a key. Patches return new commits without switching that directory; `get_file` always reads authoritative Git objects. Cancellation, revocation and failed renewal close execution authority. A missing worker or unsupported isolation cannot fall back to an ordinary subprocess. The [integration record](notes/proposed/2026-09-05-local-execution-supervisor.md) separates executable checks and recorded synthetic evidence from final client and deployment acceptance.

## Deployment

Every verified `main` commit publishes separate Spring and frontend images from the same source commit. Copy the files under `deploy/` and a filled-in `.env.example` as `.env` into the host's deployment root. Supply the private domain/DNS configuration, one-time owner initialization token, repository/database credentials, separate data directories and four image pins. Run `deploy.sh --app-image <application-image> --app-revision <commit> --frontend-image <frontend-image>`; later runs without options redeploy the recorded pins. Both application revision labels must match, and PostgreSQL/Caddy references must carry registry digests.

Caddy owns public HTTPS, forwards `/api` and `/mcp` to Spring and other paths to Next.js, and blocks the management entrance. Success requires healthy containers plus the local certificate-verified website and API. `deploy/transfer.sh` transfers both application images when the host cannot reach GHCR; the host still needs Docker Hub access or the exact cached database/gateway digests. Its `--pull --sync` mode synchronizes current stack files while acquiring application images on the host. Automatic deployment stays separately enabled through the production environment. The host executor is installed and tested independently before setting `POKETTO_EXECUTOR_ENABLED=true`; missing isolation prerequisites fail closed. See the [stack delivery record](notes/implemented/2026-09-05-blog-stack-delivery.md) for image identity, configuration, persistence and remaining real-installation acceptance.

Set `POKETTO_NETWORK_SUBNET` to an unused RFC1918 IPv4 CIDR with at least 16 addresses, and `POKETTO_NETWORK_DYNAMIC_RANGE` to a canonical strict subpool with at least eight addresses. Set `POKETTO_GATEWAY_INTERNAL_IP` to Caddy's fixed address outside that pool, excluding the subnet's network, first usable bridge and broadcast addresses. Deployment rejects invalid ranges before starting containers; Docker allocates the other services only from the dynamic pool. This deployment alone enables Tomcat forwarding and trusts only that gateway `/32`; Caddy rebuilds client address, protocol and host headers and removes `X-Forwarded-Port` before Spring. Other entry points explicitly default to `server.forward-headers-strategy=none`. `./gradlew proxyForwardingCheck` requires Docker and Python 3.10+ and verifies actual Compose address allocation plus real per-client and shared-account login limits; it is mandatory in `check` and CI.

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
