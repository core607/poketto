# Phase One: Daily-use Blog and Repository MCP

Date: 2026-09-05

The [CodeAct MCP entrance](2026-09-10-codeact-mcp-entrance.md) supersedes
standalone agent file-read, list and patch tool selections in this record. Shared
service contracts and outstanding delivery requirements remain applicable.
The [MCP OAuth extension](2026-09-11-mcp-oauth.md) adds owner-approved client connections. The [multi-user delivery](2026-09-11-multiuser-workspaces-and-discovery.md) adds multiple spaces and connecting existing repositories. [Consumer identity](2026-09-20-consumer-identity-and-site-policy.md) replaces its invitation-gated registration with verified email and Google login, and [GitHub-authorized personal spaces](2026-09-21-github-authorized-personal-spaces.md) add explicit repository creation; automatic creation during registration remains excluded. [Account working copies](2026-09-14-account-working-copies.md) owns durable disk storage, shared account/workspace identity and transport-independent execution.

Status: Implemented

## Delivered components

[CodeAct content and media](2026-09-09-codeact-content-and-media.md) own the implemented repository-agent contract: workspace-scoped local media, public/private roots, isolated execution and host-mediated persistence.

The [identity HTTP backend](2026-09-06-workspace-identity-http.md) delivers the self-hosted account, session, invitation, membership and key foundation.

The [repository authoring foundations](2026-09-05-repository-authoring-foundations.md) add repository-native content, publication policy, atomic patches and snapshot-bound images. The [MCP and local execution integration](2026-09-05-local-execution-supervisor.md) supplies the tool transport and signed worker adapter. The [blog and browser administration](2026-09-06-blog-browser-interface.md) provides public pages and the editor.

## Problem

The executable baseline serves canonical documents through a read-only public API. The accepted publishing, frontend, membership, asset, and execution proposals describe a broader product, but their combined first delivery needs an explicit boundary and verifiable completion criteria. A daily-use installation needs working browser and agent authoring, not only independently passing infrastructure tests.

## Delivery boundary

The single-host installation provides a blog, authenticated administration, and repository MCP access verified through currently callable clients and real service integration. Remote `main` remains authoritative for content. The operator supplies an existing private content repository without moving Markdown into `documents/`, adding mandatory frontmatter, or reformatting untouched files. Repository coordinates, host addresses, credentials, and machine-specific operating records remain private operator configuration. The public domain is operator configuration as well; the README links the author's own instance.

The public interface includes an article stream, article and folder pages, tags, archive, bounded search, RSS, and sitemap. Administration includes a file tree, Markdown editing and preview, image selection and upload, invitations, memberships, and API keys. Interface text is Simplified Chinese; interface language switching is outside this delivery.

Backups, restore drills, visitor Q&A, open registration, automatic provider-side repository creation, serverless deployment, rich-text editing, general image transformations, and persistent content indexes are excluded. The multi-user scope includes accounts, whose registration [consumer identity](2026-09-20-consumer-identity-and-site-policy.md) now owns, connecting existing repositories and album thumbnails. Backup proposals remain future work and are not implementation or deployment prerequisites. All acknowledged managed originals are retained; temporary uploads, derived caches, and execution copies may be cleaned up only under their owning lifecycle rules.

## Repository contracts

### Discovery and routes

Discover regular UTF-8 Markdown at arbitrary repository-relative paths. Optional metadata takes precedence; otherwise the first heading or filename supplies the title and Git history supplies dates. Keep exact source text and service-issued blob revisions available to authorized editors and agents. Do not canonicalize untouched fields or files.

An eligible `index.md` owns its folder route. Its gallery contains eligible sibling images only, sorted by filename, with images already referenced in the body omitted. Other articles have no implicit gallery. Route collisions, malformed metadata, invalid UTF-8, unsafe paths, and individual file-limit violations produce administrator diagnostics and exclude affected files from structured results without hiding unrelated valid documents. Symlinks, submodules, repository internals, and non-regular files never become public content.

### Publication and freshness

Publication requires a valid, explicitly enabled `.poketto/publishing.yaml` and the owner's independent website switch. The [CodeAct content contract](2026-09-09-codeact-content-and-media.md) owns the publication mode and public/private roots. Missing or disabled policy exposes no content. An invalid policy fails public service closed; it cannot retain an earlier public policy. Root `private/` and configured exclusions always win over article references and galleries. These paths remain readable by authorized members and AI keys with `READ_PRIVATE`.

