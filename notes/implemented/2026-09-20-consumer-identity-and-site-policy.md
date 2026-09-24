# Consumer Identity and Site Policy

Date: 2026-09-20
Status: Implemented

## Problem

[Multi-user spaces](2026-09-11-multiuser-workspaces-and-discovery.md)
required a registration invitation but let every account connect a repository. This
did not support open reader registration followed by an operator's decision to
permit community participation or personal-space creation. Removing an author's
publication eligibility must withdraw their websites without taking away the
workspace access needed to correct their content.

## Accounts and login

Verified email registration and Google login replace registration invitations.
Google authenticates through OpenID Connect. Workspace invitations remain separate and keep their current
membership semantics. An email registration supplies a verified email, password
and display name; it does not require another login name. Existing account IDs,
passwords, login names, memberships and content remain intact. Existing accounts
can verify and bind an email after logging in.

Registration invitations, the mechanism this replaces, gated account creation
behind an issuer-owned credential: 256 random bits stored only as a digest,
single-use, valid for seven days and revocable by its issuer. Site administrators
issued them, and ordinary accounts could too when the operator enabled it. They
let an operator admit trusted people before verification and send limits existed,
and they kept account creation apart from workspace joining. They stopped fitting
once ordinary readers were meant to join freely: what needed gating was space
creation and website eligibility, which the groups below now control, while email
proofs, send limits and the VIEWER default bound anonymous abuse. Migration `V10`
drops the invitation table, and the former registration routes answer 404. An
invitation gate may return only if open registration must close again, for
example under abuse the send limits cannot bound. It must stay a credential
separate from workspace invitations: reusing a workspace invitation to create an
account would grant unrelated access and let any workspace owner bypass the site's
registration policy, so a workspace invitation never creates an account.

`GET /api/auth/account` returns the signed-in account's identity and requires no
workspace membership.

Google login requests only basic identity scopes. Validate the provider identity,
signature, audience, issuer, expiry and callback state; use the provider subject as
the identity key. A verified Google identity can register without a second email
challenge or a local password. An existing account with the same email must be
authenticated before linking; email equality does not merge identities. Linking
and unlinking require an authenticated account and cannot remove its last login
method. Missing Google configuration hides that entrance rather than disabling
email login. Return to an approved local destination after login.

Use Spring Security's authorization-code client and OIDC ID-token decoder for
token exchange and cryptographic validation. Bind each ten-minute browser flow to
its session, a random state and nonce, and an S256 PKCE verifier. Start flows by a
CSRF-protected POST, consume callback state once, and rotate the local session on
success. A linking flow must still belong to the same signed-in account after the
provider exchange. Retain the Google subject and verified email, not provider
access or refresh tokens. Only the account and MCP connection pages are permitted
return destinations.

The token transport allows five seconds to connect and ten seconds per blocked
read. It bounds success and error response bodies to 64 KiB before parsing, and
does not follow token-endpoint POST redirects. Provider failure returns a local
login failure without exposing token payloads.

Email challenges are purpose-bound, six digits, expire after ten minutes, permit
at most five failed attempts and are single-use. Resending has a sixty-second
cooldown and replaces the previous code. Store only keyed challenge digests.
Consume challenges and create or update accounts atomically. Rate-limit by email,
source address and installation; the configurable installation default is one
hundred sends per UTC day. Resend retries use an idempotency key belonging to the
logical message. Delivery failure never reports a successful send. Password
recovery gives uniform anonymous responses and invalidates existing browser and
machine credentials after a reset.

Reserve sends in PostgreSQL before contacting Resend, without holding database
locks during provider I/O. The initial limits are ten sends per email per UTC day,
twenty per source address per UTC hour, and the configurable installation limit.
The email cooldown spans purposes and UTC-day boundaries. Failed deliveries use
their reservation, remain unusable and require a new challenge. Transport retries
reuse the same message ID and payload. Derive the challenge HMAC key from the
Resend credential with a separate purpose label; rotating that credential expires
outstanding proofs. Rate buckets store keyed address identifiers. Retire expired
challenge and rate rows during later reservations.

Binding proofs belong to the signed-in account. Recovery proofs belong to the
account holding the email when the challenge is issued, and consumption checks
that the same account still owns that email. Moving an email between accounts
cannot transfer an outstanding recovery proof.

## Fixed account groups

