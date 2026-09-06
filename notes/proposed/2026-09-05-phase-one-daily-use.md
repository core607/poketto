# Phase One: Daily-use Blog and Repository MCP

Date: 2026-09-05
Status: Proposed

## Problem

The executable baseline serves canonical documents through a read-only public API. The accepted publishing, frontend, membership, asset, and execution proposals describe a broader product, but their combined first delivery needs an explicit boundary and verifiable completion criteria. A daily-use installation needs working browser and agent authoring, not only independently passing infrastructure tests.

## Delivery boundary

Deliver a single-host installation with a blog, authenticated administration, and repository MCP access from Codex and Claude Code. Remote `main` remains authoritative for content. The operator supplies an existing private content repository without moving Markdown into `documents/`, adding mandatory frontmatter, or reformatting untouched files. Repository coordinates, host addresses, domains, credentials, and machine-specific operating records remain private operator configuration.

The public interface includes an article stream, article and folder pages, tags, archive, bounded search, RSS, and sitemap. Administration includes a file tree, Markdown editing and preview, image selection and upload, invitations, memberships, and API keys. Interface text is Simplified Chinese; interface language switching is outside this delivery.

Backups, restore drills, visitor Q&A, consumer registration and personal-workspace provisioning, serverless deployment, OAuth, rich-text editing, image transformations, and persistent content indexes are excluded. Backup proposals remain future work and are not implementation or deployment prerequisites. All acknowledged managed originals are retained; only temporary uploads, derived caches, and execution directories may be cleaned up.

## Repository contracts

### Discovery and routes

Discover regular UTF-8 Markdown at arbitrary repository-relative paths. Optional metadata takes precedence; otherwise the first heading or filename supplies the title and Git history supplies dates. Keep exact source text and service-issued blob revisions available to authorized editors and agents. Do not canonicalize untouched fields or files.

An eligible `index.md` owns its folder route. Its gallery contains eligible sibling images only, sorted by filename, with images already referenced in the body omitted. Other articles have no implicit gallery. Route collisions, malformed metadata, invalid UTF-8, unsafe paths, and individual file-limit violations produce administrator diagnostics and exclude affected files from structured results without hiding unrelated valid documents. Symlinks, submodules, repository internals, and non-regular files never become public content.

### Publication and freshness

Publication requires a valid, explicitly enabled `.poketto/publishing.yaml` using `public-by-default`. Missing or disabled policy exposes no content. An invalid policy fails public service closed; it cannot retain an earlier public policy. Root `private/` and configured exclusions always win over article references and galleries. These paths remain readable by authorized members and AI keys with `READ_PRIVATE`.

The initial YAML schema has `enabled` (boolean), `mode` (`public-by-default`), and optional `exclude` (a list of repository-relative globs). Reject unknown, duplicate, or YAML merge keys, malformed UTF-8, multiple YAML documents, collection aliases, policies over 16 KiB, more than 64 exclusions, and patterns over the repository path bound. `*` and `?` match within one segment; a whole `**` segment matches zero or more segments. Matching is case-sensitive. Directory exclusions use a trailing `/**`; absolute paths, traversal, backslashes, character classes, and brace expansion are not accepted.

Readers use one resolved commit. A successful remote refresh renews snapshot verification; an acknowledged application write immediately updates the serviceable snapshot. Direct Git pushes become visible through scheduled refresh. A temporarily unreachable remote permits the last verified snapshot only until `poketto.repository.stale-after-seconds`, default 3600. At expiry, public content service and new image grants stop and readiness becomes unavailable. Process restart must not renew the last verification time. Failure to validate policy is distinct from remote transport failure.

Public search fixes its scope internally. Private search authorizes the workspace before scanning. Both perform bounded literal text matching with tag and date filters against a resolved tree; diagnostics and errors never expose private paths to public callers. PostgreSQL stores relational application state, not a content projection or search index. Remove the empty `projection` and `search` modules, zhparser, and their test and deployment dependencies.

Git transport fetches history objects and reuses them incrementally; repository images materialize lazily from those objects. Partial clone is excluded. Cold startup may transfer historical and image objects beyond the requested file. Measure initial fetch, retained history, derived cache storage, and scan costs instead of claiming selective cold transfer.

### Atomic text writes

Browser editing and `repo_patch` call the same workspace-authorized service. Each bounded UTF-8 batch names a base commit and a service-issued revision or expected absence for every affected path. Creation, update, move, and deletion are atomic. A move checks both source and destination. The remote ref advances only from the expected base; concurrent changes return a conflict without overwriting them. A lost response is reconciled against remote authority, never blindly retried or reported as an unverified success.

`WRITE_PRIVATE` alone permits changes only to paths private or excluded both before and after the patch. Changes to existing or newly public content, public references, gallery reachability, or publication policy also require `PUBLISH`. Binary repository mutation is outside this text bridge. Upload acknowledgement and document-save acknowledgement remain separate.

## Identity and assets

Spring Security owns one-time owner initialization protected by a separate initialization credential, adaptive password hashing, server-side sessions, logout, CSRF, and login throttling. Initialization cannot reopen after success. Invitations are single-use, workspace-bound, expiring member invitations. Suspension blocks new requests and revokes affected keys. Concurrent membership changes cannot remove the last active owner.

