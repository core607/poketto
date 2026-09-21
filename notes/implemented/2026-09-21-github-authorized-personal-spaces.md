# GitHub-authorized Personal Spaces

Date: 2026-09-21
Status: Implemented

## Problem

[Managed repository connections](../implemented/2026-09-11-managed-workspace-connections.md)
require a user to create a private repository and supply a provider token. A
personal-space entrance asks the user to authorize GitHub and
confirm a repository name. The repository must belong to that user's personal
GitHub account, with no platform-owned repository or organization provisioning.

This is an explicit space-creation operation after account registration.
[Site eligibility](../implemented/2026-09-20-consumer-identity-and-site-policy.md)
still requires CREATOR or ADMINISTRATOR. Connecting GitHub does not change that
group, grant membership, or add a Poketto login method.

## User flow and provider boundary

1. A signed-in eligible account starts its first authorization of the configured GitHub App. Bind the
   single-use callback to that browser session, account and credential version.
   Use GitHub's immutable user ID; reject organization identities and never infer
   identity from an email address or user-supplied login name.
2. Show the verified personal GitHub account, requested repository name and fixed
   private visibility before accepting creation. Derive the owner server-side.
   Starting OAuth or returning from installation does not itself create a repo.
3. Verify an active App installation on that personal account with Administration
   write, Contents write and metadata read before recording dispatch intent.
   GitHub requires an existing selected repository for the first installation;
   the user can explicitly create an empty private repository for that purpose.
   Create through the GitHub App's user access token. Record the returned immutable
   repository ID and verify its owner and private visibility.
4. If the App installation cannot access the new repository, return the user to
   GitHub's installation settings to select it. Verify the App, personal owner,
   repository ID and granted permissions on return; callback parameters are not
   proof of access. Resume the same operation after this step.
5. Initialize the content template and bind the space. Show completion only after
   its repository authority, owner membership and initialization are ready. The
   public website starts disabled.

Resolve installation settings from the authenticated App and verified personal
owner. An existing installation uses its verified numeric ID; a missing one uses
the configured App's verified slug. Construct links on `github.com` rather than
following provider-supplied URLs. Keep settings reachable for a suspended or
under-permissioned installation, but do not treat opening settings as proof that
access was restored. Recheck the account and grant after resolving the link.

