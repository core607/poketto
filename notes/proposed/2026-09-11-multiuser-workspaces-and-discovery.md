# Multi-user Workspaces and Public Discovery

Date: 2026-09-11

## Problem

Account registration, workspace membership, and installation currently share a browser entrance. Browser and MCP requests resolve the default workspace even though relational identity and storage already carry workspace identifiers. Public navigation treats directory indexes as ordinary articles, emits Markdown fragments as summaries, and loses collection and search context.

This delivery extends [workspace identity](../implemented/2026-09-06-workspace-identity-http.md), [remote repository authority](../implemented/2026-09-01-remote-repository-authority.md), and [MCP OAuth](../implemented/2026-09-11-mcp-oauth.md). It preserves their operation-level authorization and remote Git authority. It replaces the automatic personal-repository provisioning choice in [consumer accounts](../rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md). The invitation, frontend, and phase-one proposals retain their independently applicable requirements; this record owns the multi-user behavior and discovery changes described below.

## Accounts and installation

An operator runs an interactive deployment command to create the first site administrator and default-space owner. Password input is hidden and never appears in arguments or logs. The durable initialization singleton prevents a second initialization. The anonymous browser initialization endpoint and its navigation are removed.

The browser has login and registration entrances. Registration requires a registration invitation, accepted through a text field or a link that populates it. Registration creates an account without membership or an automatic personal workspace. Accounts without spaces can log in, create a space, or accept a workspace invitation.

Registration invitations and workspace invitations are separate credentials and operations. Each is single-use, expires after seven days, and is stored only as a digest. A workspace invitation specifies its workspace and initial member permissions; it cannot register an account or replace a registration invitation. An unauthenticated recipient completes login or separately authorized registration before continuing acceptance.

`poketto.registration.user-invitations-enabled` defaults to `false`: only site administrators can issue registration invitations. Enabling it permits authenticated accounts to issue them. Eligibility and issuance allowance use one policy boundary so user classes and per-account quotas can be added independently. This delivery does not impose a fixed per-user invitation quota. Bounded request admission and paginated listings remain transport safeguards. Issuers can list and revoke their invitations; site administration is distinct from workspace ownership. Disabling ordinary issuance does not silently revoke previously issued invitations.

## Workspace lifecycle and access

The account-level space list supports creation, joining, and switching. Creation asks for a display name, public slug, and an existing GitHub or CNB HTTPS repository with credentials. The creator receives the owner membership. Creation is durable and idempotent, records failure stages, and resumes without duplicate spaces or destructive writes to existing content. One remote repository cannot be bound to two spaces. There is no automatic provider-side repository creation.

Credentials are encrypted using a deployment-provided key, scoped by workspace, excluded from response bodies and logs, and rotatable only by the space owner. Repository validation rejects unsupported origins, credentials in URLs, unsafe redirect targets, and private-network destinations. An established workspace cannot be rebound to another repository through this delivery's UI.

A new workspace starts with public delivery disabled, including when its repository already contains `public/`. The owner must enable public delivery explicitly. Public delivery then exposes only the repository's public scope; `private/` and other excluded files never enter public discovery. Registered membership is required to use the private administration surface. Members can read the public scope within their space even while its public website is disabled; private files additionally require private-read permission.

Owners list members and assign private read, private write, and public-content write/publish permissions separately. Private write requires private read. Ordinary invitations default to none of these capabilities. Owners alone administer membership, repository credentials, and the space's publication switch. The last-owner invariant remains. Existing ordinary members lose implicit permissions when the new permission model is installed; owners remain owners.

Effective machine access is the intersection of the connection's explicit grants and its holder's current permissions. Reducing membership permissions revokes over-scoped connections and terminates affected execution sessions. Increasing permissions never expands existing connection grants. All storage, media, histories, exports, caches, jobs, and authorization checks remain workspace-scoped.

Account identity is available independently of workspace access. Private HTTP operations explicitly select the workspace in their route and reauthorize each request. A browser-wide mutable current-space value must not decide where an edit is saved: separate tabs can safely edit different spaces. OAuth consent selects one accessible workspace and fixes it into the issued connection; the existing `/mcp` resource address remains, with workspace authority derived from the credential rather than the browser's selection.

## Public discovery and reading

The root site becomes cross-workspace public discovery. Space websites use `/s/{slug}`; existing published default-space article URLs redirect to their canonical new routes. Both logged-in and anonymous visitors see public discovery; accounts additionally have a My spaces entrance. Cards identify their author display name, space, and collection without exposing private account fields.