The YAML schema has `enabled` (boolean), `mode` (`public-root`), and optional `exclude` (a list of repository-relative globs). Reject unknown, duplicate, or YAML merge keys, malformed UTF-8, multiple YAML documents, collection aliases, policies over 16 KiB, more than 64 exclusions, and patterns over the repository path bound. `*` and `?` match within one segment; a whole `**` segment matches zero or more segments. Matching is case-sensitive. Directory exclusions use a trailing `/**`; absolute paths, traversal, backslashes, character classes, and brace expansion are not accepted.

Readers use one resolved commit. A successful remote refresh renews snapshot verification; an acknowledged application write immediately updates the serviceable snapshot. Direct Git pushes become visible through scheduled refresh. A temporarily unreachable remote permits the last verified snapshot only until `poketto.repository.stale-after-seconds`, default 3600. At expiry, public content service and new image grants stop and readiness becomes unavailable. Process restart must not renew the last verification time. Failure to validate policy is distinct from remote transport failure.

Public search fixes its scope internally. Private search authorizes the workspace before scanning. Both perform bounded literal text matching with tag and date filters against a resolved tree; diagnostics and errors never expose private paths to public callers. PostgreSQL stores relational application state, not a content projection or search index; [stock PostgreSQL](2026-09-05-stock-postgresql.md) removed the empty `projection` and `search` modules and zhparser.

Git transport fetches history objects and reuses them incrementally; repository images materialize lazily from those objects. Partial clone is excluded. Cold startup may transfer historical and image objects beyond the requested file. Measure initial fetch, retained history, derived cache storage, and scan costs instead of claiming selective cold transfer.

### Atomic text writes

Browser editing and host-mediated CodeAct saves call the same workspace-authorized service. Each bounded UTF-8 batch names a base commit and a service-issued revision or expected absence for every affected path. Creation, update, move, and deletion are atomic. A move checks both source and destination. The remote ref advances only from the expected base; concurrent changes return a conflict without overwriting them. A lost response is reconciled against remote authority, never blindly retried or reported as an unverified success.

`WRITE_PRIVATE` alone permits changes only to paths private or excluded both before and after the patch. Changes to existing or newly public content, public references, gallery reachability, or publication policy also require `PUBLISH`. Binary repository mutation is outside this text bridge. Upload acknowledgement and document-save acknowledgement remain separate.

## Identity and assets

The interactive deployment command initializes the first site administrator and space owner with hidden password input and a durable one-time guard; no anonymous initialization endpoint remains. Spring Security owns adaptive password hashing, server-side sessions, logout, CSRF, and login throttling. Workspace membership invitations are single-use, expiring credentials and never create an account. Suspension blocks new requests and revokes affected keys. Concurrent membership changes cannot remove the last active owner.

Workspace API keys store verification digests and reveal the full token only on creation. Human sessions, API keys, and system principals have separate attribution. AI keys lack `PUBLISH`, `MANAGE_KEYS`, and `EXECUTE_REPOSITORY` by default; the owner explicitly grants these capabilities. Revocation and suspension also terminate active executions. Authorization remains a business boundary shared by browser and MCP entrances.

The asset module owns a local authoritative `ManagedBlobStore`, bounded idempotent uploads, immutable references, and a disposable read-only Git-image cache. Acknowledged originals survive cache cleanup and application restart. Uploads do not mutate Git or publish an image. Image validation checks signature, type, bytes, path containment, and cumulative response bounds; production limits must accommodate the designated corpus without permitting unbounded allocation.

Public image grants are opaque and bind workspace, page commit, and exact Git blob or managed revision. Their lifetime is at most five minutes and never exceeds the content snapshot expiry. Image delivery validates that grant and exact bytes; it does not reinterpret an old page against a newer tree. Under the [website delivery boundary](2026-09-14-workspace-public-delivery.md), every replay also requires the currently approved page commit and path, so website withdrawal or a snapshot replacement invalidates issued grants before they expire. Private preview checks current identity on every request. Public page and image caches cannot outlive the authorization they contain.

## Frontend and MCP

[Directory navigation](2026-09-08-repository-directory-navigation.md) extends the basic read surface with the installation and client evidence recorded for this delivery.

Next.js owns presentation and consumes Spring contracts; it never reads repositories, blob stores, or PostgreSQL directly. Spring owns authorization, mutations, asset resolution, and business state. Public pages and editor preview share restricted Markdown rendering: raw HTML disabled, safe URLs and media, and CSP. Public initial HTML remains readable with JavaScript disabled. Mutable public responses have no uncoordinated cross-request Next.js cache.

Use the Spring AI 2.0.1 WebMVC Streamable HTTP server at `/mcp`, authenticated with workspace Bearer API keys or OAuth access tokens independently of browser sessions. Origin validation remains explicit. The executor-enabled tool set is:

