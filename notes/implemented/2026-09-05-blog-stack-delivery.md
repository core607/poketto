# Blog Stack Delivery Entrance

Date: 2026-09-05

## Problem

The [continuous-delivery baseline](2026-09-03-continuous-delivery.md) deploys Spring and PostgreSQL. The [phase-one delivery](../proposed/2026-09-05-phase-one-daily-use.md) also needs the Next.js blog and administration, one HTTPS origin for browser sessions and MCP, and a separately installed host executor. A frontend image from another commit can silently disagree with Spring request and response contracts.

## Decision

The [Compose stack](../../deploy/compose.yaml) runs Spring, PostgreSQL, Next.js and Caddy. CI publishes independent application and frontend images from the same verified commit. The host verifies both revision labels against one full source commit before replacing any container. Application/frontend registry references use digests; transferred archives use per-commit tags whose revision labels are rechecked on every deployment. PostgreSQL and Caddy always use immutable registry digests.

[The shared SSH delivery script](../../deploy/transfer.sh) synchronizes both Compose files, Caddyfile and the deployment entrance in both delivery modes. Default mode verifies both local source labels and sends one checksummed archive containing the two application images. `--pull` requires their registry digests and lets the host acquire them. Restricted GHCR access therefore does not require host GHCR access, but Docker Hub must remain reachable for the pinned database and gateway images unless those exact digests are already cached. Docker-save image IDs are not substituted for registry digests.

Candidate pins reach the private `.env` only after all application, frontend and gateway containers are healthy, Spring's loopback health entrance answers, and certificate-verified HTTPS requests to the local gateway serve both the website and public API. The previous application, frontend, database and gateway pins are retained together. A failed candidate leaves recorded pins unchanged; there is no automatic rollback or data-volume replacement. A Compose wait timeout bounds startup before the separate entrance checks.

## Origin and credentials

[Caddyfile](../../deploy/Caddyfile) sends `/api`, `/api/*`, `/mcp` and `/mcp/*` to Spring, blocks `/actuator` and its descendants, and sends other paths to Next.js. Only Caddy exposes public ports. Frontend startup depends on the Spring process rather than public snapshot readiness, and its health checks the administration page; an invalid publication policy therefore does not prevent opening the repair interface. Spring's host port is loopback-only; Next.js has no published host port. The deployment domain is a validated DNS name supplied through private operator configuration. DNS and reachable ports 80/443 are prerequisites for automatic HTTPS. Certificate data has a separate persistent directory; deployment never cleans it as derived content.

The frontend receives only the public HTTPS origin and internal public-API base URL. Repository credentials, the one-time owner initialization token, signing keys and content storage stay with Spring or the independent host worker. Spring's Origin allowlist derives from the same public origin and its session cookie remains Secure. The initializer's database state prevents reopening owner creation after first use. Resource limits and asset cache/grant bounds are configurable; defaults are bounded starting values, not a measured production resource profile.

## Host execution prerequisite

Execution is disabled unless explicitly configured. Enabling it adds [the executor overlay](../../deploy/compose.executor.yaml) only after the independently installed worker's runtime directory, socket, export directory and signing key exist with the expected ownership and permissions and `poketto-executor.service` is active. This topology requires rootful Linux Docker without UID remapping; the worker's application UID/GID matches Spring's UID/GID. The execution account belongs to a separate group.

The production application image explicitly creates UID/GID 10001; automatic system-group allocation is not used. The root-owned runtime parent uses mode 0751; `control.sock` uses mode 0660 with the Spring group. Spring mounts the whole runtime parent read-only, so a worker restart can replace the socket inode. The Spring-owned export directory is shared with the host worker, and its Ed25519 signing private key is mounted as a read-only mode-0600 file. The worker holds only the public verification key. These mounts do not expose the application repository cache or managed originals to the execution account. Missing prerequisites fail deployment before container replacement; the application adapter still verifies the worker's peer identity and signed lease protocol. No ordinary subprocess replaces unavailable isolation.

The generic deployment entrance does not install privileged worker services or rewrite worker policy. Their installation and native boundary tests are separate prerequisites, and phase-one acceptance still requires enabled, verified execution.

## Alternatives and consequences

A single image containing both web runtimes would make source matching implicit but couple their processes, health and resource limits. Two labeled images keep those boundaries explicit without requiring another release manifest. Transferring official database and gateway images as unverified local tags would avoid Docker Hub access at the cost of weakening their pin contract; this entrance retains digest pulls and cached digests instead.

Deploying this stack changes HTTP routing and requires a real domain before the entrance can confirm success. Existing operator settings must add the frontend image, gateway pin, domain, initialization token and gateway data directory. Real production installation, certificate issuance, browser and MCP clients, container image builds, and host resource measurements remain acceptance work; code and script tests do not establish those results. Backups are outside this phase and are not deployment prerequisites.

## Verification

Both Compose variants can be validated with the real Compose parser without starting containers. [The pinned gateway validation script](../../deploy/tests/validate_gateway.sh), also required by CI, provisions the shipped Caddy configuration inside the exact gateway image with network access disabled and disposable memory-backed storage. It requires a working Docker daemon and is distinct from configuration-text assertions. A native Linux run on Docker 27.0.3 pulled the exact Caddy digest and returned `Valid configuration` with UID/GID 10002, all capabilities dropped except `NET_BIND_SERVICE`, no-new-privileges, a read-only root, writable temporary storage, 128 MiB memory, 0.5 CPU and 128 processes. Networking was disabled and no ports were published. The validation container and its isolated staging files were removed. This verifies configuration provisioning under the production permission/resource profile, not certificate issuance or serving browser traffic.

Gradle `appImageIdentityCheck`, included in `check`, builds the production Dockerfile from the current image inputs and verifies the resulting image ID and source revision. A disposable pinned Python fixture presents a root-owned mode-0751 runtime directory and a root:10001 mode-0660 Unix socket. The actual application image's default identity must connect as UID/GID 10001, while an unrelated identity is denied. This catches image-account mismatches independently of mocked deployment checks. Clean inputs receive the HEAD revision label; local changes to image inputs append `-dirty`, which cannot satisfy the deployment entrance's exact-commit check. The generated image ID and revision are recorded below `build/app-image-identity/` for later acceptance. The reusable script also accepts an existing image and its full revision:

```sh
bash deploy/tests/validate_app_identity.sh IMAGE FULL_COMMIT
```

The deployment script suite uses fake Docker, SSH, curl and disk-space commands. It exercises matched and mismatched image revisions, two-image archives and failure before remote mutation, stack-file synchronization on both registry and archive paths, digest and domain validation, service/HTTPS failures, fixed-version redeployment, and fail-closed executor prerequisites. Configuration assertions verify the declared routing and credential separation; they do not replace real Caddy runtime, certificate, browser or worker tests.

The continuous-delivery, frontend, retrieval/execution and phase-one records retain their independent rationale and outstanding acceptance requirements. No broader proposal is marked complete by this wiring change.