Workspace API keys store verification digests and reveal the full token only on creation. Human sessions, API keys, and system principals have separate attribution. AI keys lack `PUBLISH`, `MANAGE_KEYS`, and `EXECUTE_REPOSITORY` by default; the owner explicitly grants these capabilities. Revocation and suspension also terminate active executions. Authorization remains a business boundary shared by browser and MCP entrances.

The asset module owns a local authoritative `ManagedBlobStore`, bounded idempotent uploads, immutable references, and a disposable read-only Git-image cache. Acknowledged originals survive cache cleanup and application restart. Uploads do not mutate Git or publish an image. Image validation checks signature, type, bytes, path containment, and cumulative response bounds; production limits must accommodate the designated corpus without permitting unbounded allocation.

Public image grants are opaque and bind workspace, page commit, and exact Git blob or managed revision. Their lifetime is at most five minutes and never exceeds the content snapshot expiry. Image delivery validates that grant and exact bytes; it does not reinterpret an old page against a newer tree. Withdrawal stops new grants as soon as the new snapshot is installed; issued grants expire within their bound. Private preview checks current identity on every request. Public page and image caches cannot outlive the authorization they contain.

## Frontend and MCP

Next.js owns presentation and consumes Spring contracts; it never reads repositories, blob stores, or PostgreSQL directly. Spring owns authorization, mutations, asset resolution, and business state. Public pages and editor preview share restricted Markdown rendering: raw HTML disabled, safe URLs and media, and CSP. Public initial HTML remains readable with JavaScript disabled. Mutable public responses have no uncoordinated cross-request Next.js cache.

Use the Spring AI 2.0.1 WebMVC Streamable HTTP server at `/mcp`, authenticated with Bearer API keys independently of browser sessions. Origin validation remains explicit. The initial tool set is:

| Tool | Contract |
|---|---|
| `get_file` | Exact authoritative UTF-8 source, commit, service-issued revision, diagnostics, and explicit expected absence for a missing path |
| `get_asset` | Authorized exact Git or managed image version as bounded MCP image content |
| `put_asset` | Bounded idempotent upload returning an immutable reference; no Git write or publication |
| `repo_patch` | Atomic revision- and base-checked UTF-8 batch with explicit conflict and indeterminate outcomes |
| `repo_exec` | Git, text search, shell, and Python in a session-specific isolated copy; commit, exit status, output, truncation, and timeout results |

Execution sessions are keyed by principal, workspace, and server-issued MCP session. Two clients using the same key cannot share a directory. An omitted commit on later execution keeps the session's pinned commit. A successful patch returns a new commit without changing an existing execution session. `get_file(commit)` always reads authoritative objects, never command-modified execution files.

## Execution boundary

Pin SRT and run it as a dedicated low-privilege identity behind a permissioned local socket. Spring supplies a server-issued snapshot lease resolved from authorized workspace and commit. Caller arguments cannot select host paths, repository coordinates, cache roots, or clone sources.

Execution copies have no shared source-object inodes, alternates, or credentials. Commands cannot access application caches, managed originals, other workspaces, host profiles, service sockets, or undeclared filesystem paths. Network access, including proxy routes, is denied. Time, CPU, memory, descendant count, storage, and output limits are enforced outside the command. Cancellation, revocation, expiry, failure, and restart terminate descendants and clean abandoned execution state without following caller-controlled paths.

Resource and lease parameters are centralized and receive production values only after real-host tests. Sandbox setup failure never falls back to an ordinary subprocess. If the selected topology cannot meet isolation, retain this acceptance gap and record a new runtime decision before substituting another execution design.

## Delivery sequence and evidence

| Stage | Required result |
|---|---|
| 0 | This scope record, reconciled proposal links, working test environment, and an early real-Linux SRT isolation/resource/cleanup spike using synthetic data |
| 1 | Arbitrary-path exact-commit reads, optional metadata, diagnostics, publication policy, bounded search, and atomic text writes with real-ref conflict behavior |
| 2 | Identity lifecycle with PostgreSQL tests; managed uploads and Git-image delivery with real storage and permission tests |
| 3 | Blog/admin and five MCP tools over shared business contracts; pinned frontend dependencies and lockfile-based CI production build |
| 4 | Prebuilt frontend, Spring, PostgreSQL, and executor integrated with Caddy same-origin HTTPS; health, failed deployment retry, fixed-version redeployment, and resource evidence |
| 5 | The configured real content repository on a formal HTTPS domain; browser, Codex, and Claude Code workflows; final reviewed commit equals deployed revision |

Independent work may proceed while a stage has a remaining gap, but that gap cannot be marked passed. The sandbox spike precedes dependent execution implementation. A missing domain or operator authorization does not prevent isolated development, but prevents final live acceptance.

Use bounded parallel implementation with independent review of critical contracts. Each completed slice records its commit, checks, actual results, and remaining gaps. Short branches and isolated worktrees deliver reviewable PRs. Inspect actual bot comments, fix valid findings, and recheck CI and review against the latest head. Merge and formal deployment require their corresponding operator authorization after the result is concrete and reviewable.

