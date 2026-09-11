# Workspace Browser and MCP Routing

Date: 2026-09-11

## Decision

Browser administration selects a workspace through `/api/admin/workspaces/{workspaceId}/...`. Repository text, directory listings, moves, search, previews, uploads, image and attachment delivery, portable exports, members, invitations, keys and application connections all retain that identifier. The [identity HTTP boundary](2026-09-06-workspace-identity-http.md) checks membership before consuming administration bodies. Its existing origin, CSRF, request-byte and concurrency limits apply to the selected route. An unscoped administration URL cannot select the default space.

`GET /api/auth/account` identifies the account independently of spaces. `GET /api/auth/workspaces` lists active memberships with deterministic pagination, names, roles and capabilities. `GET /api/auth/workspaces/{workspaceId}/me` rechecks one membership. The workbench uses `workspace` and `tab` query parameters and remounts workspace content when selection changes. Each component's API client captures its workspace identifier, so an outstanding save cannot switch repositories when a different tab or view selects another space. Unsaved edits require an explicit discard before leaving their view.

The account view connects existing private GitHub or CNB repositories through [managed connections](2026-09-11-managed-workspace-connections.md). A browser-tab draft retains only the request identifier, name, slug and repository URL. Provider tokens remain outside browser storage. A timed-out submission can query or retry the same durable request; invalid input can be corrected, and a completed request opens its recorded workspace. Accepting an invitation selects the returned workspace and displays a success receipt.

Registration detects workspace invitation codes before submission and explains where to use them. Invalid or expired invitations receive a specific, locally defined message from the authentication problem code; arbitrary server diagnostics are never displayed as guidance.

Successful logout clears workspace and document navigation from the current URL before displaying login. A subsequent account does not inherit the previous account's selected workspace. Failed logout retains the current account and navigation.

[OAuth consent](2026-09-11-mcp-oauth.md) loads account and request information without requiring default-space membership. The user selects one owned space before approving permissions. An account without an eligible space receives a create/join entrance and can refresh its choices without restarting an otherwise valid authorization request. Rejection requires no space. Approved connections permanently retain the selected workspace in their backing key; refresh cannot select another one.

The `/mcp` resource remains shared. Both its authentication filter and SDK session identity resolve the workspace from the durable key row, including OAuth backing keys. Browser state and caller headers never select the machine workspace. A session is bound to both the key and its workspace; a different key cannot reuse it, even when held by the same account.

## Alternatives and consequences

A session-wide current workspace would let one browser tab redirect another tab's pending write. Default-space fallback would conceal missing routing and prevent an account belonging only to another space from connecting. Explicit browser routes and credential-derived machine scope preserve the existing service-level `WorkspaceId` contract without introducing either behavior.

The browser URL contains workspace identity and navigation only, never repository credentials. Authorization remains necessary after parsing a valid identifier. Public browsing still uses the default site's existing routes; cross-space discovery, public-delivery controls and the member permission matrix remain owned by the [multi-user proposal](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md).

## Verification

PostgreSQL and real HTTP integration cover membership isolation, body protection, scoped media/export entrances, OAuth and MCP. A two-space test concurrently saves the same path through one browser account into two distinct Git authorities and independently reads back both remote objects. Another account sees only its own membership and receives denial before entering foreign-space operations. The Streamable HTTP test connects an account without default-space membership and rejects cross-key session reuse.

Frontend tests cover invitation success, OAuth selection, repository operations and retrying a durable creation request without storing its provider token. The isolated browser entrance can seed a second independently backed space for interactive evidence; it does not exercise provider-side repository creation.
