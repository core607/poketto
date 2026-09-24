# Multi-user Workspaces and Public Discovery

Date: 2026-09-11

## Problem

The original account registration, workspace membership, and installation shared a browser entrance. Browser and MCP requests resolve the default workspace even though relational identity and storage already carry workspace identifiers. Public navigation treats directory indexes as ordinary articles, emits Markdown fragments as summaries, and loses collection and search context.

This delivery extends [workspace identity](2026-09-06-workspace-identity-http.md), [remote repository authority](2026-09-01-remote-repository-authority.md), and [MCP OAuth](2026-09-11-mcp-oauth.md). It preserves their operation-level authorization and remote Git authority. It replaces the automatic personal-repository provisioning choice in [consumer accounts](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md). The invitation, frontend, and phase-one records retain their independently applicable requirements; this record owns the multi-user behavior and discovery changes described below.

## Accounts and installation

An operator runs an interactive deployment command to create the first site administrator and default-space owner ([operator administrator setup](2026-09-11-operator-administrator-setup.md)). There is no anonymous browser initialization endpoint.

[Consumer identity and site policy](2026-09-20-consumer-identity-and-site-policy.md) owns registration and account groups. A new account is a `VIEWER` with no membership and no automatic personal workspace; only `CREATOR` and `ADMINISTRATOR` accounts create spaces. An account without spaces can log in, manage its login methods, and accept a workspace invitation.

A workspace invitation specifies its workspace and initial member permissions, is single-use, expires after seven days, and is stored only as a digest. It cannot register an account: an unauthenticated recipient signs in or registers first, then accepts. Site administration is distinct from workspace ownership.

## Workspace lifecycle and access

The account-level space list supports creation, joining, and switching. Manual creation asks for a display name, public slug, and an existing GitHub or CNB HTTPS repository with credentials; [GitHub-authorized personal spaces](2026-09-21-github-authorized-personal-spaces.md) add creation of a private repository in the user's own GitHub account, the only path on which Poketto creates a provider repository. [Repository initialization on connection](2026-09-16-repository-initialization-on-connection.md) commits the content template into an empty repository once the space exists and, for a repository with content, offers the template's absent files from the space's repository connection view. The creator receives the owner membership. Creation is durable and idempotent, records failure stages, and resumes without duplicate spaces or destructive writes to existing content. One remote repository cannot be bound to two spaces.

Credentials are encrypted using a deployment-provided key, scoped by workspace, excluded from response bodies and logs, and rotatable only by the space owner. Repository validation rejects unsupported origins, credentials in URLs, unsafe redirect targets, and private-network destinations. An established workspace cannot be rebound to another repository through this delivery's UI.

A new workspace starts with public delivery disabled, including when its repository already contains `public/`. The owner must enable public delivery explicitly. Public delivery then exposes only the repository's public scope; `private/` and other excluded files never enter public discovery. Registered membership is required to use the private administration surface. Members can read the public scope within their space even while its public website is disabled; private files additionally require private-read permission.

Owners list members and assign private read, private write, and public-content write/publish permissions separately ([member content permissions](2026-09-12-member-content-permissions.md)). Private write requires private read. Ordinary invitations default to none of these capabilities. Owners alone administer membership, repository credentials, and the space's publication switch. The last-owner invariant remains.

Effective machine access is the intersection of the connection's explicit grants and its holder's current permissions. Reducing membership permissions revokes over-scoped connections and terminates affected execution sessions. Increasing permissions never expands existing connection grants. All storage, media, histories, exports, caches, jobs, and authorization checks remain workspace-scoped.

Account identity is available independently of workspace access. Private HTTP operations explicitly select the workspace in their route and reauthorize each request. A browser-wide mutable current-space value must not decide where an edit is saved: separate tabs can safely edit different spaces. OAuth consent selects one accessible workspace and fixes it into the issued connection; the existing `/mcp` resource address remains, with workspace authority derived from the credential rather than the browser's selection.

## Public discovery and reading

