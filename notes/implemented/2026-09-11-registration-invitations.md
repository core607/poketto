# Registration Invitations

Date: 2026-09-11

## Decision

Registration invitations create account identities without adding workspace membership. This is the account-level service foundation for [multi-user spaces and discovery](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md); that proposal still owns the browser registration flow, removal of invitation-based workspace registration, installation command, and workspace creation.

`RegistrationInvitationPolicy` owns issuance eligibility and any future allowance rule. Its configured policy admits site administrators and, only when enabled, ordinary accounts. Workspace ownership does not imply site administration. `POKETTO_REGISTRATION_USER_INVITATIONS_ENABLED` defaults to `false`; setting it to `true` permits authenticated ordinary accounts, including accounts without a space, to issue registration invitations. There is no fixed per-user invitation quota. Issuance takes the issuer's account-row lock so a future policy can count and reserve an allowance atomically.

An invitation contains 256 bits of randomness, is stored only as a digest, expires after seven days, and can register one account. Redemption verifies the invitation before password hashing, then locks and revalidates it before atomically creating the account and consuming the invitation. Invalid, expired, revoked, and consumed credentials fail uniformly. Failed account creation rolls back consumption. Site-administrator status is always false for accounts created by registration.

Issuers can list and revoke only their own registration invitations, even when ordinary issuance has subsequently been disabled. Disabling issuance does not revoke existing credentials. Workspace invitations and registration invitations cannot be substituted for one another. The [workspace identity record](2026-09-06-workspace-identity-http.md) retains the independently enforced workspace invitation contract.

## HTTP contract

All mutations require the browser CSRF token and allowed origin. Auth request-body limits, no-store responses, no-referrer policy, and login-throttle address limits also cover registration. The invitation issuance address limit is a short request window, not a per-user product quota.

| Endpoint | Behavior |
|---|---|
| `GET /api/auth/account` | Authenticated account ID, login name, site-administrator status, and issuance eligibility; requires no workspace membership |
| `POST /api/auth/register` | Anonymous CSRF-protected registration with `token`, `login`, and `password`; returns the account ID, without starting a login session |
| `POST /api/auth/registration-invitations` | Authenticated policy-checked issuance; returns the invitation ID and its only complete-token response |
| `GET /api/auth/registration-invitations` | Issuer-only deterministic pages, default 30 and maximum 100 entries; offset is bounded at 100,000 |
| `DELETE /api/auth/registration-invitations/{id}` | Idempotently revokes an unused invitation belonging to the caller; does not reveal another issuer's invitation |

Account identity and workspace identity are distinct responses. The existing `/api/auth/me` workspace response remains governed by the workspace HTTP contract until the scoped-entrance work in the delivery proposal replaces its callers. API keys cannot impersonate an account at the registration service boundary.

## Alternatives and consequences

Reusing a workspace invitation for platform registration would grant unrelated access and let any workspace owner bypass site registration policy. A single account-owned invitation table also permits independent revocation, expiry, and future quota decisions without changing workspace membership.

Registration state is authoritative PostgreSQL state. It is not derived from Git. Public registration remains invitation-gated; this service does not implement browser account recovery, automatically create repositories, or grant administrative roles.

## Verification

PostgreSQL tests exercise administrator-only defaults, ordinary-user enablement without membership, issuer isolation, expiry, revoke, credential substitution, failed-account rollback, concurrent redemption, and replacement issuance policy. HTTP tests use real sessions and CSRF to register and authenticate an account with no space while denying access to workspace administration.
