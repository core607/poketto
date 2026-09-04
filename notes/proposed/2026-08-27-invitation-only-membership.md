# Invitation-Only Membership

Date: 2026-08-27
Status: Proposed

The [repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md) record the delivered subset and its remaining integration gaps.

[Phase-one delivery](2026-09-05-phase-one-daily-use.md) selects the self-hosted initialization, invitation, membership, and key lifecycle for the first daily-use installation. Consumer provisioning remains outside that delivery.

## Problem

A Poketto knowledge workspace needs to serve its owner, trusted members, and their AI agents. The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) allow trusted members but define only issued API keys; they do not define how a human joins or leaves a workspace while retaining an independent audit identity.

Sharing an owner account or long-lived API key obscures attribution and prevents independent revocation. Consumer registration may create an account and its personal workspace, but it must not grant access to an existing workspace. A member joins an existing workspace only through an invitation initiated by that workspace's owner.

## Proposal

### Accounts and memberships

- A human account is an instance-level identity. A membership connects an account to one workspace with the `OWNER` or `MEMBER` role. One account may join several workspaces and have an independent role in each.
- A self-hosted instance may use a one-time initialization flow to create its first account, instance administrator, and default-workspace `OWNER`. The entry point closes permanently after initialization and leaves no default password. The consumer account and personal-workspace flow belongs to the [consumer accounts proposal](2026-09-01-consumer-accounts-and-personal-workspaces.md).
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
- This proposal does not define consumer account creation or personal-workspace provisioning. An account created through the consumer flow still needs an invitation before it can join someone else's workspace.

## Implementation scope and dependencies

The implemented [workspace and tenant boundary](../implemented/2026-08-27-workspace-tenancy.md) is this proposal's foundation. Membership does not depend on the implemented [content repository foundation](../implemented/2026-08-26-content-foundation.md), but human end-to-end acceptance also requires administration pages for initialization, login, invitations, and member management.

The first implementation includes accounts, memberships, invitations, server-side sessions, self-hosted owner initialization, password login, logout, invitation acceptance, membership suspension, authorization checks, audit attribution, and database integration tests. It excludes email delivery, OAuth, social login, passkeys, consumer account creation, personal-workspace provisioning, and an instance-level operations console.

## Alternatives considered

**Share the owner account.** This is the smallest implementation, but it prevents independent revocation, attribution, and authorization and requires members to share credentials.

**Issue API keys to humans.** API keys suit scripts and AI agents, not browser sessions, login protection, and member lifecycle. They remain the machine credential.

**Let any registered account discover a workspace and request admission.** This creates an enumeration and spam surface and makes the owner process unsolicited requests. An explicit invitation reveals only the workspace the owner chose to share.

**Put a long-lived login credential in the invitation.** A leaked link would expose the account indefinitely. A short-lived one-time invitation establishes an account or membership, after which normal credentials and sessions take over.

## Acceptance

- Both requirements documents distinguish account registration from invitation-only admission to an existing workspace.
- Self-hosted first-time initialization creates the instance administrator and owner of the default workspace; the same entry point cannot be used again. Consumer account creation does not expose or reopen that entrance.
- Invitation tokens have sufficient entropy, appear once, exist only as hashes at rest, and become invalid after use, revocation, or expiry.
- An invitation can establish only its bound workspace's `MEMBER` membership. Cross-workspace reuse, privilege escalation, and removal of the last owner are rejected.
- Two members have independent page actions, API keys, and audit identities. Disabling one membership does not affect the account's authority in another workspace.
- Anonymous failures do not reveal whether an invitation exists. Logs, metrics, and response headers contain no invitation token, password, or session secret.
- Focused tests cover CSRF, session fixation, login throttling, password-hash upgrades, and authorization denial paths.
- PostgreSQL integration tests, Spring Modulith verification, and `./gradlew check` pass.

## Risks

Local passwords and sessions make Poketto responsible for credential protection. The implementation must use mature Spring Security mechanisms rather than custom cryptography. Passkeys or an external identity provider should be added only after a concrete need appears.

An instance-level account may belong to several workspaces, so account suspension and membership suspension have different effects. Administration pages and errors must name the affected object and must not imply that a workspace owner can delete an instance account.
