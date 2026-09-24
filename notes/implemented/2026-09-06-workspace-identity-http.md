# Workspace Identity and HTTP Authentication

Date: 2026-09-06
Status: Implemented

## Problem and scope

The [workspace boundary](2026-08-27-workspace-tenancy.md) needs independently revocable human and machine identities. This record owns the backend identity contracts of [invitation-only membership](2026-08-27-invitation-only-membership.md) and [phase-one delivery](2026-09-05-phase-one-daily-use.md). Administration pages, MCP authorization and account registration have their own records: [blog and browser administration](2026-09-06-blog-browser-interface.md), [MCP OAuth](2026-09-11-mcp-oauth.md) and [consumer identity](2026-09-20-consumer-identity-and-site-policy.md).

## Decision

The `auth` module owns accounts, workspace memberships, invitations, API keys and capability checks in PostgreSQL. `web` maps browser requests to that service. Membership and key mutations serialize on the workspace row; authorization uses current stored membership and key state. Revocation events publish only after the database transaction commits.

[Operator administrator setup](2026-09-11-operator-administrator-setup.md) creates the first account and default-workspace owner; a persistent singleton closes initialization permanently. Passwords use Spring Security's delegating PBKDF2 encoder with upgrade-on-login support. Login names normalize to lowercase ASCII; password failures have uniform responses and perform password verification for missing accounts too.

Human login uses Spring Security server-side sessions, session-ID rotation, logout invalidation and CSRF protection. Cookies are `HttpOnly`, `Secure`, `SameSite=Lax` (for cross-site OAuth navigation; see [MCP OAuth](2026-09-11-mcp-oauth.md)) and cookie-only; [long-lived browser sessions](2026-09-23-long-lived-browser-sessions.md) owns their PostgreSQL storage and idle lifetime. The bounded in-memory login throttle limits attempts per normalized account and source address. When its table is full, requests needing new buckets receive 429 until existing buckets expire; this intentionally fails closed without allocating more memory. Auth and admin responses use `no-store`; supplied Origin headers must match an explicitly configured origin. Auth request bodies and login forms have a 16 KiB limit. Declared lengths are checked before dispatch. Unknown-length JSON and form requests are read up to the applicable limit plus one byte and rejected with 413 before business handling if oversized. Multipart asset requests retain servlet part parsing and its separately configured multipart limits.

Administration requests validate the current account membership before reading a body or resolving CSRF form parameters. Anonymous requests receive 401; suspended memberships receive 403. This authorization uses a completed database query and holds no database transaction while a client sends its body. Origin and declared-length rejection remain earlier, without consuming the request stream. The public login and registration entrances retain their separate authentication flow. [Workspace routing](2026-09-11-workspace-browser-and-mcp-routing.md) owns how a request selects its workspace: administration routes name it explicitly, and MCP resolves it from the credential.

The upload, repository-patch and preview paths share an instance-wide admission limit of two active requests. Admission covers declared and unknown lengths, servlet multipart parsing and downstream processing. A full pool returns 429 without opening the request body; read failures and synchronous completion release admission. Asynchronous processing retains admission through timeout or error handling until completion. Patch and preview bodies retain a 6 MiB bound; uploads retain the 17 MiB multipart request and 16 MiB file bounds. Their POST entrances accept JSON and multipart respectively, rejecting unsupported types before buffering. Per-request limits alone cannot constrain concurrent buffering, so the admission limit also applies when several clients each stay within their request limit.

Only human owners administer invitations and memberships. Invitations contain 256 bits of randomness, expire after seven days, grant only `MEMBER`, and store only a SHA-256 token digest. Only a signed-in account accepts an invitation; an invitation cannot register an account. Repeat acceptance by the same account is idempotent, but another account cannot reuse the token. Expired, revoked and unknown invitations have uniform errors. A suspended membership cannot be reactivated through invitation acceptance.

The last active owner cannot be removed or demoted. Suspending or demoting a membership revokes keys held or created by that account in the affected workspace; other workspace memberships remain independent. Promotion preserves existing keys and their explicitly issued capabilities, without granting the owner's additional capabilities to those keys. Member, invitation and key listings use deterministic pages with totals, a default size of 30 and maximum size of 100.

API keys bind their holder, creator, workspace and capabilities. Owners with `MANAGE_KEYS` may issue or revoke them; human members cannot escalate privileges. The default AI set is `READ_PRIVATE` and `WRITE_PRIVATE`. `PUBLISH`, `MANAGE_KEYS` and `EXECUTE_REPOSITORY` require explicit selection. Complete tokens appear only in the creation response and are stored as digests.

The `/mcp` security chain accepts Bearer credentials independently of browser cookies, stores no browser security context, and reauthenticates asynchronous dispatches against current authority.

## Operation

Set `POKETTO_SECURITY_ALLOWED_ORIGINS` to comma-separated exact HTTP(S) origins, without paths, credentials, queries or fragments. Local HTTP development must explicitly set `POKETTO_SESSION_COOKIE_SECURE=false`; HTTPS keeps the secure default.

`POKETTO_SECURITY_ADMIN_BODY_CONCURRENCY` sets the positive admission limit and defaults to 2. Increasing it requires measuring heap headroom alongside body-buffer copies, JSON parsing, image operations and other requests. It does not change request byte limits or the separate MCP admission pool.

Browser clients obtain CSRF metadata from `GET /api/auth/csrf`, retain the cookie, and send its named header on mutations. Login submits the `username` and `password` form fields to `POST /api/auth/login`; logout is `POST /api/auth/logout`. `GET /api/auth/workspaces/{workspaceId}/me` resolves the caller's role and capabilities in that workspace. Owners manage invitations, members and keys under `/api/admin/workspaces/{workspaceId}`.

## Alternatives and consequences

Shared owner credentials obscure attribution and cannot revoke one member independently. API keys remain suitable for machines; Spring Security sessions supply browser CSRF and session-fixation defenses. Self-service registration, owned by [consumer identity](2026-09-20-consumer-identity-and-site-policy.md), creates accounts without workspace access.

Relational identity state is authoritative and is not reconstructible from Git. The login throttle is process-local; a restart clears it. Audit records go to the `poketto.audit` log described by [service diagnostics](2026-09-14-service-diagnostics.md), not to a database table, and workspace invitations are delivered out of band.

## Verification

`AuthIntegrationIT`, `BrowserSecurityIntegrationIT` and `AdminPaginationIntegrationIT` pin the database and real-HTTP contracts; `OriginAndBodyFilterTests`, `AdminBodyFilterTests`, `BrowserBodySecurityTests` and `LoginThrottleTests` pin body admission, request limits and throttle eviction.