## Acceptance

- The existing corpus works without directory moves or mandatory frontmatter; Chinese paths, malformed files, route collisions, and large images have deterministic outcomes.
- Public pages, galleries, search, RSS, sitemap, stale URLs, and errors cannot expose private or excluded content. Authorized private reads work.
- Browser and MCP creation, update, move, and deletion share atomic revision checks. Concurrent edits conflict; remote outages and lost replies do not fabricate success.
- Pages retain exact authorized image versions across commits until grant expiry. Withdrawal prevents new grants; expired grants fail. Deleting derived caches rebuilds them without deleting originals.
- Owner initialization and invitations cannot be reused. Concurrent owner removal is protected. Key revocation and suspension deny new requests and terminate active executions.
- Both real Codex and Claude Code clients connect, discover tools, inspect directories and history, read private text and images, upload, write with revisions, and handle conflicts. Protocol probes alone do not satisfy client acceptance.
- Real sandbox tests deny sensitive host paths, another workspace, direct network and proxy access; source objects remain unchanged. Timeout, resource exhaustion, cancellation, service restart, and abandoned sessions clean up correctly. Measure initial copy plus twenty reused executions and deployed-process resource peaks.
- A real browser verifies JavaScript-disabled public reading, editor preview, conflicts, and mobile layouts. Screenshots or recordings correspond to the exact delivered tree.
- Focused tests cover each slice. Final Gradle `check` covers PostgreSQL integration, module boundaries, deployment scripts, frontend checks, and production build; `repoCheck`, generated-file checks, and `git diff --check` pass. Missing required infrastructure is repaired or reported as incomplete, never replaced by a claimed manual pass.
- Final delivery requires all functionality and workflows on the authorized host under valid HTTPS with no unresolved valid blocking review findings. Waiting for a domain, authorization, or environment repair does not complete the goal.

## Related decisions and alternatives

The scoped same-topic audit retains these records; none is archived or rejected by this delivery boundary:

| Record | Relationship |
|---|---|
| [Remote repository authority](../implemented/2026-09-01-remote-repository-authority.md) | Retain exact-ref authority, conflict, and lost-response semantics |
| [Validated content snapshot](../implemented/2026-09-04-validated-content-snapshot.md) | Retain snapshot and resource ownership; this proposal replaces whole-tree document rejection and indefinite stale public service |
| [Repository publishing](2026-09-01-repository-native-publishing-and-assets.md) | Retain discovery, policy, gallery, and patch contracts; bound delivery grants explicitly here |
| [Repository retrieval and execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) | Retain five-tool direction and isolation; exclude Q&A and selective cold transfer from this delivery |
| [Asset storage](2026-09-01-repository-asset-blob-store.md) | Deliver local storage and Git-image cache; retain OSS and physical reclamation as unimplemented scope |
| [Invitation-only membership](2026-08-27-invitation-only-membership.md) | Deliver self-hosted identity lifecycle and member administration |
| [Next.js frontend](2026-08-30-nextjs-frontend.md) | Deliver presentation boundary and runtime evidence; omit Q&A controls |
| [Continuous delivery](../implemented/2026-09-03-continuous-delivery.md) | Retain immutable artifacts and deployment verification; this delivery does not require a backup gate |
| [Off-host backup](2026-08-27-off-host-backup-and-restore.md) and [source-encrypted recovery](2026-09-01-source-encrypted-backup-recovery.md) | Retain as future work, excluded from phase-one completion |
| [Consumer workspaces](2026-09-01-consumer-accounts-and-personal-workspaces.md) and [serverless](2026-09-01-optional-serverless-deployment-profile.md) | Retain independent future profiles |

Whole-commit rejection preserves an all-valid document set but lets a malformed private file hide unrelated articles. Per-file diagnostics preserve the actual commit while identifying precisely which structured results are unavailable. Invalid publication policy still closes the entire public surface because its authorization decision cannot be reconstructed safely.

Indefinite stale service improves availability but cannot bound the age of a public authorization decision. Bounded snapshot service and short grants make that exposure explicit. Rechecking every image against latest `main` instead would break the consistency of already-rendered pages.

Mandatory content conversion would simplify metadata parsing but make an existing repository depend on Poketto's format. Optional metadata and byte-preserving edits keep Git independently usable. Partial clone could reduce cold transfer but adds transport and missing-object behavior; first measure ordinary history fetch and incremental reuse.

Requiring every adjacent proposal before first use would add backups, remote stores, and visitor AI to the critical path. The narrower boundary delivers daily reading and authoring while retaining acknowledged originals and accurately marking unfinished proposal scope.

## Risks

Repository scans have linear cost and weaker ranking than a persistent index. Bound allocations and response sizes, measure the real corpus, and require evidence before adding an index. Same-kernel sandboxing is an explicit dependency whose limitations must be tested on the deployed topology. Browser, backend, and executor increase operational complexity; prebuilt artifacts and full-stack resource measurements are required. Without backup work, phase one supplies no tested off-host recovery for authoritative relational state or managed originals.
