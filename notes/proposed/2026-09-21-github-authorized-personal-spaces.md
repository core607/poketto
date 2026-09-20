# GitHub-authorized Personal Spaces

Date: 2026-09-21
Status: Proposed

## Problem

[Managed repository connections](../implemented/2026-09-11-managed-workspace-connections.md)
require a user to create a private repository and supply a provider token. A
personal-space entrance should instead ask the user to authorize GitHub and
confirm a repository name. The repository must belong to that user's personal
GitHub account, with no platform-owned repository or organization provisioning.

This is an explicit space-creation operation after account registration.
[Site eligibility](../implemented/2026-09-20-consumer-identity-and-site-policy.md)
still requires CREATOR or ADMINISTRATOR. Connecting GitHub does not change that
group, grant membership, or add a Poketto login method.

## User flow and provider boundary

1. A signed-in eligible account authorizes the configured GitHub App. Bind the
   single-use callback to that browser session, account and credential version.
   Use GitHub's immutable user ID; reject organization identities and never infer
   identity from an email address or user-supplied login name.
2. Show the verified personal GitHub account, requested repository name and fixed
   private visibility before accepting creation. Derive the owner server-side.
   Starting OAuth or returning from installation does not itself create a repo.
3. Create through the GitHub App's user access token. Record the returned immutable
   repository ID and verify its owner and private visibility.
4. If the App installation cannot access the new repository, return the user to
   GitHub's installation settings to select it. Verify the App, personal owner,
   repository ID and granted permissions on return; callback parameters are not
   proof of access. Resume the same operation after this step.
5. Initialize the content template and bind the space. Show completion only after
   its repository authority, owner membership and initialization are ready. The
   public website starts disabled.

GitHub documents [personal repository creation](https://docs.github.com/en/rest/repos/repos#create-a-repository-for-the-authenticated-user)
for App user access tokens with Administration write permission; installation
tokens are not accepted for that endpoint. Set `private=true` explicitly.
The [add-repository endpoint](https://docs.github.com/en/rest/apps/installations#add-a-repository-to-an-app-installation)
does not accept App tokens. Do not substitute a personal access token or silently
expand installation access to all repositories. The exact interaction between a
new repository and a selected-repository installation needs real-provider
acceptance; the UI must support an explicit installation step instead of assuming
automatic access.

## Durable creation and recovery

The `spaces` module owns one durable operation per account and request ID, with a
prospective workspace ID, verified GitHub owner ID, requested name, immutable
repository ID when known, current stage and bounded attempt lease. Reusing a
request ID with different input fails. Overlapping requests observe the same
operation; they do not create another repository or workspace.

Stages distinguish awaiting authorization, creating the repository, awaiting
installation access, initializing, ready, disconnected and uncertain creation.
Provider I/O occurs outside relational locks. Recheck account identity, creator
eligibility, connection version and attempt lease before each remote mutation
and before committing the resulting binding. A downgrade during creation leaves
the operation resumable without granting a new space or deleting its repository.

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

## Credentials, synchronization and revocation

Keep provider grant and token issuance behind a content-owned repository
credential contract, shared by creation and ordinary Git transport. Extend the
current encrypted credential handling rather than putting App authentication in
the executor or exporting it to browser code. The `spaces` module coordinates the
operation and the `web` module owns HTTP and callback entrances.

Persist encrypted user access and refresh tokens with account/provider identity
binding and version checks. Respect provider expiry, serialize refresh-token
replacement and fail closed after refresh rejection. Store no provider token in
browser storage, repository content, logs or public responses. Installation access
tokens are short-lived runtime credentials, requested for exactly the recorded
repository ID with only metadata/content permissions needed for Git operations;
routine synchronization does not retain Administration write authority.

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

## Configuration and acceptance

The operator supplies the App ID, client ID, client secret, private signing key
and webhook secret through protected deployment configuration. Require the App's
signing key as single-line Base64 PKCS#8 at the application boundary; deployment
converts GitHub's downloaded PEM to preserve the line-oriented settings channel.
Use the client ID as the issuer of short-lived RS256 App JWTs, following
[GitHub's authentication contract](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app).
Require the App's
repository Administration write and Contents write permissions, plus metadata
read. Explain those requested permissions before redirecting to GitHub. Do not
request organization permissions or account email access for this feature.
Missing App configuration disables this entrance without disabling manual
repository connections.

Tests must cover duplicate requests, concurrent attempts, lost creation responses,
same-name conflicts, initialization interruption, token expiry/refresh races,
foreign callbacks, stale sessions, downgrade during creation, installation owner
or repository mismatch, revoked access and repository transfer. Real GitHub
acceptance must create a user-confirmed disposable private repository, complete
selected-repository authorization, initialize and synchronize through a scoped
installation token, and verify reconnection behavior. Synthetic provider tests
cannot establish these provider capabilities.

Update both public README files and usage references with the resulting entry
flow and limits when implemented. Do not describe this proposal as available.

## Alternatives and related decisions

Platform-owned repositories violate user ownership. Broad personal access tokens
preserve the current setup burden and are not the authorization mechanism for
this entrance. Installation-wide tokens unnecessarily expand routine Git access.
Automatic repository creation during registration remains rejected by the
[earlier consumer-account proposal](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md);
this proposal instead requires an eligible, signed-in user's explicit action.
The account, workspace, public-visibility and existing manual-connection decisions
remain authoritative outside this extension.