The root site is cross-workspace public discovery. Space websites use `/s/{slug}`; published default-space article URLs redirect to their canonical routes. Both logged-in and anonymous visitors see public discovery; accounts additionally have a My spaces entrance. Cards identify their author display name, space, and collection without exposing private account fields.

Discovery mixes public article, album, and collection cards; it does not turn every raw media file into a post. [Discovery batches](2026-09-14-public-discovery-batches.md) keep random order stable within a browsing batch, including pagination and return navigation, and use bounded verified public data rather than a repository fetch per request. Publication withdrawal overrides old batches. [Album entrances](2026-09-14-album-entrances-and-lightbox.md) and [collection reading](2026-09-14-collection-reading.md) own folder landings, thumbnails and sequential reading; album and collection names are content-derived, never hard-coded.

Summaries come from parsed visible Markdown text before truncation. Link labels remain without URL syntax; duplicate opening titles are omitted from summaries. Reading-text extraction is shared by public and authorized management search. CommonMark nodes and the table, strikethrough, task-list and footnote extensions retain authored prose, code and image descriptions without interpreting link destinations as text. Only reachable footnote definitions participate, in reference order. Text normalization and summary generation are transient reads; they never rewrite repository content.

Site search covers enabled public spaces; space search fixes one space. Authenticated management search fixes both a space and the caller's current authorization. Search matches literal visible text, without semantic search. [Site search](2026-09-14-public-site-search.md) and [search highlights and reading return](2026-09-14-search-highlights-and-reading-return.md) own result identity, escaped highlighting, URL state and return navigation; raw or cross-origin return URLs are not trusted navigation targets. The [public sitemap contract](2026-09-14-public-sitemaps.md) enumerates every canonical space URL.

Disabling public delivery or withdrawing content denies discovery, search, page, thumbnail, image, and download access, including stale snapshots or cached grants. External copies already downloaded cannot be recalled. Public and authenticated cache variants must never mix. The [website delivery boundary](2026-09-14-workspace-public-delivery.md) owns the owner-only switch and the invalidation of previously issued public image tokens.

## Administration experience

New notes and uploads default to private; full path entry remains an advanced action. Publishing reuses repository public/private roots and coordinated moves and references; it does not add a second per-document visibility authority outside files. [Content navigation](2026-09-14-admin-content-navigation.md), [repository-wide filename search](2026-09-14-administration-filename-search.md) and [explicit public-page availability](2026-09-14-editor-public-page-state.md) own restorable URLs, unsaved-change handling, filename search and the editor's saved-versus-public state.

## Alternatives and boundaries

A default-space blog cannot represent several independent accounts. Content storage owned only by an account would lose the shared-space authorization boundary; [account working copies](2026-09-14-account-working-copies.md) remain scoped to an authorized workspace. Open registration and provider-side repository creation need their own abuse and provisioning controls, which [consumer identity](2026-09-20-consumer-identity-and-site-policy.md) and [GitHub-authorized personal spaces](2026-09-21-github-authorized-personal-spaces.md) own.

Purely client-side workspace switching could save edits into another tab's selected repository; request-scoped workspace selection is required. Hiding private links in UI would leave image, history, export, and execution entrances exposed, so service authorization owns visibility. Per-request random ordering would break pagination and reading returns, so randomization has a stable batch.

Cross-instance identities, personalized ranking, destructive workspace deletion, and ownership transfer are not implemented. [Community interactions](2026-09-23-community-interactions.md) own comments and likes, and consumer identity owns email recovery. Scoped credential rotation, revocation, and last-owner protection remain required operations.

## Verification

Subsystem records name the tests that pin their contracts. The [real GitHub connection run](../../acceptance/evidence/2026-09-15-provider-connection.json) verifies failed credentials, same-request retry, authoritative result lookup, idempotent replay and duplicate-binding rollback against PostgreSQL without modifying the repository; it makes no claim about CNB interoperability or remote content writes. The [two-space browser evidence](../../acceptance/evidence/2026-09-14-daily-use-ui.json) covers search and editor delivery.
