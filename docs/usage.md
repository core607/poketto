# Development and operations

Runtime requirements, content configuration, MCP access and deployment for Poketto.

[Project overview](../README.md) · [中文说明](usage.zh.md)

## Development

Use Java 26 and the checked-in Gradle Wrapper. Linux executor tests require Python 3.10+ with venv and pip support; Windows runs that required suite in a pinned Linux container. The frontend and complete check also require Node.js 24.19.0 and npm 12.0.2. Docker is required for database integration tests and the complete check; the faster unit and repository checks do not require it. `./gradlew frontendCheck` runs frontend formatting, types, tests and the production build. Use the [isolated browser entrance](../acceptance/README.md) to exercise the real application with synthetic data; frontend runtime settings are documented in [frontend/README.md](../frontend/README.md).

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

To initialize the first owner, set a private `POKETTO_AUTH_INITIALIZATION_TOKEN` and configure `POKETTO_SECURITY_ALLOWED_ORIGINS` with the exact browser origin. Local HTTP also needs `POKETTO_SESSION_COOKIE_SECURE=false`; HTTPS retains the secure default. Fetch `/api/auth/csrf` before initialization or login and send its named CSRF header with the session cookie. See the [identity HTTP contract](../notes/implemented/2026-09-06-workspace-identity-http.md#operation) for the initialization and login sequence. The deployment profile passes these identity settings from private operator configuration.

On Windows, `check` also runs `linuxStorageTest` in a pinned Linux container using a disposable native disk volume, including durable public-marker and snapshot restoration tests. Windows development keeps public snapshots online-only: fresh remote verification permits in-memory reads, but an offline restart never restores public authorization from disk. Linux requires successful file and directory synchronization before publication-affecting writes can push; unsupported or failed synchronization closes service. Authoritative image storage requires directory synchronization; an unsupported host cannot acknowledge durable uploads. Set the same names through `$env:...`, make `POKETTO_DATA_DIR` absolute, and use `.\gradlew.bat`. See [AGENTS.md](../AGENTS.md#commands) for the command table and contribution rules.

## Content and images

Initialize an empty content repository from [content-template](../content-template/AGENTS.md).
It contains independent `private/` and `public/` trees and keeps publication disabled.
Create new content under `private/`. To publish selected content, move it and its
required media into `public/`, then configure `.poketto/publishing.yaml`:

```yaml
enabled: true
mode: public-root
exclude:
  - public/drafts/**
```

Only paths below the exact root `public/` are eligible. Exclusions use full repository-relative paths and win; guides named `AGENTS.md` in any letter case and hidden path segments stay private. Names below either root are ordinary categories. Missing or disabled policy publishes nothing; invalid policy closes public service. Existing default-public repositories need the [coordinated content conversion](../notes/implemented/2026-09-09-codeact-content-and-media.md#implementation-and-acceptance) before upgrading; copying the template over an existing repository is not a conversion.

Markdown metadata is optional, and unchanged source bytes are retained. Default routes omit `public/` and `.md`; an explicit route is preserved but cannot grant publication rights. The public detail endpoint is `GET /api/public/document?route=...`; list/search and tags include snapshot metadata. `index.md` owns its folder route (`public/index.md` owns `/`) and supplies a non-recursive sibling-image gallery without repeating body images.

Authenticated `/api/admin/repository` endpoints provide the Markdown index, paginated directory listing, file reads, search, preview, atomic patches and moves. The browser destination picker moves files or folders and repairs Markdown references in the same commit. Text changes carry revisions or explicit absence against the base commit; moves check the source and destination at that base. Conflicts or uncertain outcomes require a fresh read before retry. Image uploads under `/api/admin/assets` require an `Idempotency-Key`, accept up to 16 MiB, return immutable references and do not write Git or publish.

The new-path field starts at `private/`. In the move picker, the private/public
directory buttons retain the category path while switching roots. Selecting a
destination does not write until the move is submitted. Moving a directory includes
its indexed media; moving one document does not move shared dependencies.

Managed originals live under `<data-dir>/managed-originals` and are retained; `<data-dir>/derived/repository-images` is disposable. Public image grants bind the exact page snapshot for at most five minutes and never past its expiry. Withdrawal stops new grants, while private previews recheck the current identity. See the [foundations record](../notes/implemented/2026-09-05-repository-authoring-foundations.md) for limits, storage guarantees and failure behavior.

`POST /api/admin/media` accepts raw octet-stream originals up to 128 MiB with an `Idempotency-Key` and optional `X-Media-Type`. Storage deduplicates bytes strictly within a workspace while retaining independent upload identities. Set `poketto.assets.max-file-bytes` to lower the upload bound; existing originals remain readable. The [logical media index](../notes/implemented/2026-09-09-logical-media-index.md) combines media paths with Git directory entries and can be saved atomically with text. [Indexed media delivery](../notes/implemented/2026-09-09-indexed-media-delivery.md) renders relative image links and supplies original attachments through authenticated `/api/admin/media` and publication-bound `/api/public/media` downloads. Uploading never writes the index or publishes.

## Export HTTP interface

In the editor, use **Export** beside a file or inside an expanded folder, or the
file sidebar's export action for the whole workspace. Choose a private copy or a
public copy, generate the ZIP, then download it. Exports use the latest saved
content; unsaved editor changes stay outside the package. Public copies reject
private selections rather than changing publication. Downloads use the browser's
download manager. Closing the dialog leaves an already offered package available
until expiry, so an active download can finish.

On native Linux, `POST /api/admin/exports` accepts `paths` (explicit Markdown,
indexed-media paths or directory prefixes) and an explicit `publicOnly` boolean.
It returns a temporary handle, ZIP size, SHA-256 and expiry. `GET
/api/admin/exports/{handle}` downloads the archive; the `/metadata` suffix reads
its receipt, and `POST /api/admin/exports/{handle}/release` releases it early.
Creation and release use normal session CSRF protection. Every operation rechecks
the owner and workspace; a handle cannot be shared as an anonymous download link.

Private packages require private-read authority and retain source frontmatter.
Public packages contain approved article fields and authorized originals; private
selections fail instead of being published. Relative links point to actual media
inside the ZIP. No original Git history, runtime guide or internal media index is
included. Download checks publication, expiry and identity again, verifies the
stored ZIP before output, and returns an attachment with `no-store` and `nosniff`.

The service stages below `<data-dir>/portable-exports/<workspace-id>` and removes
expired or abandoned packages. Defaults are one build, two downloads (at most one
per workspace), 512 MiB of originals, 256 MiB of text and 10,000 entries. Properties
under `poketto.exports` set `max-zip-bytes` (800 MiB), `max-retained-bytes` (2 GiB),
`max-workspace-bytes` (1600 MiB), `max-packages` (8), `lifetime-seconds` (600), and
`build-seconds` (120). A build reserves its full ZIP allowance before preparation;
only its actual size remains charged after success. Capacity exhaustion returns
429; missing, expired or differently owned handles return 404. Filesystems without
POSIX permission support return 503 before reading export content. The
[export decision](../notes/implemented/2026-09-10-portable-content-exports.md)
owns package selection and lifecycle.

In a CodeAct session, use `poketto export PATH... --output FILE [--public]`.
Selections use repository-relative files or folders; `.` selects the visible workspace.
Public-only sessions always export the host-approved public projection. The ZIP
contains latest saved content and originals; local edits are excluded. Different
existing output files are preserved. Use `get_artifact` after `poketto artifact
create FILE --type application/zip` when the result fits the artifact limits.
`MATERIALIZE_CAPACITY` preserves the session and existing files: free local space,
select fewer files, or use browser export when the ZIP exceeds the worker's capacity.
The [worker reference](../executor-service/README.md) owns deadlines, size bounds,
installation ordering and error codes; [real HTTP MCP acceptance](../acceptance/clients/evidence/2026-09-10-cli-exports.json)
verifies the authenticated export and artifact path.

## MCP and isolated execution

`/mcp` uses Spring AI 2.0.1 WebMVC Streamable HTTP and a workspace Bearer credential (API key or OAuth access token), independently of browser sessions. With the executor enabled, the catalog contains `repo_exec`, `get_artifact`, `get_asset` and `put_asset`. The asset tools transfer exact image versions and accept idempotent uploads; upload acknowledgement never implies publication.

Use `repo_exec` for file listings, search, reads and edits, then the `poketto` CLI for persistence. Read relevant repository-owned `AGENTS.md` files progressively. Standalone `list_directory`, `get_file` and `repo_patch` calls are not supported, including when the worker is disabled. The [CodeAct entrance record](../notes/implemented/2026-09-10-codeact-mcp-entrance.md) defines this boundary; the shared directory reader still serves browser navigation.

Oversized MCP bodies receive 413 before tools run; transport errors contain protocol fields rather than exception internals. Request and concurrency limits are defined in the [integration record](../notes/proposed/2026-09-05-local-execution-supervisor.md#mcp-and-java-integration).

`repo_exec` requires explicit `EXECUTE_REPOSITORY` capability and `POKETTO_EXECUTOR_ENABLED=true`. Configure `POKETTO_EXECUTOR_SOCKET`, `POKETTO_EXECUTOR_SIGNING_KEY` and `POKETTO_EXECUTOR_STAGING_DIRECTORY` on the Linux application, then install and verify the separate root supervisor and unprivileged SRT account as described by the [worker reference](../executor-service/README.md). Defaults admit two sessions and 128 MiB bundles; align application admission and export bounds with the worker and measure production limits before use.

Full-read execution sessions retain authorized current files and original Git history; public-only sessions receive a fresh current-public projection without original history or private metadata. Each client has a separate directory even when clients share a key. Ordinary edits stay local. `poketto save` commits selected files and explicit deletions through the shared atomic writer while retaining unselected edits; `poketto sync` reconciles one file against its own baseline, and `poketto recover` reconciles a pending save or move without replaying newer edits. Cancellation, revocation and failed renewal close execution authority. A missing worker, mismatched CodeAct protocol or unsupported isolation cannot fall back to an ordinary subprocess.

`poketto media import` stores a workspace-owned immutable original and updates its local logical index; save that index with referring text to persist the references. `poketto media fetch` uses the local index or an explicitly selected historical commit in full-read sessions, and the host-owned approved mapping in public sessions. CLI paths are repository-relative; use `poketto --help` for commands and file lifetime. The [worker reference](../executor-service/README.md) owns limits, permissions, conflict behavior and coordinated worker installation.

`poketto move SOURCE DESTINATION` moves saved files, directories and indexed media,
repairing Markdown references in one remote commit. Unselected local edits and
unsaved index entries remain local. Dirty selected files and occupied destinations
are refused. If the commit succeeds but local installation is pending, use
`poketto recover` before another save, move or sync; it retains the original
operation and preserves edits made after an acknowledged local installation.
If local changes prevent installation, `poketto recover --skip-local` confirms
the remote move and keeps local files untouched. It releases the pending move
without advancing file baselines; use `poketto sync` on affected paths before
saving them.

`poketto media list` discovers indexed media without fetching bytes. It includes
unsaved imports in full-read sessions; public sessions use only the host-owned
approved mapping. Use `--prefix` to filter paths and continue pages with the
returned `nextOffset` and `indexVersion`. Full readers can select `--commit` for a
historical index. Metadata is checked against original storage when fetched.
Keep `--prefix` and `--commit` unchanged between pages; restart at offset zero
when changing the selection. Historical listing shares original-read concurrency
limits and can return `MEDIA_UNAVAILABLE` while that capacity is occupied.

`poketto artifact create FILE --type MIME` retains an immutable, temporary result
for the originating MCP session. `get_artifact` renders validated raster images
or returns text/binary pages; long command output also supplies artifact handles.
Handles expire after five minutes or session closure and do not upload, save or
publish files. Timeout, resource limits and cancellation close the session, so
long output then has only its preview and an explicit artifact-unavailable error.
The [worker reference](../executor-service/README.md#returned-artifacts)
defines quotas, byte paging and authorization.

## Deployment

Every verified `main` commit publishes separate Spring and frontend images from the same source commit. Copy the files under `deploy/` and a filled-in `.env.example` as `.env` into the host's deployment root. Supply the private domain/DNS configuration, one-time owner initialization token, repository/database credentials, separate data directories and four image pins. Run `deploy.sh --app-image <application-image> --app-revision <commit> --frontend-image <frontend-image>`; later runs without options redeploy the recorded pins. Both application revision labels must match, and PostgreSQL/Caddy references must carry registry digests.

For an operator-owned Compose installation, [existing-installation delivery](../notes/implemented/2026-09-08-existing-installation-delivery.md) updates only app/frontend images. Configure the protected updater and select `POKETTO_DEPLOY_LAYOUT=existing` with transfer mode; Compose files, environment settings and dependencies remain operator-owned.

Caddy owns public HTTPS, forwards `/api` and `/mcp` to Spring and other paths to Next.js, and blocks the management entrance. Success requires healthy containers plus the local certificate-verified website and API. HTTPS checks retry within the remaining `POKETTO_HEALTH_TIMEOUT` deadline (default 180 seconds) while certificates and routes become ready. `deploy/transfer.sh` transfers both application images when the host cannot reach GHCR; the host still needs Docker Hub access or the exact cached database/gateway digests. Its `--pull --sync` mode synchronizes current stack files while acquiring application images on the host. Automatic deployment stays separately enabled through the production environment. The host executor is installed and tested independently before setting `POKETTO_EXECUTOR_ENABLED=true`; missing isolation prerequisites fail closed. See the [stack delivery record](../notes/implemented/2026-09-05-blog-stack-delivery.md) for image identity, configuration, persistence and remaining real-installation acceptance.

Set `POKETTO_NETWORK_SUBNET` to an unused RFC1918 IPv4 CIDR with at least 16 addresses, and `POKETTO_NETWORK_DYNAMIC_RANGE` to a canonical strict subpool with at least eight addresses. Set `POKETTO_GATEWAY_INTERNAL_IP` to Caddy's fixed address outside that pool, excluding the subnet's network, first usable bridge and broadcast addresses. Deployment rejects invalid ranges before starting containers; Docker allocates the other services only from the dynamic pool. This deployment alone enables Tomcat forwarding and trusts only that gateway `/32`; Caddy rebuilds client address, protocol and host headers and removes `X-Forwarded-Port` before Spring. Other entry points explicitly default to `server.forward-headers-strategy=none`. `./gradlew proxyForwardingCheck` requires Docker and Python 3.10+ and verifies actual Compose address allocation plus real per-client and shared-account login limits; it is mandatory in `check` and CI.


## OAuth connections

Set `POKETTO_OAUTH_ISSUER=https://your-domain.example` on the application, with no trailing slash, to enable OAuth. Use the same public origin in `POKETTO_SECURITY_ALLOWED_ORIGINS`. Without an issuer, OAuth discovery and authorization stay disabled and static API keys remain usable. PostgreSQL retains connection and token-digest state. Use the current gateway configuration so the authorization-server and protected-resource discovery URLs reach Spring; existing-layout deployments must update their operator-owned gateway and environment explicitly.

Add `https://your-domain.example/mcp` in the MCP client, choose OAuth and leave client ID/secret empty for dynamic registration. The client opens Poketto's login and consent page. Sign in as a workspace owner, verify the application's return address, choose the requested permissions, and allow the connection. Private reading, private writing and publishing are separate choices and start unchecked. The client receives credentials only after consent; the account password stays with Poketto.

Select “保持连接” to permit rotating refresh tokens. In administration, “已连接应用” lists permissions and expiry and can disconnect each application independently. Disconnecting revokes access, refresh and affected execution sessions. Connection authorization lasts at most ninety days; a client must request fresh consent after expiry or revocation. Unsupported clients can still send a static key in `Authorization: Bearer <key>`.

The [OAuth decision](../notes/implemented/2026-09-11-mcp-oauth.md) owns protocol bounds, admission limits and interoperability coverage. Public-client DCR and S256 PKCE are supported; confidential-client secrets and CIMD are not advertised. This is MCP authorization, not social login or open account registration.
