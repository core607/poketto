# Invitation-Only Membership

Date: 2026-08-27
Status: Proposed

## Problem

A Poketto knowledge workspace needs to serve its owner, trusted members, and their AI agents. The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) allow trusted members but define only issued API keys; they do not define how a human joins or leaves a workspace while retaining an independent audit identity.

Sharing an owner account or long-lived API key obscures attribution and prevents independent revocation. Open registration would also change the trust boundary of a personal self-hosted product. A member must join a specific workspace through an invitation initiated by its owner.

## Proposal

### Accounts and memberships

- A human account is an instance-level identity. A membership connects an account to one workspace with the `OWNER` or `MEMBER` role. One account may join several workspaces and have an independent role in each.
- The first account uses a one-time instance initialization flow to become the instance administrator and `OWNER` of the default workspace. The entry point closes permanently after initialization and leaves no default password.
- A workspace always retains at least one active `OWNER`. Disabling a member, leaving a workspace, or transferring ownership cannot remove the last owner.
- Human sessions, AI API keys, and system tasks are distinct principal types. Audit records preserve the acting principal and do not attribute a member's or AI's work to the owner.

This proposal defines the security contract for accounts, sessions, and invitations; page styling is outside its scope. Human login uses local credentials and server-side sessions managed by Spring Security. Passwords use an upgradeable adaptive password encoder and are never stored or logged in plaintext. Login attempts are throttled per account and per source address to slow online password guessing. Browser sessions use `HttpOnly`, `Secure`, and an appropriate `SameSite` cookie policy, and every state-changing request has CSRF protection.

### Invitation lifecycle

- Only a workspace `OWNER` may create, list, or revoke that workspace's invitations. An invitation is bound to its workspace and target role before issuance. v1 invitations grant only `MEMBER` and cannot grant instance administration.
- Tokens come from a cryptographically secure random source and appear only in the creation response. The database stores only the token hash, workspace, role, creator, creation time, expiry, revocation time, and use time.
- An invitation expires after seven days by default and succeeds only once. Anonymous callers receive the same failure semantics for an expired, revoked, used, or unknown token to prevent enumeration.
- A recipient creates an account or signs in to an existing account before joining the target workspace. Reaccepting the same membership is idempotent success. A token cannot join another workspace or lower an existing higher privilege.
- The owner delivers the invitation link out of band. v1 sends no email or SMS and never writes the token to application logs, audit details, referrers, or analytics events.

### Members and keys

- An owner may disable a membership. Disabling it immediately blocks new workspace sessions and revokes every active API key that the member created or holds in that workspace. Memberships in other workspaces are unaffected.
- An AI API key is bound to a workspace, creator, and capability set. `MANAGE_KEYS`, member invitations, and member suspension are owner-only by default. A member or its AI cannot elevate its own authority.
- Open registration has no route, API, or configuration switch. Adding it requires a new proposal covering abuse, identity verification, compliance, and resource budgets.

## Implementation scope and dependencies

The implemented [workspace and tenant boundary](../implemented/2026-08-27-workspace-tenancy.md) is this proposal's foundation. Membership does not depend on the implemented [content repository foundation](../implemented/2026-08-26-content-foundation.md), but human end-to-end acceptance also requires administration pages for initialization, login, invitations, and member management.

The first implementation includes accounts, memberships, invitations, server-side sessions, owner initialization, password login, logout, invitation acceptance, membership suspension, authorization checks, audit attribution, and database integration tests. It excludes email delivery, OAuth, social login, passkeys, open registration, self-service workspace creation, and an instance-level operations console.

## Alternatives considered

**Share the owner account.** This is the smallest implementation, but it prevents independent revocation, attribution, and authorization and requires members to share credentials.

**Issue API keys to humans.** API keys suit scripts and AI agents, not browser sessions, login protection, and member lifecycle. They remain the machine credential.

**Allow open registration followed by owner approval.** This expands the anonymous attack surface and introduces spam registration and identity-verification concerns. Invitations directly express the current trust relationship.

**Put a long-lived login credential in the invitation.** A leaked link would expose the account indefinitely. A short-lived one-time invitation establishes an account or membership, after which normal credentials and sessions take over.

## Acceptance

- Both requirements documents include local accounts, workspace memberships, and the invitation-only member boundary while continuing to exclude open registration.
- First-time initialization creates the single instance administrator and owner of the default workspace; the same entry point cannot be used again.
- Invitation tokens have sufficient entropy, appear once, exist only as hashes at rest, and become invalid after use, revocation, or expiry.
- An invitation can establish only its bound workspace's `MEMBER` membership. Cross-workspace reuse, privilege escalation, and removal of the last owner are rejected.
- Two members have independent page actions, API keys, and audit identities. Disabling one membership does not affect the account's authority in another workspace.
- Anonymous failures do not reveal whether an invitation exists. Logs, metrics, and response headers contain no invitation token, password, or session secret.
- Focused tests cover CSRF, session fixation, login throttling, password-hash upgrades, and authorization denial paths.
- PostgreSQL integration tests, Spring Modulith verification, and `./gradlew check` pass.

## Risks

Local passwords and sessions make Poketto responsible for credential protection. The implementation must use mature Spring Security mechanisms rather than custom cryptography. Passkeys or an external identity provider should be added only after a concrete need appears.

An instance-level account may belong to several workspaces, so account suspension and membership suspension have different effects. Administration pages and errors must name the affected object and must not imply that a workspace owner can delete an instance account.
