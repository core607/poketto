# Invitation-Only Membership

Date: 2026-08-27
Implemented: 2026-09-06

## Problem

A Poketto workspace serves its owner, trusted members and their AI agents. The [requirements](2026-08-25-requirements-and-architecture.md) allow trusted members but define only issued API keys; they do not say how a human joins or leaves a workspace while keeping an independent audit identity. Sharing the owner account or a long-lived key obscures attribution and prevents independent revocation, and any account-creation path must not by itself grant access to an existing workspace.

## Decision

A human account is an instance-level identity. A membership connects it to one workspace with the `OWNER` or `MEMBER` role, and one account may hold independent memberships in several workspaces. Joining an existing workspace happens only through an invitation issued by that workspace's owner. The notes below own the mechanisms; this record owns the membership model and its alternatives.

[Workspace identity and HTTP authentication](2026-09-06-workspace-identity-http.md) owns the `auth` module: accounts, memberships, workspace invitations, API keys and capability checks in PostgreSQL, with their password, throttle, invitation, last-owner and revocation contracts. A workspace invitation grants only `MEMBER`, is single-use and expires after seven days. Suspending or demoting a membership revokes the keys that account holds or created in that workspace and leaves its other memberships untouched. [Long-lived browser sessions](2026-09-23-long-lived-browser-sessions.md) owns browser session storage and lifetime.

[Operator administrator setup](2026-09-11-operator-administrator-setup.md) owns the one-time initialization. An interactive `admin init` command in the deployed container creates the first site administrator and default-workspace owner, a durable singleton closes initialization permanently, and there is no browser initialization endpoint and no default password.

[Consumer identity and site policy](2026-09-20-consumer-identity-and-site-policy.md) owns account creation: verified email registration or Google login creates a `VIEWER` account with no membership. A workspace invitation is accepted only by a signed-in account and cannot create one. The two acts use separate credentials that cannot be substituted for each other.

[Member content permissions](2026-09-12-member-content-permissions.md) refines the `MEMBER` role: membership alone grants public-scope reading, and a workspace invitation carries the initial `READ_PRIVATE`, `WRITE_PRIVATE` and `PUBLISH` grants the owner selected, defaulting to none.

[Blog and browser administration](2026-09-06-blog-browser-interface.md) owns the administration pages for members and keys. [Multi-user workspaces and discovery](2026-09-11-multiuser-workspaces-and-discovery.md) owns the account-level space list, where a signed-in account accepts a workspace invitation.

[Service diagnostics](2026-09-14-service-diagnostics.md) owns audit attribution. `AuditRecords` writes one record per authentication outcome, invitation issue, revocation and redemption, key issue and revocation, and membership change to the `poketto.audit` logger, naming subjects by identifier and never a login name, password, token or invitation code.

## Alternatives

**Share the owner account.** Smallest to build, but members would share credentials and lose independent revocation, attribution and authorization.

**Issue API keys to humans.** Keys suit scripts and agents; they offer no browser session, login protection or member lifecycle. They remain the machine credential.

**Let any registered account discover a workspace and request admission.** An enumeration and spam surface that makes the owner process unsolicited requests. An invitation reveals only the workspace its owner chose to share.

**Put a long-lived login credential in the invitation.** A leaked link would expose the account indefinitely. A short-lived one-time invitation establishes the membership, after which ordinary credentials and sessions take over.

## Consequences

Relational identity state is authoritative and cannot be rebuilt from Git. The login throttle is process-local, so a restart clears it. Workspace invitations travel out of band: nothing sends them by email or SMS, and a token never enters logs or audit records. Account creation and workspace admission are separate acts with separate credentials, so a workspace owner cannot bypass the site's registration policy, and site administration is distinct from workspace ownership. Membership suspension is workspace-scoped: the account keeps its other memberships and its account-level entrance. Passkeys and email delivery of workspace invitations are not supplied.

## Verification

The owning notes above name the tests that pin their contracts. Related: [phase-one delivery](2026-09-05-phase-one-daily-use.md) selected this model for the first installation, [workspace and tenant boundaries](2026-08-27-workspace-tenancy.md) is its foundation, and provisioning a personal workspace automatically at registration is [rejected](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md).
