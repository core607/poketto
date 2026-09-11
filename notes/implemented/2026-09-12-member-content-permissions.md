# Member Content Permissions

Date: 2026-09-12

## Decision

Workspace membership permits reading the current public scope. It does not imply private access or permission to change content. Owners assign `READ_PRIVATE`, `WRITE_PRIVATE` and `PUBLISH` independently, except that private writing requires private reading. Workspace invitations carry these initial grants and default to none. Owners retain all capabilities and exclusive membership and static-key administration. Site administration remains separate from workspace ownership.

This implements the membership portion of the [multi-user proposal](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md). It replaces implicit ordinary-member private access in the [identity HTTP decision](2026-09-06-workspace-identity-http.md) while retaining its invitation digests, last-owner invariant and workspace locking. Installing the permission schema gives existing ordinary members empty content grants and revokes their keys with private, publication or management authority. Owners must explicitly grant any continuing private access.

## Content authorization

The repository publication policy determines public scope, including exclusions and reserved files. Members can read this scope even when the workspace's anonymous website is disabled. Without `READ_PRIVATE`, repository reads select current authoritative content only and filter paths before pagination. They cannot select historical objects or read excluded files. `RepositoryFile.publicScope` reports the policy decision used by browser editing; the path prefix alone is insufficient.

The workspace catalog's `public_delivery` flag controls anonymous website delivery. It is distinct from `.poketto/publishing.yaml`, which defines eligible content. Disabled, absent or invalid repository policy grants no public scope; ignoring that policy would expose content its owner has withdrawn. The member read path does not depend on the catalog website flag.

Public changes require `PUBLISH`; private or excluded changes require `WRITE_PRIVATE`. Moves additionally require private reading for private source, destination or repaired references. The atomic writer computes requirements from every affected path and holds workspace authorization through the write. Publishing-policy changes and indirect publication effects retain their publication checks. A public-only move plan cannot return a private media index. Failed authorization cannot advance the remote branch.

Authenticated public-scope image previews remain bound to their member and workspace. An edited preview cannot authorize a managed original absent from current public content. Image delivery rechecks current source and membership. Original downloads verify the current repository media snapshot, immutable version and membership; transfer rechecks happen every 256 KiB without fetching Git for each block. Snapshot withdrawal or revoked authority stops an outstanding transfer. Anonymous grants and private-read grants are separate: neither substitutes for member public authority, and downgrading a member does not make an old private grant public.

The public editor's image picker lists eligible Git images and indexed managed images without private-read permission. Filtering precedes validation diagnostics, totals and pagination. It inserts relative paths and cannot list arbitrary private uploads or upload new originals without private-write authority. Requested history and a changed current commit are rejected.

## Machine delegation

Current membership capabilities intersect the connection's stored grants on every operation. Increasing membership privileges cannot enlarge an existing key. Narrowing privileges revokes over-scoped credentials and emits revocation events that terminate affected MCP and execution sessions; a narrower connection remains valid. A revoked connection is not restored by a later permission increase.

Members may approve [OAuth connections](2026-09-11-mcp-oauth.md) for themselves within their current grants, list those connections and disconnect them. Owners can manage all workspace connections. OAuth does not delegate key management or permit members to issue static keys. Consent fixes the workspace and resets selected permissions when switching spaces.

The [CodeAct execution boundary](2026-09-09-codeact-content-and-media.md) still distinguishes full-source sessions from sanitized public projections. Full-source sessions require private reading and execution; their selected saves and moves derive write requirements from the actual changes. A connection with private reading and publication can save public files without private writing. Public projection sessions remain read-only, including when a connection has publication permission: writing their sanitized text back would discard author metadata and comments. Public-scope machine authoring without private reading is not implemented by this record.

## Alternatives and consequences

Role-wide private access cannot represent a member invited only to read public content. A single write capability would force public editors to receive private write authority. UI-only controls would leave API, media and machine entrances available. Explicit grants and path-level service checks avoid those failures while keeping repository policy as the content-scope authority.

Requiring private reading for full-source execution retains its existing metadata and history boundary. The browser can edit current public source with publication permission alone; a sanitized execution projection cannot provide equivalent source-preserving authoring. The broader proposal remains proposed, including that machine authoring gap, public website controls, discovery and remaining administration interaction.

## Verification

PostgreSQL tests cover grant validation, invitation defaults, current membership intersection, non-expansion, OAuth self-delegation and credential revocation. Real HTTP tests use separate Git repositories to exercise filtered reads, independent public/private writes and moves with public and private reference repair. MCP protocol tests verify over-scoped session revocation while retaining a narrower connection.

Native Linux storage tests cover member previews, immutable originals, stale versions, publication withdrawal and transfer revocation. Socket tests exercise the host execution boundary; frontend tests cover permission selection, OAuth workspace changes and editor controls. These checks do not replace final-tree browser evidence or an actual external MCP client exchange.