| Tool | Contract |
|---|---|
| `get_asset` | Authorized exact Git or managed image version as bounded MCP image content |
| `put_asset` | Bounded idempotent upload returning an immutable reference; no Git write or publication |
| `repo_exec` | Git, text search, shell, Python and host-mediated CodeAct operations in an authorized account/workspace copy; explicit identity, retention, exit, output and uncertain-outcome results |
| `repo_discard` | Explicit owner-authorized disposal by copy ID; never reverses remote saves |
| `get_artifact` | Bounded access to an artifact created by an authorized execution lease |

Default copies are keyed by Poketto account, workspace and reading scope. Authorized clients share the same copy; a restricted public projection cannot expose a full copy. Commands serialize, while exact-text edits and final save conflicts retain operation-level protections. An omitted commit uses the current copy. Confirmed saves advance its local Git baseline while retaining host-owned per-file write preconditions; [Mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md) own pending local installation and its recovery. Authoritative reads never use command-modified execution files. MCP disconnects do not remove work; the account-copy contract owns disk quotas, seven-day idle expiry, reattachment, interrupted commands and explicit disposal.

## Execution boundary

Pin SRT and run it as a dedicated low-privilege identity behind a permissioned local socket. Spring supplies a server-issued snapshot lease resolved from authorized workspace and commit. Caller arguments cannot select host paths, repository coordinates, cache roots, or clone sources.

Execution copies have no shared source-object inodes, alternates, or credentials. Commands cannot access application caches, managed originals, other workspaces, host profiles, service sockets, or undeclared filesystem paths. Network access, including proxy routes, is denied. Time, CPU, memory, descendant count, storage, and output limits are enforced outside the command. Cancellation, revocation, expiry, failure, and restart terminate descendants and clean abandoned execution state without following caller-controlled paths.

Resource and lease parameters are centralized and receive production values only after real-host tests. Sandbox setup failure never falls back to an ordinary subprocess. If the selected topology cannot meet isolation, retain this acceptance gap and record a new runtime decision before substituting another execution design.

## Acceptance

The delivery was accepted on the authorized HTTPS installation against the real content repository; its evidence lives under [acceptance/](../../acceptance/README.md). These evidence rules still apply to later deliveries:

- A remaining gap is never marked passed. Missing required infrastructure is repaired or reported as incomplete, never replaced by a claimed manual pass.
- Protocol probes and synthetic fixtures do not replace real browser, currently callable client, and HTTPS evidence. Real authenticated HTTP and native-worker integration covers service boundaries that the available connector cannot expose.
- An external client that the operator cannot access is not a completion condition and is never reported as tested.

## Related decisions and alternatives

Related: [remote repository authority](2026-09-01-remote-repository-authority.md) keeps exact-ref conflict and lost-response semantics; the [repository authoring foundations](2026-09-05-repository-authoring-foundations.md) own the public content snapshot, which replaces whole-tree rejection and indefinite stale service; [continuous delivery](2026-09-03-continuous-delivery.md) keeps immutable artifacts without a backup gate; remote object storage and physical reclamation of managed originals stay unimplemented; [off-host backup](../proposed/2026-08-27-off-host-backup-and-restore.md) remains a proposal, and the [serverless profile](../rejected/2026-09-01-optional-serverless-deployment-profile.md) is rejected.

Whole-commit rejection preserves an all-valid document set but lets a malformed private file hide unrelated articles. Per-file diagnostics preserve the actual commit while identifying precisely which structured results are unavailable. Invalid publication policy still closes the entire public surface because its authorization decision cannot be reconstructed safely.

Indefinite stale service improves availability but cannot bound the age of a public authorization decision. Bounded snapshot service and short grants make that exposure explicit. Rechecking every image against latest `main` instead would break the consistency of already-rendered pages.

Mandatory content conversion would simplify metadata parsing but make an existing repository depend on Poketto's format. Optional metadata and byte-preserving edits keep Git independently usable. Partial clone could reduce cold transfer but adds transport and missing-object behavior; first measure ordinary history fetch and incremental reuse.

Requiring every adjacent proposal before first use would add backups, remote stores, and visitor AI to the critical path. The narrower boundary delivers daily reading and authoring while retaining acknowledged originals and accurately marking unfinished proposal scope.

## Risks

Repository scans have linear cost and weaker ranking than a persistent index. Bound allocations and response sizes, measure the real corpus, and require evidence before adding an index. Same-kernel sandboxing is an explicit dependency whose limitations must be tested on the deployed topology. Browser, backend, and executor increase operational complexity; prebuilt artifacts and full-stack resource measurements are required. Without backup work, phase one supplies no tested off-host recovery for authoritative relational state or managed originals.