Each account has one group. Only a site administrator changes it. There is no
custom policy editor, multiple-group union or automatic promotion.

| Group | Site operations | Existing workspace operations |
|---|---|---|
| VIEWER | Public reading and own-account management | Existing membership and machine grants remain effective |
| COMMUNITY | Viewer operations and community interactions | Same membership boundary; no space creation or website eligibility |
| CREATOR | Community operations, repository connection and website eligibility | Still requires the owning workspace grants |
| ADMINISTRATOR | Site account and group administration, plus creator eligibility | No implicit private-file or original-history access |

All new accounts are VIEWER and acquire no membership. Email verification, Google
login, GitHub connection and accepting a workspace invitation do not promote the
account. The [community decision](2026-09-23-community-interactions.md)
defines participation and retained history after downgrade. The group replaces the independently
mutable administrator flag. Protect the last administrator and record who changed
each group, its previous and new value, the time and the operator's reason.

## Website withdrawal and restoration

Extend [website delivery](2026-09-14-workspace-public-delivery.md)
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

Expose moderation reads under `/api/auth/site/workspaces/{workspaceId}/review`.
Use the verified repository-public snapshot independently of website delivery;
do not accept source text, historical commits or arbitrary original identities.
The administrator-only document response contains the selected article's rendered
media mappings, not the entire snapshot. Review images use an isolated registry
of at most 128 grants, so their tokens cannot be replayed through anonymous image
routes. Original downloads must be referenced by the selected current article,
and recheck site-administrator authority at the existing streaming checkpoints.
Role revocation or snapshot replacement invalidates subsequent reads.

Account administration lists the account's owned spaces and their desired and
eligible website states. Workspace owners can read the current ineligible owners'
latest group-change reasons; administrators without membership do not gain that
owner endpoint. The author can continue correcting content and request a group
change from the operator.

## Delivery and data

Migration `V8` preserved account IDs, spaces, grants, content and originals, and
assigned initial groups: former instance administrators became ADMINISTRATOR,
workspace owners and members holding private-write or publication grants became
CREATOR, and every other account became VIEWER. New accounts always start as VIEWER.

Resend credentials, the from-address and the Google client settings are separate
deployment configuration; [usage](../../docs/usage.md) owns their names and the
updater procedure. Both deployment paths deliver them without logging secrets or
baking them into artifacts. The existing-installation updater accepts only the
identity keys, the public support contact and the [GitHub App settings](2026-09-21-github-authorized-personal-spaces.md)
through its protected stdin channel. This extends the image-only boundary of
[existing-installation delivery](2026-09-08-existing-installation-delivery.md)
without admitting repository credentials or other runtime changes. Registry
credentials reach only the pull helper, and archive delivery rejects them. The
transfer script never replaces the privileged updater. The protected overlay is
mode 0600; deployment state and command output contain fingerprints, not
credentials. The combined configuration is validated before restarting, both
layouts reject an incomplete Google credential pair, a pending attempt accepts
only the same images and candidate configuration, and there is no automatic
rollback.

Public `/privacy` and `/terms` pages describe account data, basic Google identity,
Resend delivery, workspace visibility, moderation, and content retention. The
footer and login form link to both. The public contact is frontend runtime
configuration so installations do not inherit another operator's email address.

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

## Verification

`EmailChallengesIntegrationIT`, `EmailAccountsHttpIntegrationIT`,
`GoogleIdentityHttpIntegrationIT`, `GoogleOidcProviderTests`, `GoogleTokenClientTests`,
`SiteGroupMigrationIntegrationIT`, `SitePolicyIntegrationIT` and
`ModerationDownloadsTests` pin these rules with fake providers; real Resend delivery
and Google login need separate live verification.

Related: [multi-user spaces](2026-09-11-multiuser-workspaces-and-discovery.md),
[member permissions](2026-09-12-member-content-permissions.md) and
[MCP OAuth](2026-09-11-mcp-oauth.md) keep their workspace boundaries, and
[website delivery](2026-09-14-workspace-public-delivery.md) gains the
owner-eligibility condition without replacing member access. Registration
provisions no repository, so the [consumer-account proposal](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md)
stays rejected; [GitHub-authorized personal spaces](2026-09-21-github-authorized-personal-spaces.md)
add explicit repository creation and [community interactions](2026-09-23-community-interactions.md)
add social features.
