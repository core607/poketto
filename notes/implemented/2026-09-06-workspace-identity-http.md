# Workspace Identity and HTTP Authentication

Date: 2026-09-06
Status: Implemented

## Problem and scope

The [workspace boundary](2026-08-27-workspace-tenancy.md) needs independently revocable human and machine identities. This record implements the backend identity portion of [invitation-only membership](../proposed/2026-08-27-invitation-only-membership.md) and [phase-one delivery](../proposed/2026-09-05-phase-one-daily-use.md). Administration pages, content authoring entrances, MCP tools, and execution services remain outside this implementation. Consumer registration and personal-workspace provisioning retain their [separate proposal](../proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md).

## Decision

The `auth` module owns accounts, workspace memberships, invitations, API keys and capability checks in PostgreSQL. `web` maps browser requests to that service. Membership and key mutations serialize on the workspace row; authorization uses current stored membership and key state. Revocation events publish only after the database transaction commits.

An operator-supplied initialization token permits creation of the first account and default-workspace owner. A persistent singleton closes initialization permanently after success. Passwords use Spring Security's delegating PBKDF2 encoder with upgrade-on-login support. Login names normalize to lowercase ASCII; password failures have uniform responses and perform password verification for missing accounts too.

Human login uses Spring Security server-side sessions, session-ID rotation, logout invalidation and CSRF protection. Cookies are `HttpOnly`, `Secure`, `SameSite=Strict`, cookie-only and expire after 30 minutes of inactivity. The bounded in-memory login throttle limits attempts per normalized account and source address. When its table is full, requests needing new buckets receive 429 until existing buckets expire; this intentionally fails closed without allocating more memory. Auth and admin responses use `no-store`; supplied Origin headers must match an explicitly configured origin. Auth request bodies and login forms have a 16 KiB limit. Declared lengths are checked before dispatch. Unknown-length JSON and form requests are read up to the applicable limit plus one byte and rejected with 413 before business handling if oversized. Multipart asset requests retain servlet part parsing and its separately configured multipart limits.

Administration requests validate the current account membership before reading a body or resolving CSRF form parameters. Anonymous requests receive 401; suspended memberships receive 403. This authorization uses a completed database query and holds no database transaction while a client sends its body. Origin and declared-length rejection remain earlier, without consuming the request stream. The public login, initialization and invitation-registration entrances retain their separate authentication flow.

The upload, repository-patch and preview paths share an instance-wide admission limit of two active requests. Admission covers declared and unknown lengths, servlet multipart parsing and downstream processing. A full pool returns 429 without opening the request body; read failures and synchronous completion release admission. Asynchronous processing retains admission through timeout or error handling until completion. Patch and preview bodies retain a 6 MiB bound; uploads retain the 17 MiB multipart request and 16 MiB file bounds. Their POST entrances accept JSON and multipart respectively, rejecting unsupported types before buffering. Per-request limits alone cannot constrain concurrent buffering, so the admission limit also applies when several clients each stay within their request limit.

Only human owners administer invitations and memberships. Invitations contain 256 bits of randomness, expire after seven days, grant only `MEMBER`, and store only a SHA-256 token digest. A recipient may register an account or accept while authenticated; repeat acceptance by the same account is idempotent, but another account cannot reuse the token. Expired, revoked and unknown invitations have uniform errors. A suspended membership cannot be reactivated through invitation acceptance.

The last active owner cannot be removed or demoted. Suspending or demoting a membership revokes keys held or created by that account in the affected workspace; other workspace memberships remain independent. Promotion preserves existing keys and their explicitly issued capabilities, without granting the owner's additional capabilities to those keys. Member, invitation and key listings use deterministic pages with totals, a default size of 30 and maximum size of 100.

API keys bind their holder, creator, workspace and capabilities. Owners with `MANAGE_KEYS` may issue or revoke them; human members cannot escalate privileges. The default AI set is `READ_PRIVATE` and `WRITE_PRIVATE`. `PUBLISH`, `MANAGE_KEYS` and `EXECUTE_REPOSITORY` require explicit selection. Complete tokens appear only in the creation response and are stored as digests. Capability names reserve the future content and execution boundaries; defining them does not implement those tools.

The `/mcp` security chain accepts Bearer API keys independently of browser cookies, stores no browser security context, and reauthenticates asynchronous dispatches against current authority. It reserves the authentication boundary without installing an MCP transport or tool implementation.

## Operation

Set `POKETTO_AUTH_INITIALIZATION_TOKEN` privately before creating the first owner. An empty value disables initialization. Set `POKETTO_SECURITY_ALLOWED_ORIGINS` to comma-separated exact HTTP(S) origins, without paths, credentials, queries or fragments. Local HTTP development must explicitly set `POKETTO_SESSION_COOKIE_SECURE=false`; HTTPS keeps the secure default.

`POKETTO_SECURITY_ADMIN_BODY_CONCURRENCY` sets the positive admission limit and defaults to 2. Increasing it requires measuring heap headroom alongside body-buffer copies, JSON parsing, image operations and other requests. It does not change request byte limits or the separate MCP admission pool.

Browser clients obtain CSRF metadata from `GET /api/auth/csrf`, retain the cookie, and send its named header on mutations. Initialize with `POST /api/auth/initialize`, then submit the `username` and `password` form fields to `POST /api/auth/login`. Initialization and invitation registration return account IDs; neither logs the new account in. `GET /api/auth/me` resolves the current default-workspace role and capabilities. Owners manage invitations, members and keys under `/api/admin`; logout is `POST /api/auth/logout`.

## Alternatives and consequences

Shared owner credentials obscure attribution and cannot revoke one member independently. API keys remain suitable for machines; Spring Security sessions supply browser CSRF and session-fixation defenses. Self-service registration would add provisioning and discovery boundaries beyond invitation-only access.

Relational identity state is authoritative and is not reconstructible from Git. Login throttles and browser sessions are process-local; a restart clears them. Durable audit history, password recovery, delivery of invitations, and administration pages are not supplied by this backend slice. The broader membership proposal stays proposed until its remaining acceptance is satisfied.

## Verification

PostgreSQL integration tests exercise one-time initialization, token digests, invitation expiry and reuse, workspace isolation, last-owner concurrency, capability escalation denial, revocation and password-hash upgrades. Real HTTP integration tests exercise sessions, CSRF, origin rejection, throttling, request limits, Bearer isolation and paginated administration. Request-body observations verify anonymous rejection without body access, admission saturation with live slow clients, and recovery after disconnect. Security-chain and filter tests cover revoked membership, framing, overflow and synchronous/asynchronous admission release. Unit and module checks cover bounded throttle eviction and application ownership. Run the Gradle `check` task for this combined build and database change.
