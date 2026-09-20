# Consumer Identity and Site Policy

Date: 2026-09-20
Status: Proposed

## Problem

[Multi-user spaces](../implemented/2026-09-11-multiuser-workspaces-and-discovery.md)
require a registration invitation but let every account connect a repository. This
does not support open reader registration followed by an operator's decision to
permit community participation or personal-space creation. Removing an author's
publication eligibility must withdraw their websites without taking away the
workspace access needed to correct their content.

## Accounts and login

Replace registration invitations with verified email registration and Google
OpenID Connect login. Workspace invitations remain separate and keep their current
membership semantics. An email registration supplies a verified email, password
and display name; it does not require another login name. Existing account IDs,
passwords, login names, memberships and content remain intact. Existing accounts
can verify and bind an email after logging in.

Google login requests only basic identity scopes. Validate the provider identity,
signature, audience, issuer, expiry and callback state; use the provider subject as
the identity key. A verified Google identity can register without a second email
challenge or a local password. An existing account with the same email must be
authenticated before linking; email equality does not merge identities. Linking
and unlinking require an authenticated account and cannot remove its last login
method. Missing Google configuration hides that entrance rather than disabling
email login. Return to an approved local destination after login.

Email challenges are purpose-bound, six digits, expire after ten minutes, permit
at most five failed attempts and are single-use. Resending has a sixty-second
cooldown and replaces the previous code. Store only keyed challenge digests.
Consume challenges and create or update accounts atomically. Rate-limit by email,
source address and installation; the configurable installation default is one
hundred sends per UTC day. Resend retries use an idempotency key belonging to the
logical message. Delivery failure never reports a successful send. Password
recovery gives uniform anonymous responses and invalidates existing browser and
machine credentials after a reset.

## Fixed account groups

Each account has one group. Only a site administrator changes it. There is no
custom policy editor, multiple-group union or automatic promotion.

| Group | Site operations | Existing workspace operations |
|---|---|---|
| VIEWER | Public reading and own-account management | Existing membership and machine grants remain effective |
| COMMUNITY | Viewer operations and future community interactions | Same membership boundary; no space creation or website eligibility |
| CREATOR | Community operations, repository connection and website eligibility | Still requires the owning workspace grants |
| ADMINISTRATOR | Site account and group administration, plus creator eligibility | No implicit private-file or original-history access |

All new accounts are VIEWER and acquire no membership. Email verification, Google
login, GitHub connection and accepting a workspace invitation do not promote the
account. Community operations are reserved for their later implementation, not
exposed as working features in this delivery. The group replaces the independently
mutable administrator flag. Protect the last administrator and record who changed
each group, its previous and new value, the time and the operator's reason.

## Website withdrawal and restoration

Extend [website delivery](../implemented/2026-09-14-workspace-public-delivery.md)
with owner eligibility. A website is available only if its owner-controlled switch
is enabled, it has owners, every OWNER account is CREATOR or ADMINISTRATOR, and the
existing repository-policy and valid-snapshot requirements pass. A downgraded
ordinary member does not affect a workspace they do not own. In a shared-owner
workspace one ineligible owner is sufficient to prevent public delivery.

Keep the owner's switch as their desired state. Downgrading an account does not
overwrite it: restoring creator eligibility automatically restores previously
enabled websites once every owner is eligible. A switch the owner explicitly
turns off while restricted remains off. Enabling the switch while ineligible is
refused. Public metadata changes cannot evade the same eligibility check.

Withdrawal applies to discovery, search, site and document routes, RSS, sitemaps,
public images and original downloads, including replayed grants and cached paths.
Use the existing before/after and streaming-checkpoint authorization boundaries.
An ordinary authenticated reader does not bypass withdrawal. Previously delivered
client bytes cannot be recalled.

Membership-authorized reading, editing, uploads, moves, previews, exports and MCP
execution continue with their existing grants. Do not revoke account copies or
machine credentials merely for a group downgrade. Repository-public editing is
distinct from eligibility to expose a website. The owner UI shows the desired
switch, effective availability and restriction reason separately.

Site administrators may inspect the affected websites' currently eligible public
documents and their referenced public media for moderation. This entrance does
not disclose private files, original history, member credentials or arbitrary blob
identities, and remains unavailable to ordinary members without the relevant
workspace authorization.

## Delivery and data

This delivery explicitly permits incremental relational changes instead of the
development-phase rebuild default. Preserve account IDs, spaces, grants, content
and originals. Existing administrators become ADMINISTRATOR. Existing workspace
owners or members with content-write or publication grants become CREATOR; other
existing accounts become VIEWER. New accounts always use the VIEWER default.

The backend receives Resend credentials through deployment configuration, using
the GitHub secret POKETTO_RESEND_API_KEY. The from-address is operator configuration.
Google client ID, secret and redirect settings are separate configuration. Both
standard and existing-installation deployment paths must deliver runtime settings
without logging secrets or baking them into artifacts. Tests use fake providers;
real mail delivery and Google login require separate live verification.

Update the English and Chinese README only where shipped capabilities or existing
descriptions change. README is a product overview, not a progress or verification
report. Usage documentation owns configuration details; PRs own execution evidence.

## Alternatives

Keeping invitation-only registration prevents ordinary readers from joining.
Automatically promoting every verified email would make identity verification a
publication grant. Both are rejected.

Revoking workspace writes on downgrade would prevent authors from correcting the
content and would conflate site moderation with content ownership. Clearing every
website switch would lose the owner's intent and require manual reconstruction
when eligibility returns. Compute availability from its authorities instead.

Making administrators universal workspace owners would expose private content
unrelated to moderation. A bounded public-content review entrance provides the
necessary inspection without that authority.

## Acceptance

- Email registration, binding and recovery cover expiration, replay, cooldown,
  failed delivery, rate bounds, concurrent consumption and unique account creation.
- Google tests cover callback rejection, subject identity, explicit linking,
  duplicate email handling and last-login-method protection.
- PostgreSQL migration tests preserve existing identities and grants and verify
  initial groups; all newly registered identities are viewers.
- Creation is refused at the service boundary for ineligible accounts. Site
  administration rechecks the current group and cannot bypass private authorization.
- Downgrade withdraws all owned public sites and old public-media grants while
  member operations and machine sessions continue. Shared-owner and non-owner
  cases distinguish the intended withdrawal scope.
- Re-promotion restores eligible websites without overriding an owner-disabled
  switch. Concurrent group/publication changes cannot return an unauthorized public
  response after their final authorization check.
- The prescribed browser entrance verifies account flows, policy changes, owner
  remediation and public withdrawal/restoration on the changed tree. Real Resend
  and Google checks are reported separately from fixtures.

## Same-topic decisions

[Multi-user spaces](../implemented/2026-09-11-multiuser-workspaces-and-discovery.md)
remains authoritative until implementation, when its invitation-only registration
and unrestricted account creation eligibility are superseded by this record.
[Member permissions](../implemented/2026-09-12-member-content-permissions.md) and
[MCP OAuth](../implemented/2026-09-11-mcp-oauth.md) retain their workspace boundaries.
[Website delivery](../implemented/2026-09-14-workspace-public-delivery.md) gains the
owner-eligibility condition without replacing member access.

The rejected [consumer-account proposal](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md)
does not become active: this delivery does not provision a repository during
registration. User-authorized GitHub repository creation is a separate next phase.
Social interactions, personalized ranking, automatic repository creation, media
playback and persistent command shells are outside this first delivery.