Discovery mixes public article, album, and collection cards. It does not turn every raw media file into a post. Random order is stable within a browsing batch, including pagination and return navigation; an explicit reshuffle starts a new batch. Publication withdrawal overrides old batches. Discovery uses bounded verified public data, not a synchronous repository fetch for every space on each request.

Albums and collections have stable navigation entrances. Folder landing detection prefers `index.md`, with `README.md` when no index exists; two files in one folder must not produce duplicate landing cards. Existing authored text remains intact. Album thumbnails use a disposable cache keyed by workspace, immutable media version, and representation; originals remain authoritative. A lightbox supports previous/next, Escape, and focus restoration. Album and collection names are content-derived, never hard-coded to demonstration data.

Collection entries follow resolved article links in the landing document's authored order. An article retains the collection through which it was opened; direct entry offers its memberships instead of guessing an ambiguous parent. Reading navigation supplies collection return and previous/next articles, with a clear final entry.

Summaries come from parsed visible Markdown text before truncation. Link labels remain without URL syntax; duplicate opening titles are omitted from summaries. Article rendering omits only a first level-one heading equal to the separately rendered page title.

Site search covers enabled public spaces; space search fixes one space. Authenticated management search fixes both a space and the caller's current authorization. Literal visible-text matching is retained without semantic search. Titles and snippets highlight matches using escaped text nodes and `mark`, while article bodies retain normal reading. Snippets surround visible matches rather than raw URL or Markdown bytes.

Search query, pagination, and space scope live in URLs. Browser-history-local state preserves the result anchor and scroll offset. Articles entered from search offer Return to results; direct entries use their space or collection. Browser Back must continue to work. Raw or cross-origin return URLs are not trusted navigation targets.

Disabling public delivery or withdrawing content denies discovery, search, page, thumbnail, image, and download access, including stale snapshots or cached grants. External copies already downloaded cannot be recalled. Public and authenticated cache variants must never mix.

## Administration experience

New note and New folder actions operate in the selected directory. New notes and uploads default to private; full path entry remains an advanced action. Filename search covers all authorized files, not just expanded tree entries, and is distinct from body search.

Management tabs, selected space, folder, and document have restorable URLs. Unsaved changes are handled before changing space or document. The editor distinguishes saved Git state from public-page availability, labels visibility, offers View public page, and updates image previews automatically. Publishing reuses repository public/private roots and coordinated moves and references; it does not add a second per-document visibility authority outside files.

## Alternatives and boundaries

A default-space blog cannot represent several independent accounts. Account-owned storage would lose the shared-space authorization boundary. Open registration and provider-side private-repository creation add provisioning and abuse mechanisms beyond connecting an existing repository, so registration remains invitation-gated and remote creation is excluded.

Purely client-side workspace switching could save edits into another tab's selected repository; request-scoped workspace selection is required. Hiding private links in UI would leave image, history, export, and execution entrances exposed, so service authorization owns visibility. Per-request random ordering would break pagination and reading returns, so randomization has a stable batch.

This delivery excludes cross-instance identities, automatic remote creation, personalized ranking, comments, likes, email recovery, destructive workspace deletion, and ownership transfer. Scoped credential rotation, revocation, and last-owner protection remain required operations.

## Acceptance and delivery

- Registration invitation defaults admit only site-administrator issuance; enabling ordinary issuance permits accounts without making workspace owners site administrators. Separate credential types cannot be exchanged or reused, and concurrent redemption creates at most one account.
- Two accounts and two spaces remain isolated across simultaneous tabs, invitation acceptance, member updates, files, media, exports, OAuth, and MCP execution. A no-space account can log in and create or join a space.
- Repository connection failure, retry, ambiguous response, and duplicate submission do not duplicate resources or overwrite source content. Credentials never appear in UI evidence or logs.
- New workspaces keep existing public files off the internet until enabled; private files remain private afterward. Stale discovery and media grants cannot bypass withdrawal.
- From the home page, two clicks reach an existing named album with recognizable thumbnails. Any collection article provides collection return and sequential reading.
- Homepage and search cards contain no encoded destinations, broken Markdown, or repeated title. Search matches highlight safely, and browser/page returns restore query, page, and reading position.
- New-file, filename search, editor state, keyboard navigation, and mobile flows use a real running frontend and backend for evidence.

Implement cohesive changes in dependency order: account/registration foundation and installation; workspace provisioning and scoped entrances; member and machine authorization; discovery and reading; management interaction. Keep this record proposed until the complete behavior is demonstrated, while completed subsystem records describe their shipped contracts. Required database, storage, executor, UI, and deployment checks follow each changed surface; final HTTPS acceptance preserves existing managed originals and independently verifies public and private behavior.