GitHub documents [personal repository creation](https://docs.github.com/en/rest/repos/repos#create-a-repository-for-the-authenticated-user)
for App user access tokens with Administration write permission; installation
tokens are not accepted for that endpoint. Set `private=true` explicitly.
The [add-repository endpoint](https://docs.github.com/en/rest/apps/installations#add-a-repository-to-an-app-installation)
does not accept App tokens. Do not substitute a personal access token or silently
expand installation access to all repositories. GitHub's [installation contract](https://docs.github.com/en/apps/using-github-apps/installing-your-own-github-app)
grants access to repositories created by the App, including for selected-repository
installations. The UI retains explicit installation recovery when access is missing.

## Durable creation and recovery

The `spaces` module owns one durable operation per account and request ID, with a
prospective workspace ID, verified GitHub owner ID, requested name, immutable
repository ID when known, current stage and bounded attempt lease. Reusing a
request ID with different input fails. Overlapping requests observe the same
operation; they do not create another repository or workspace.

Expose account-scoped creation, result and continuation endpoints with session
authentication and CSRF protection for writes. A bounded history returns the
original non-secret request and result so losing browser storage does not lose
the recovery entrance. Reading history does not require creator eligibility;
continuing an operation still applies its current creation or ownership rules.

Stages distinguish awaiting authorization, creating the repository, awaiting
installation access, initializing, ready, disconnected and uncertain creation.
Provider credential exchanges occur outside relational locks. Recheck account
identity, creator eligibility, connection version and attempt lease before
repository creation and before committing a new workspace binding. A downgrade during creation leaves
the operation resumable without granting a new space or deleting its repository.

A five-minute lease serializes each creation attempt. Preparation is resumable
until the provider's creation callback commits the dispatch intent. After that
commit, an expired lease resumes through reconciliation only. Local admission
rejection before dispatch and a definitive provider rejection are distinguished
from lost responses. A late worker cannot replace the result of a newer lease.
An HTTP 403 with GitHub's exact `Resource not accessible by integration` error
confirms a permission denial and leaves the original request retryable after
installation repair. Other 403 responses, malformed replies, rate limits and
transport failures remain uncertain after dispatch; they cannot trigger a new POST.
Store a confirmed remote result even if site eligibility was withdrawn during
the request; recording that fact grants no workspace or membership. Account
restrictions block further creation independently of a disconnected GitHub grant.

GitHub repository creation is not a database transaction. Record intent before
the request and its immutable result before initialization. A lost response must
not cause a blind POST retry or adoption based only on repository name, owner or
creation time. Persist a server-generated random creation marker before the POST
and include it in the repository description in that same creation request.
Reconciliation requires the exact marker, recorded personal owner and private
visibility before persisting the immutable repository ID. A missing repository
after an ambiguous response does not prove the POST failed; retain the uncertain
state instead of repeating it. The marker is operation metadata, not a credential;
after the repository ID is durable, later access uses that ID and users may edit
the description. Do not rewrite their description to remove the marker.
A pre-existing same-name repository without this operation's marker is a
conflict, never an implicit connection or overwrite target.

Template initialization uses the existing repository initialization contract and
must preserve unexpected user changes. Binding uniqueness covers immutable
provider identity as well as canonical URI. A ready operation replays its result;
it does not repeat creation or initialization. Failures never delete remote repos.
The existing manual GitHub/CNB connection entrance remains available separately.

Commit the workspace, owner membership, verified App binding and initialization
stage together. Installation verification compares the recorded repository ID,
personal owner and private visibility; edited descriptions do not invalidate a
known repository. A prepared binding expires within sixty seconds and is
rechecked with the current grant in the commit transaction. The operator's
configured repository participates in duplicate-identity checks.

Once that transaction commits, initialization acts on an existing owned space.
It retains current owner permissions after a site-group downgrade and does not
recreate the workspace or repository. Check the operation lease at the Git write
checkpoint and current ownership before recording READY. Initialization failure
keeps the binding and permits inspection of missing template files on retry;
response loss cannot justify overwriting files. READY records the initialization
commit only after all template files are present. Public delivery remains off.

## Credentials, synchronization and revocation

Keep provider grant and token issuance behind a content-owned repository
credential contract, shared by creation and ordinary Git transport. Extend the
current encrypted credential handling rather than putting App authentication in
the executor or exporting it to browser code. The `spaces` module coordinates the
operation and the `web` module owns HTTP and callback entrances.

Persist encrypted user access and refresh tokens with account/provider identity
binding and version checks. Respect provider expiry, serialize refresh-token
replacement and fail closed after refresh rejection. Bind the encrypted envelope
to the App client, Poketto account, GitHub owner and grant version, using the
existing repository credential key with a separate encryption context. A bounded
database lease owns each refresh; provider I/O runs outside database locks.
Late refresh results cannot overwrite revocation or newer consent. An expired
lease or ambiguous provider response requires fresh consent because GitHub may
already have consumed the refresh token. A local admission rejection before any
request was sent leaves the existing grant available for retry. Store no provider
token in browser storage, repository content, logs or public responses. Installation access
tokens are short-lived runtime credentials, requested for exactly the recorded
repository ID with only metadata/content permissions needed for Git operations;
routine synchronization does not retain Administration write authority.

Prepare Git credentials before acquiring workspace authorization locks, then
recheck workspace permissions inside the operation. Keep the prepared credential
scoped to that workspace and operation, with a sixty-second local lease bounded
by provider expiry. Recheck the grant version, recorded binding and lease before
opening Git transport and each HTTP exchange; disconnect or binding replacement
invalidates a prepared credential. Already dispatched requests may still finish.
An App binding failure never falls back to manual or operator credentials.
Manual bindings retain their encrypted credentials and reject a prepared token
after rotation. App bindings cannot enter manual token rotation.

Prepared repository proofs and creation leases that expire while waiting cause
transient failures, not authorization revocations. Continue the original creation request
or prepare fresh access for the same operation without requiring new consent.
Ambiguous remote writes still require reconciliation before another write.
Authorized browser workspace routes expose fixed `REPOSITORY_RETRY` or
`REPOSITORY_RECONNECT` recovery codes, without exception messages or provider
diagnostics. The client renders its own instructions and never retries a write
automatically. Public routes retain generic failures even for signed-in visitors.

Provider exchanges use fixed GitHub HTTPS origins over direct public-network
connections and refuse redirects. Bound concurrent exchanges, request and response
bytes including error bodies, and the HTTP response deadline. Provider headers
and credential payloads never enter diagnostic output.

Before issuing or using repository credentials, verify the connection remains
authorized and the repository belongs to the recorded personal owner and remains
private. Handle signed GitHub authorization, installation and repository-access
revocations, and revalidate provider access so missed webhook delivery cannot
preserve authorization indefinitely. Reject invalid signatures and replayed
deliveries. Revocation prevents further provider operations and invalidates local
credential leases; the space reports that reconnection is needed. Preserve the
user's remote repository, stored originals and membership records.

GitHub revocation and site-group withdrawal are independent. A group downgrade
does not revoke an existing repository connection or its member/MCP grants.
Restoring a group cannot restore a revoked GitHub authorization. Reconnection
must verify the same owner and immutable repository identity; a repository
transfer or a different same-name repository cannot silently replace it.
An account may reauthorize an existing grant after a group downgrade so its
retained space permissions remain usable. Reauthorization does not restore
creation eligibility, publish a space, or establish membership.

### Revocation delivery and commit races

The stateless `POST /api/hooks/github` entrance authenticates the exact request
bytes using `X-Hub-Signature-256` before parsing. It caps JSON bodies at 25 MiB,
admits one body at a time, and uses a five-second
database transaction with a two-second lock timeout. No provider request runs
inside the webhook transaction. Browser sessions, API keys and CSRF tokens do
not authorize this endpoint; other browser endpoints retain their existing gates.

Persist each `X-GitHub-Delivery` UUID under the configured App client and commit
its receipt with all effects. Reject an already committed delivery with 409;
a failed transaction leaves it retryable. Retain these small receipts across
restarts and account reauthorization instead of expiring them without a trusted
event timestamp. GitHub's [delivery guidance](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)
uses the same identifier for a redelivery. The signature authenticates the body,
not an event chronology: a first-seen delayed revocation still closes access and
requires explicit reconnection. Never infer permission restoration from a later
arrival, an installation addition or an unsuspend event.
Gateway access and process logs omit both webhook signature headers; the
authenticator still reaches the application unchanged.

Authorization revocation clears that App owner's encrypted grants and refresh
leases. Installation deletion or suspension closes its bindings; selected-repo
removal closes the specified repositories. GitHub can omit the removed list
when changing from all repositories to selected repositories, so that event
closes the installation's bindings until each is verified again. Repository
deletion, transfer, publication, archival or rename requires reconnection of
the same immutable identity. Subscribe to repository events as well as the
App's default authorization and installation events.

Persist installation and immutable-repository revocation epochs even when no
space binding exists yet. Sample them before provider verification and lock and
compare them when committing creation or reconnection. This prevents a webhook
received during provider I/O from being lost because the workspace was not yet
bound. A first OAuth consent likewise samples its owner's authorization epoch,
revalidates the user token, and compares the epoch in the account transaction.
Grant versions still prevent late refresh results from restoring revoked access.
Epochs and revocation receipts contain identifiers only, never provider payloads
or credentials. Accounts, memberships, remote repositories and originals survive.

## Configuration and acceptance

The operator supplies the App ID, client ID, client secret, private signing key
and webhook secret through protected deployment configuration. Require the App's
signing key as single-line Base64 PKCS#8 at the application boundary; deployment
converts GitHub's downloaded PEM to preserve the line-oriented settings channel.
The [operator guide](../../docs/github-app.md) owns App registration and protected
settings. Both deployment layouts forward these settings literally and reject
incomplete App setting updates before replacing containers. The PEM converter validates RSA
key material through OpenSSL before emitting a single Base64 line; no temporary
key file or secret-bearing command argument is needed. Clearing the client secret
and private key disables authorization and App-backed repository operations,
including synchronization of existing spaces. Retaining the App identity and
webhook secret keeps revocation processing available in that state.
Use the client ID as the issuer of short-lived RS256 App JWTs, following
[GitHub's authentication contract](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app).
Require the App's
repository Administration write and Contents write permissions, plus metadata
read. Explain those requested permissions before redirecting to GitHub. Do not
request organization permissions or account email access for this feature.
Missing App configuration disables this entrance without disabling manual
repository connections.
The browser callback is `/api/auth/workspaces/github/callback` at the configured
public HTTPS origin. Authorization initiation and disconnection require a current
account session and CSRF protection. The callback consumes its matching browser
state once and rechecks that session after provider exchange, before persisting
credentials; it never signs the browser into a Poketto account.

Tests must cover duplicate requests, concurrent attempts, lost creation responses,
same-name conflicts, initialization interruption, token expiry/refresh races,
foreign callbacks, stale sessions, downgrade during creation, installation owner
or repository mismatch, revoked access and repository transfer. Real GitHub
acceptance must create a user-confirmed disposable private repository, complete
selected-repository authorization, initialize and synchronize through a scoped
installation token, and verify reconnection behavior. Synthetic provider tests
cannot establish these provider capabilities.

Real GitHub acceptance on 2026-09-21 verified browser authorization, installation
limited to an explicitly approved empty private repository, App-created private
repository access, template initialization and content saves through scoped
installation credentials. Removing that repository from the installation delivered
a signed webhook that revoked its binding before another application operation.
The browser retained a rejected write as an unsaved draft and the remote commit
did not change. Restoring GitHub access left the binding revoked until the owner
explicitly verified the same repository; reads and saves then succeeded, with
public delivery still disabled. Two regression tests reproduced the initial
installation-permission denial being misclassified as uncertain creation; the
preflight and explicit-denial recovery cover that failure.

## Alternatives and related decisions

Platform-owned repositories violate user ownership. Broad personal access tokens
preserve the current setup burden and are not the authorization mechanism for
this entrance. Installation-wide tokens unnecessarily expand routine Git access.
Automatic repository creation during registration remains rejected by the
[earlier consumer-account proposal](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md);
this entrance instead requires an eligible, signed-in user's explicit action.
The account, workspace, public-visibility and existing manual-connection decisions
remain authoritative outside this extension.
