# Managed Workspace Connections

Date: 2026-09-11

## Decision

Account-level HTTP operations can connect an existing private GitHub or CNB HTTPS repository as an additional workspace. This implements repository provisioning from [multi-user workspaces](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md), retaining the [workspace tenant boundary](2026-08-27-workspace-tenancy.md) and [remote repository authority](2026-09-01-remote-repository-authority.md). Browser workspace selection, publication controls, and cross-workspace discovery are separate parts of that proposal.

The `spaces` module coordinates creation through the `auth`, `workspace`, and `content` contracts. A request supplies a UUID idempotency key, display name, lowercase public slug, repository URL, Git username, and provider token. The database records the prospective workspace UUID, encrypted credentials, validation stage, and a five-minute renewable attempt lease before contacting the provider. A retry with the same request key keeps its workspace identity; changing its name, slug, or repository is rejected. A ready attempt is replayed without another provider request. Overlapping attempts return the existing stage, and a restart can resume an expired lease. Validation admits at most two concurrent requests per application process.

GitHub and CNB metadata provide immutable repository identity. Canonical URL and provider identity are independently unique, including a comparison with the operator-configured default repository. Git access validation reads the fetch and receive-pack advertisements without sending a commit or changing a ref. A nonempty repository requires `main`. The catalog entry, owner membership, repository binding, and ready stage commit together; failure leaves no partly created workspace. Public delivery starts disabled. The upstream repository must remain private because Poketto's `private/` directory cannot conceal files already public on a Git hosting service.

## Credentials and transport

`POKETTO_REPOSITORY_CREDENTIAL_KEY` is a Base64-encoded 32-byte deployment secret. Missing configuration disables new connections; it never causes plaintext storage. AES-256-GCM binds each encrypted envelope to its workspace ID and canonical repository URI, using a fresh nonce. Keep the key in protected deployment configuration and include it with database recovery material. Replacing or losing this key makes existing managed credentials unreadable. Provider credential rotation uses the existing key; deployment-key rotation is not exposed by this API.

Tokens must allow repository metadata reads and Git content reads/writes. CNB requires `repo-basic-info:r` in addition to Git permissions. The operator's default repository token needs metadata access as well when connecting another repository on the same provider. A token that works with Git alone may therefore need additional metadata permission.

Only the fixed GitHub and CNB HTTPS origins are accepted. Embedded credentials, queries, fragments, alternate ports, encoded path components, and unsupported origins are rejected. Provider metadata requests have response-byte and request-time limits. Managed Git transport accepts only the connected repository's smart Git endpoints, disables automatic redirects and proxy selection, checks resolved addresses for private/local networks, and retains TLS certificate and hostname verification. This is a fixed-provider policy, not support for arbitrary self-hosted Git servers.

The application container needs public DNS answers for these provider hosts. Fake-IP desktop proxies or cloud DNS routes that map a provider into private/link-local space are rejected. Configure a public resolver on that container when necessary; do not allow private addresses merely to accommodate such a route. Real Git advertisement probes passed against both providers in an isolated Linux container using a public resolver. These read-only probes do not establish the configured private repository token's write permissions.

Credential rotation requires current human-owner authorization and preserves the repository identity. Fixed error codes cross HTTP boundaries; provider response bodies, tokens, and raw transport exceptions do not. A successful attempt removes its extra staged credential copy.

## HTTP contract

All routes are under `/api/auth/workspaces`, require browser account authentication, retain CSRF checks, and use the bounded authentication body and no-store response policy:

- `GET /creation-policy` reports whether deployment encryption is configured.
- `POST /creations` accepts `requestId`, `displayName`, `slug`, `repository`, `username`, and `token`. It returns `workspaceId`, `stage` (`VALIDATING`, `FAILED`, or `READY`), a fixed `failureCode` when applicable, and `retryAfterSeconds` for an active validation lease. A retry can omit both credential fields to reuse its encrypted server-side copy; a new credential pair replaces that copy. Browser storage need not retain provider tokens to recover an interrupted creation.
- `GET /creations/{requestId}` reads only the authenticated account's attempt.
- `PUT /{workspaceId}/repository-credentials` rotates `username` and `token` for the same established managed binding. It cannot rotate the separately operator-configured default binding or select a different remote.

## Alternatives and consequences

Provider-side repository creation would require broader token authority and recovery of remote resources. Connecting an existing repository keeps that operation with its provider. One database transaction around network validation would hold relational locks while waiting on third parties; a persisted lease releases those locks and prevents a late attempt from completing after another retry takes ownership. URL-only uniqueness would miss repository renames, so immutable provider identity also participates in duplicate detection.

PostgreSQL integration exercises retry, response replay, duplicate binding rollback, account isolation, and overlapping validation. Real Spring HTTP coverage checks CSRF, origin and body limits, encrypted storage, and foreign-account denial. Cipher tests reject tampering and cross-workspace or cross-repository ciphertext reuse. Provider parsing and transport tests cover read-only, renamed, archived, and unsafe targets. The production-shaped Linux probe validates metadata and Git read/write advertisements against the actual private CNB repository without changing its refs. Browser creation evidence is still required before describing the complete multi-space user flow as delivered.
