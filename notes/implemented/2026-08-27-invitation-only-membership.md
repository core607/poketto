# Invitation-Only Membership

Date: 2026-08-27
Implemented: 2026-09-06

## Problem

A Poketto workspace serves its owner, trusted members and their AI agents. The [requirements](2026-08-25-requirements-and-architecture.md) allow trusted members but define only issued API keys; they do not say how a human joins or leaves a workspace while keeping an independent audit identity. Sharing the owner account or a long-lived key obscures attribution and prevents independent revocation, and any account-creation path must not by itself grant access to an existing workspace.

## Decision

A human account is an instance-level identity. A membership connects it to one workspace with the `OWNER` or `MEMBER` role, and one account may hold independent memberships in several workspaces. Joining an existing workspace happens only through an invitation issued by that workspace's owner. This record was consolidated on 2026-09-17 after the work shipped under the notes named below; each remains the authority for its part, and this summary defers to them wherever they are more specific.

[Workspace identity and HTTP authentication](2026-09-06-workspace-identity-http.md) owns the `auth` module: accounts, memberships, workspace invitations, API keys and capability checks in PostgreSQL. Passwords use Spring Security's delegating PBKDF2 encoder with upgrade on login; a missing account and a wrong password fail uniformly. Browser login uses server-side sessions with session-ID rotation and CSRF protection; session cookies are `HttpOnly`, `Secure` by default and `SameSite=Lax`, and expire after 30 minutes of inactivity. A bounded in-memory throttle limits login attempts per normalized account and per source address. A workspace invitation carries 256 bits of randomness, is stored only as a SHA-256 digest, expires after seven days, grants only `MEMBER` and is single-use: revoked, expired, unknown and consumed tokens fail with one uniform error, while repeat acceptance by the same account is idempotent. The last active owner cannot be removed or demoted. Suspending or demoting a membership revokes the keys that account holds or created in that workspace and leaves its other memberships untouched. API keys bind holder, creator, workspace and capabilities, appear complete only in the creation response and are stored as digests; only owners with `MANAGE_KEYS` issue or revoke them, and no member can widen its own authority.

[Operator administrator setup](2026-09-11-operator-administrator-setup.md) owns the one-time initialization. An interactive `admin init` command in the deployed container creates the first site administrator and default-workspace owner, a durable singleton closes initialization permanently, and there is no browser initialization endpoint and no default password.

[Registration invitations](2026-09-11-registration-invitations.md) owns the split between creating an account and joining a workspace. Registration is gated by a separate, issuer-owned invitation credential and creates an account with no membership; a workspace invitation is accepted only by a signed-in account and cannot register one. The two credentials cannot be substituted for each other.

[Member content permissions](2026-09-12-member-content-permissions.md) refines the `MEMBER` role: membership alone grants public-scope reading, and a workspace invitation carries the initial `READ_PRIVATE`, `WRITE_PRIVATE` and `PUBLISH` grants the owner selected, defaulting to none.

[Blog and browser administration](2026-09-06-blog-browser-interface.md) owns the administration pages for members and keys. The registration note owns the login, registration and join entrances, and [multi-user workspaces and discovery](2026-09-11-multiuser-workspaces-and-discovery.md) owns the account-level space list.

[Service diagnostics](2026-09-14-service-diagnostics.md) owns audit attribution. `AuditRecords` writes one record per authentication outcome, invitation issue, revocation and redemption, key issue and revocation, and membership change to the `poketto.audit` logger, naming subjects by identifier and never a login name, password, token or invitation code.

## Alternatives

**Share the owner account.** Smallest to build, but members would share credentials and lose independent revocation, attribution and authorization.

**Issue API keys to humans.** Keys suit scripts and agents; they offer no browser session, login protection or member lifecycle. They remain the machine credential.

**Let any registered account discover a workspace and request admission.** An enumeration and spam surface that makes the owner process unsolicited requests. An invitation reveals only the workspace its owner chose to share.

**Put a long-lived login credential in the invitation.** A leaked link would expose the account indefinitely. A short-lived one-time invitation establishes the membership, after which ordinary credentials and sessions take over.

## Consequences

Relational identity state is authoritative and cannot be rebuilt from Git. Login throttles and browser sessions are process-local, so a restart clears them. Invitations travel out of band: nothing sends email or SMS, and a token never enters logs or audit records. Account creation and workspace admission are separate acts with separate credentials, so a workspace owner cannot bypass the site's registration policy, and site administration is distinct from workspace ownership. Membership suspension is workspace-scoped: the account keeps its other memberships and its account-level entrance. Password recovery, social login, passkeys and email delivery of invitations are not supplied.

## Implementation and acceptance

The backend shipped under the workspace identity note on 2026-09-06; its verification section lists the PostgreSQL, HTTP, security-chain and filter tests it relies on. The initialization command, registration split, member permissions and audit records shipped under their notes above, each with its own verification section. [Multi-user daily-use acceptance](2026-09-15-multiuser-daily-use-acceptance.md) consolidates the evidence on the authorized HTTPS installation, including `RegistrationIntegrationIT` for policy defaults, distinct credential kinds, expiry, rollback and concurrent single redemption, and browser evidence for login without anonymous initialization and no-space account management. Names checked in the tree on 2026-09-17: `MembershipRole`, `Capability`, `AuthService`, `RegistrationService`, `RegistrationInvitationPolicy`, `AdministratorSetup`, `AuditRecords` and `LoginThrottleFilter` under `src/main/java/io/github/core607/poketto/auth`, and the session cookie settings in `src/main/resources/application.properties`.

## Same-topic audit

The owning notes above are retained and remain the authority for their contracts. [Phase-one delivery](2026-09-05-phase-one-daily-use.md) selects this membership model for the first daily-use installation, and [workspace and tenant boundaries](2026-08-27-workspace-tenancy.md) is its foundation; both stay independent. The consumer account and personal-workspace direction this proposal deferred is [rejected](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md) in its automatic-provisioning form; [multi-user workspaces and discovery](2026-09-11-multiuser-workspaces-and-discovery.md) implements invitation-gated registration and explicit workspace creation instead. No note is archived or rejected by this consolidation.
