# Development and operations

Runtime requirements, content configuration, MCP access and deployment for Poketto.

[Project overview](../README.md) · [中文说明](usage.zh.md)

## Community

The **Community** page at `/community` contains chronological updates from followed
spaces, private bookmarks, your like history, notifications, followed spaces and blocked accounts.
COMMUNITY, CREATOR and ADMINISTRATOR accounts may add likes, bookmarks, follows,
comments and replies through a browser session. VIEWER accounts can read public
discussion, remove their own records, mark notifications read, report comments and
manage blocks. Workspace membership and API keys do not grant these interactions.

Articles need a unique canonical lowercase UUID in optional frontmatter `id` to
receive interactions. New browser note and folder drafts include one. For an
existing article, choose **Enable article interactions** in the editor, then save
and publish normally. Preparation only changes the draft and retains its original
text, metadata, line endings and version checks. A malformed existing `id` must be
corrected in source; the action does not replace it. Keep the ID when renaming or
moving the same article, and generate a new UUID when copying it as another article.
External Git authors can supply the same field without using the editor.

Markdown without an ID remains readable. Missing, malformed or currently duplicated
public IDs have no interaction entrance; diagnostics help authors resolve invalid
and duplicate IDs. The public article response includes `articleId`, or null when
unavailable. The workspace is part of the identity: another space cannot claim its
history. Changing an ID starts a different history; restoring it reconnects the
retained records. Space following also works for articles without IDs.

Comments are plain text with up to 4,000 Unicode code points and one level of
replies. Retrying the same comment request is idempotent. Deleting your comment
cannot be undone; a root becomes a tombstone when replies remain and accepts no
new replies. Space owners and site administrators can remove comments, hiding a
root's replies with it. Administrator report review is on the Community page and
grants no access to private Git content. Reports contain at most 1,000 code points.
Comment identities are account display names; article bylines are freely authored.

Blocking hides an account's comments and notifications for you and prevents new
replies between you. It does not hide public articles from anonymous readers.
New root comments notify up to 100 current space owners, and replies notify the
root author, excluding the actor and blocked pairs. Each inbox retains its latest
1,000 notifications. Bookmarks and following do not disclose a public roster or
send the space owner a notification. There are no direct messages or email alerts.

Withdrawal, publication restrictions, expired snapshots and removed articles hide
their public discussion, notification targets and distribution cards. Existing
records remain. Private lists show unavailable placeholders with removal controls;
they do not expose the old title or body. Account downgrade prevents new
interactions but does not automatically erase comments on other people's articles.

Each account can retain 1,000 likes, 1,000 bookmarks, 100 followed spaces and 500
blocks. New comments are limited to 10 per minute and 300 per UTC day; relations to
60 per minute and 3,000 per day; reports to 5 per minute and 30 per day; blocks to
30 per minute and 500 per day. Idempotent retries do not consume another allowance.
Paginated lists return at most 20 records per page; the following-space list returns at most 100 entries; a page with hidden records may be empty
and still have a cursor. The following feed admits two concurrent scans, at most
100,000 documents and five seconds per scan. Retry a limited scan later or follow
fewer spaces. Feed cursors describe current content, not a retained historical batch.

Public community reads use `/api/public/community/spaces/{slug}` and its
`/articles/{articleId}` child. Browser mutations and private lists use
`/api/auth/community`; mutations require the normal session, Origin and CSRF
checks, and request bodies are bounded to 64 KiB. Comment requests include a fresh
`requestId`, optional root `parentId` and `body`; preserve the request ID for an
uncertain retry. The [community decision](../notes/implemented/2026-09-23-community-interactions.md)
owns identity, visibility and transaction boundaries.

## Development

Use Java 26 and the checked-in Gradle Wrapper. Linux executor tests require Python 3.10+ with venv and pip support; Windows runs that required suite in a pinned Linux container. The frontend and complete check also require Node.js 24.19.0 and npm 12.0.2. Docker is required for database integration tests and the complete check; the faster unit and repository checks do not require it. `./gradlew frontendCheck` runs frontend formatting, types, tests and the production build. Use the [isolated browser entrance](../acceptance/README.md) to exercise the real application with synthetic data; frontend runtime settings are documented in [frontend/README.md](../frontend/README.md).

Application startup requires a PostgreSQL data source, an absolute `POKETTO_DATA_DIR`, and one pre-provisioned private HTTPS Git repository. Set `SPRING_DATASOURCE_URL`, database credentials, `POKETTO_REPOSITORY_REMOTE_URI`, `POKETTO_REPOSITORY_USERNAME`, and `POKETTO_REPOSITORY_PASSWORD` before `bootRun`. Flyway creates the default workspace; the application binds it to remote `main` and materializes only a disposable cache below `<data-dir>/workspaces/<workspace-id>/content`. `POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` and `POKETTO_REPOSITORY_TIMEOUT_SECONDS` optionally change the defaults of 32 workspaces and 30 seconds; `POKETTO_REPOSITORY_REFRESH_SECONDS` sets how often served content is re-validated against remote `main` (default 30), and `POKETTO_REPOSITORY_STALE_AFTER_SECONDS` sets how long served content may go without a successful re-validation before health reports it out of service (default 3600). Unavailable content keeps the process and refresh loop running, but readiness reports out of service and public reads fail closed. Snapshot expiry also stops public reads; the maximum stale lifetime is one hour. A running instance answers `GET /actuator/health` for deployment checks and serves the default workspace's public documents at `GET /api/public/documents`; a write through Poketto is visible immediately, and a valid direct push after the next refresh.

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

Start the application to initialize its database schema, then run `./deploy.sh --initialize-admin` on the deployment host in an interactive terminal. Enter a username and confirm the hidden password. The command creates the first site administrator and default-workspace owner once; it cannot replace existing accounts. It uses the running app container's database environment and does not expose a browser setup endpoint. See [operator administrator setup](../notes/implemented/2026-09-11-operator-administrator-setup.md) for custom container installations. Configure `POKETTO_SECURITY_ALLOWED_ORIGINS` with the exact browser origin. Local HTTP also needs `POKETTO_SESSION_COOKIE_SECURE=false`; HTTPS retains the secure default. Fetch `/api/auth/csrf` before login and send its named CSRF header with the session cookie.

Guests can register with a verified email, password and display name, or use Google login when configured. Registration needs no invitation or additional username. Existing accounts retain username/password login and can bind a verified email in account security. Workspace invitations remain separate: joining a space grants its stated membership, not a higher account group. Accounts without spaces can sign in and manage their login methods.

Set `POKETTO_RESEND_API_KEY` and `POKETTO_EMAIL_FROM` to enable email verification and password recovery. The from-address must use a verified sending domain. Six-digit codes expire after ten minutes, allow five failed attempts and can be used once; wait sixty seconds before resending. `POKETTO_EMAIL_DAILY_LIMIT` defaults to 100 sends per UTC day. Email and source-address limits also apply. A failed send does not report success. Password recovery invalidates old browser sessions and machine credentials while retaining workspace membership. Rotating the Resend key expires outstanding email proofs.

Set `POKETTO_GOOGLE_CLIENT_ID` and `POKETTO_GOOGLE_CLIENT_SECRET` to enable Google login. Register a Web application in Google and set its authorized redirect URI to `<POKETTO_OAUTH_ISSUER>/api/auth/identity/google/callback`. `POKETTO_OAUTH_ISSUER` is the exact HTTPS origin without a path; local development also permits loopback HTTP. Request only `openid`, `email` and `profile`. Google must return a verified email. Matching emails do not merge accounts: sign into the existing account and explicitly link Google. Account security cannot remove the final login method. Google and email configuration are independent; an unconfigured provider has no login button.

New accounts are **viewers**, including accounts registered through Google. Administrators assign one fixed group: viewer, community member, creator or site administrator. Community membership permits the interactions described above; creators and administrators can connect repositories and expose eligible websites. Site administrators search accounts, change groups with a reason, inspect the change history and review each account's owned spaces. They can review current repository-public articles and referenced media without receiving private-file or original-history access. The last administrator cannot be demoted.

Every owner must be a creator or administrator for a space's website to appear. Demoting any owner hides that space's public routes, discovery, search, feeds and media, while retaining the author's website switch and existing member/MCP permissions. Owners see the restriction reasons and can continue editing. Restoring eligibility restores websites whose switches remain on; a switch the author turned off remains off. A group change does not affect spaces where the account is only a member.

On Windows, `check` also runs `linuxStorageTest` in a pinned Linux container using a disposable native disk volume, including durable public-marker and snapshot restoration tests. Windows development keeps public snapshots online-only: fresh remote verification permits in-memory reads, but an offline restart never restores public authorization from disk. Linux requires successful file and directory synchronization before publication-affecting writes can push; unsupported or failed synchronization closes service. Authoritative image storage requires directory synchronization; an unsupported host cannot acknowledge durable uploads. Set the same names through `$env:...`, make `POKETTO_DATA_DIR` absolute, and use `.\gradlew.bat`. See [AGENTS.md](../AGENTS.md#commands) for the command table and contribution rules.

## Content and images

The management page lists the signed-in account's spaces. Select a space before editing; its `workspace` URL parameter keeps separate tabs independent. Account and space management also supports connecting an existing private GitHub/CNB repository, querying or retrying an interrupted creation, and accepting a workspace invitation. Configure `POKETTO_REPOSITORY_CREDENTIAL_KEY` as a Base64-encoded 32-byte secret before enabling repository connection. New spaces start with public delivery disabled. Creation commits the content template into an empty repository as its first commit; if that write does not complete, the space's Repository connection tab offers it. A repository that already holds content is left unchanged, and the same tab lists the template's absent guide and policy files with an action that adds only those. Provider tokens require metadata read and Git write access and are never retained in browser drafts.

When the operator configures the GitHub App, creators and administrators can authorize their personal GitHub account from account settings. Before creating a space, use **Manage GitHub repository access** to install the App on that account. GitHub's selected-repository installation requires at least one existing repository; users can create an empty private repository for this purpose. Confirm the account and an unused repository name to create the space's private repository. GitHub automatically grants the App access to repositories it creates; if access is missing, select the new repository in installation settings and continue the same request. An explicit installation-permission rejection leaves that request retryable after permission is restored. The request history retains interrupted attempts; an uncertain creation result must be reconciled there before starting another request.

For an existing GitHub App connection, the space's Repository connection tab offers **Verify and restore connection**. Restore the original GitHub account authorization in account settings first, then use “Manage GitHub repository access” to select the repository. That action remains available after a site-group downgrade and does not require an unfinished creation request. If the repository was renamed, enter its current name. Reconnection verifies the original immutable owner and repository IDs; a transferred repository or a newly created repository with the old name cannot replace it. Only the space owner whose account supplied the original authorization can reconnect it, including after a site-group downgrade. Successful reconnection invalidates previously prepared repository credentials. Manual-token connections retain their separate credential-update form.

If short-lived verification expires while an operation waits, retry the same operation or continue the original creation request. Expiry alone does not require GitHub authorization again. Management errors distinguish temporary access failures from connections that the original authorizing owner must restore in Repository connection settings. Reconcile an uncertain remote write before retrying it; the browser does not resend it automatically.

The [GitHub App configuration guide](github-app.md) covers the five protected settings, permissions, callback and signing-key conversion.

Set the App's webhook URL to `/api/hooks/github` on the public HTTPS origin and its webhook secret to the protected `POKETTO_GITHUB_WEBHOOK_SECRET` value. Subscribe to Repository events; authorization and installation events are delivered by default. Revocation stops the affected repository access without deleting content or membership. Restore access explicitly through reconnection; permission additions and unsuspension do not reopen it automatically. The endpoint accepts JSON payloads up to 25 MiB and returns 409 for an already committed delivery. Redeliver failed notifications from GitHub after restoring service; ordinary credential preparation also verifies current provider access.

Private HTTP routes use `/api/admin/workspaces/{workspaceId}`. Read memberships from `GET /api/auth/workspaces` and current authorization from `GET /api/auth/workspaces/{workspaceId}/me`; unscoped administration routes do not select a fallback space. OAuth consent chooses one joined space, and the `/mcp` resource derives that space from its issued credential. See [workspace routing](../notes/implemented/2026-09-11-workspace-browser-and-mcp-routing.md).

Owners set private reading, private writing and public editing/publishing in member management or on a workspace invitation. Invitations default to public-scope reading only; private writing also requires private reading. Publication-policy exclusions remain private even below `public/`. Members can read their space's current public scope while its anonymous website is disabled. Reducing grants revokes over-scoped connections; increasing grants does not enlarge existing connections. See [member content permissions](../notes/implemented/2026-09-12-member-content-permissions.md), including the loss of implicit private access for existing ordinary members when this schema is installed.

Space owners can update managed repository credentials from the Repository connection tab. Supply a Git username and replacement token; the server validates access before replacing the existing credentials. This cannot change the repository address. The form clears submitted tokens and never stores them in browser drafts. After a confirmed update, revoke the old token at the Git provider. Deployment-managed repositories require an operator configuration update.

The [content-template](../content-template/AGENTS.md) is what initialization adds: a root guide,
independent `private/` and `public/` trees with their own guides, and a publication policy that
starts disabled. Nothing existing is modified or moved, and content outside the two trees stays
private. A deployment-managed repository is initialized the same way from its space's Repository
connection tab. Create new content under `private/`. To publish selected content, move it and its
required media into `public/`, then configure `.poketto/publishing.yaml`:

```yaml
enabled: true
mode: public-root
exclude:
  - public/drafts/**
```

Only paths below the exact root `public/` are eligible. Exclusions use full repository-relative paths and win; guides named `AGENTS.md` in any letter case and hidden path segments stay private. Names below either root are ordinary categories. Missing or disabled policy publishes nothing; invalid policy closes public service. Existing default-public repositories need the [coordinated content conversion](../notes/implemented/2026-09-09-codeact-content-and-media.md#implementation-and-acceptance) before upgrading; copying the template over an existing repository is not a conversion.

Markdown metadata is optional, and unchanged source bytes are retained. Default routes omit `public/` and `.md`; an explicit article route is preserved but cannot grant publication rights. Folder landings always derive their route from their directory and ignore a frontmatter route override, including after moves; authored source remains intact. The public detail endpoint is `GET /api/public/document?route=...`; list/search and tags include snapshot metadata. `index.md`, or `README.md` when no eligible valid index exists, owns its folder route (`public/index.md` owns `/`) and supplies a non-recursive sibling-image gallery without repeating body images. When both valid landings exist, structured reading uses the index and retains the README source. Gallery images open in a dialog with previous/next buttons, arrow-key navigation and Escape return to the opener. Public gallery tiles load thumbnails up to 640 pixels on the longest side; opening the lightbox loads the unchanged original. Transparent thumbnails use PNG and opaque thumbnails use JPEG. JPEG orientation is preserved, and animated previews use the first frame. WebP thumbnails accept up to four million source pixels; PNG/WebP files containing EXIF metadata have no thumbnail in this version. An unavailable preview still opens its authorized original. Authenticated editor previews retain original image delivery.

Public articles, album and collection landings, discovery cards and search summaries show the nonblank `public_author` frontmatter string first. Otherwise they use the workspace public signature from **Website publication**, falling back to the public workspace name when unset. Both signatures are trimmed single-line text, limited to 120 Unicode code points. Only a signed-in owner can change the workspace signature. Existing arbitrary metadata such as `author`, account details and Git commit identities remain private.

Public and authorized management search match literal titles and parsed Markdown reading text. Link labels, image descriptions, code, table cells and referenced footnotes participate; hidden destinations, raw HTML and unused footnote definitions do not. Summaries collapse whitespace and omit an opening level-one heading only when its text repeats the page title, then take a bounded excerpt around the match. Stored Markdown and article bodies remain unchanged.

Site search at `/search` uses `/api/public/search` to combine every enabled website's current verified public snapshot. Result cards identify their space and open its canonical article; space search remains scoped to `/s/{slug}/search`. Combined results sort by newest creation time, then space slug and route, with a total across the searched corpus. Every page reads current content, so publication changes can change counts and page boundaries. An unavailable snapshot or a changed corpus during the request refuses the whole response instead of returning partial results. The service permits two concurrent queries and bounds each scan to 256 spaces, 100,000 documents, 64 Mi UTF-16 body characters and a five-second deadline checked between processing steps. Exceeding a bound asks the reader to retry or search an available space directly. The default `/api/public/documents` endpoint retains its default-space scope for archive and feed consumers.

Public search highlights exact matches in titles and snippets. Opening a result provides **Return to search results**, preserving its query, page and space. Browser Back restores the selected result and scroll position; the explicit return link also restores them while the browser retains that tab's entry. If local storage is unavailable or the result has been removed, the search page still opens normally. Direct article visits retain their space and collection navigation.

Authenticated `/api/admin/workspaces/{workspaceId}/repository` endpoints provide the Markdown index, paginated directory listing, file reads, search, preview, atomic patches and moves. The browser destination picker moves files or folders and repairs Markdown references in the same commit. Text changes carry revisions or explicit absence against the base commit; moves check the source and destination at that base. Conflicts or uncertain outcomes require a fresh read before retry. Image uploads under `/api/admin/workspaces/{workspaceId}/assets` require an `Idempotency-Key`, accept up to 16 MiB, return immutable references and do not write Git or publish.

The editor retains the selected folder and document in its URL, independently of
each other. Refresh and Back/Forward restore saved repository content; cancelling
the unsaved-change prompt preserves the current draft and its navigation history.
Switching spaces clears the previous folder and document. **New note** and **New
folder** use a single name and show their private destination, preserving the
selected category beneath `private/` even when a public folder is selected. They
prepare a local draft; **Save** writes the note or the folder's ordinary `index.md`.
Existing exact paths are refused before preparation, and Save checks repository
name collisions while retaining a rejected draft for correction. Full-path entry
remains under **Advanced: full path**, starting at `private/`.

**Publish** and **Withdraw to draft** move a saved note between matching private
and public paths after confirmation. Save edits first. Category paths, article IDs
and reference repair follow the ordinary atomic move rules; private dependencies
or collisions refuse the move. Publishing does not enable a disabled website or
override exclusions or account restrictions. The public-page status remains the
authority for availability. Move the containing folder when media must travel with it.

**File history** is available to members with private-read permission. It lists
changes to the selected literal path along remote main's first-parent history;
merge entries compare the resulting tree with the first parent. It does not follow
renames. Select a version to compare its exact source with the current editor text,
including unsaved changes. Large comparisons show source side by side. Deleted,
binary, oversized and managed-media versions cannot supply restoration text.
**Restore to editor** requires current write permission and confirms replacement
of unsaved text. It retains the current file's revision/absence precondition;
**Save** creates a new commit and still refuses concurrent changes. It never resets
Git history or saves automatically.

`GET /api/admin/workspaces/{workspaceId}/repository/history` accepts `path`, an
optional exact `commit`, `offset` (default 0) and `limit` (default 20, maximum 32).
Pages inspect at most 256 commits for changes. `nextOffset` counts inspected
commits, so an empty page can still have a continuation; pass its pinned `commit`
when continuing. Historical metadata and bytes require current private-read access,
even for a currently public path. The [history record](../notes/implemented/2026-09-23-browser-history-and-restoration.md)
owns traversal and comparison bounds.

Unsaved text is retained as plaintext recovery data in this browser, scoped by
account, space, path and editing tab. **Local drafts** lists accessible recovery
records; opening a file offers restoration only after a fresh authorized read.
Restoration never saves automatically. Changed remote content retains the original
revision check, so copy and reconcile conflicting edits before saving. Typing is
batched after a 250 ms pause, with a one-second maximum delay; hiding or leaving
the page attempts an earlier write. Wait for the retained-draft notice before
relying on recovery; abrupt termination can lose the most recent pending edits.
The browser store shares a limit of 20 drafts and 2 MiB of encoded records across
accounts and spaces, with a 1 MiB source
limit per draft. Unsupported Web Locks, storage failures and quota exhaustion show
a notice without evicting another draft. Successful save, explicit discard and
logout clear the corresponding records; logout clears this account's records in
all spaces. They do not sync across devices. Clearing browser data loses them,
and anyone with access to the browser profile may read them. A full shared store
may require switching to another authorized account or space to clean its drafts;
clearing site data in browser settings removes all local drafts.

Paste or drop one PNG, JPEG, WebP or GIF image, at most 16 MiB, into the source
editor to upload and insert a reference. Ordinary text paste is unchanged. Failed
uploads retain the same operation identity for retry. Uploading alone does not
save or publish the article. Leaving during an upload prevents a late response
from inserting into another file; an uploaded original can still be selected from
the image shelf. Upload requires private-write permission.

In the move picker, the private/public
directory buttons retain the category path while switching roots. Selecting a
destination does not write until the move is submitted. Moving a directory includes
its indexed media; moving one document does not move shared dependencies.

**Search filenames** searches all authorized regular Git files and indexed-media
paths, including unopened folders. It matches the repository-relative path
literally and shows up to 50 results per page. Paging retains the returned commit;
starting a new search reads current main. Public-only members see only current
publication-eligible paths, even when the website is disabled. Body search remains
a separate form. Both forms highlight literal matches; searching leaves the draft
intact, and opening a different result asks before discarding edits. Binary files
retain their existing text-read restrictions.

The filename endpoint is `/api/admin/workspaces/{workspaceId}/repository/filenames`
with `query`, optional `commit`, `offset` and `limit`. Queries contain 1–200
characters; page limits are 1–200 and offsets 0–100,000. Continuations require the
first page's commit. A scan refuses more than 100,000 combined Git tree and indexed
media entries, including traversed directories; it does not return partial counts.

The editor distinguishes saved content, publication scope and public-page
availability. **View public page** opens the confirmed canonical page in a new
tab; unsaved edits remain in the editor and do not change that page. Disabled
websites and unavailable pages have separate labels. After a confirmed save, a
separate exact-commit read refreshes this information. If that read fails, the
confirmed save and body remain intact; **Check status again** retries only the
read. New drafts and changed destination paths wait for Save before confirming
their public-page state.

Managed originals live under `<data-dir>/managed-originals` and are retained; `<data-dir>/derived/repository-images` is disposable. Public image grants bind the exact page snapshot for at most five minutes and never past its expiry. Disabling website delivery or replacing its public snapshot also invalidates previously issued image URLs; reload the page to obtain current URLs. Private previews recheck the current identity. The [website delivery boundary](../notes/implemented/2026-09-14-workspace-public-delivery.md) records this authorization change; the [foundations record](../notes/implemented/2026-09-05-repository-authoring-foundations.md) retains storage guarantees and bounds.

Human owners manage the website switch from the selected workspace's **Website publication** panel, or `GET` / `PUT /api/auth/workspaces/{workspaceId}/publication`. A write requires the session CSRF token and `{ "enabled": true }` or `{ "enabled": false }`; omission is an error. The panel asks for confirmation and requires a fresh state read after an uncertain response. Website shutdown leaves member access to authorized repository files intact.

Each enabled website has an entrance at `/s/{slug}`, with its own `/search`, `/tags`, `/archive` and `/read/...` pages. The corresponding `/api/public/spaces/{slug}` endpoints fix document and media scope to that space. Unknown and disabled slugs return 404. Background refresh rotates through at most eight enabled spaces per pass, plus the default workspace for repository health. Newly enabled sites can remain unavailable until refresh succeeds; requests never fetch remote Git. Public original-download URLs include a required `workspace` query parameter alongside the page route, commit and logical path. Opaque image tokens already bind their workspace.

`/sitemap.xml` indexes the root site and all enabled space websites. Its child
sitemaps contain canonical `/s/{slug}` and `/s/{slug}/read/...` URLs from each
space's current approved snapshot, without sampling discovery batches. Unavailable
snapshots or exceeded enumeration bounds return 503 instead of an incomplete list;
disabled or unknown spaces return 404. `/robots.txt` advertises this index using
`POKETTO_PUBLIC_URL` and discourages crawling `/admin` and `/api/`. These crawler
directives do not grant or revoke content access.

The root homepage samples enabled public spaces into a stable browsing batch. Pagination and browser return keep its order; **New batch** explicitly reshuffles. Withdrawal removes cards from existing batches. A batch lasts up to 30 minutes and can expire earlier after restart or cache eviction; an expired link offers a new batch. Discovery samples at most four pages per space and 32 spaces per batch, with later batches advancing through the catalog. It is not an exhaustive search. See [discovery batches](../notes/implemented/2026-09-14-public-discovery-batches.md).

The homepage also offers **Following** and a direct **Private bookmarks** entrance.
Following reads the signed-in account's chronological feed through authenticated
requests, separately from public discovery. Within each space's four-card cap,
discovery selects an authored `featured: true` choice, a recent article, a tag-diverse
choice and random remaining content. Missing featured choices leave more random
slots; malformed or non-boolean values do not enable the signal. This is an author's
choice, not a site endorsement. **Discover by tag** matches a complete, case-sensitive
tag (up to 64 characters); cards' tag links start a new filtered batch. The tag is
retained across pagination and continuation, and changing it starts a new batch.
`/api/public/discovery` accepts optional `tag` when creating a batch; a different tag
with an existing `batch` or `afterBatch` returns 400. No private reading history or
follow list enters the selection. See [authoring and discovery](../notes/implemented/2026-09-23-authoring-and-discovery-experience.md).

Homepage cards distinguish articles, directories, albums and collections. A folder with both sibling images and an authored reading sequence shows both album and collection labels. Visible album cards load one thumbnail linking to the named folder; an unavailable cover keeps that entrance without downloading an original. Revisiting a current batch page refreshes its short-lived cover URLs while retaining the card order.

Folder landing links supply a collection in authored order. Reading from that landing preserves the chosen collection through previous/next and return links; direct article entry lists its memberships without guessing a parent. Repeated links contribute one entry, and private, missing and external targets are excluded. The final article explicitly ends the sequence. A duplicate opening level-one title is hidden in article reading while its fragment anchor remains; original Markdown and editor previews are unchanged. See [collection reading](../notes/implemented/2026-09-14-collection-reading.md).

`POST /api/admin/workspaces/{workspaceId}/media` accepts raw octet-stream originals up to 128 MiB with an `Idempotency-Key` and optional `X-Media-Type`. Storage deduplicates bytes strictly within a workspace while retaining independent upload identities. Set `poketto.assets.max-file-bytes` to lower the upload bound; existing originals remain readable. The [logical media index](../notes/implemented/2026-09-09-logical-media-index.md) combines media paths with Git directory entries and can be saved atomically with text. [Indexed media delivery](../notes/implemented/2026-09-09-indexed-media-delivery.md) renders relative image links and supplies original attachments through authenticated `/api/admin/workspaces/{workspaceId}/media` and publication-bound `/api/public/media` downloads. Uploading never writes the index or publishes.

Relative Markdown links to indexed MP3, WAV, MP4 or WebM originals show native
playback controls in articles and authorized previews, for example a relative link
to `recording.mp3`. Use `audio/mpeg`, `audio/wav` (also `audio/wave` or
`audio/x-wav`), `audio/mp4`, `video/mp4`, `audio/webm` or `video/webm` when importing.
Playback checks original integrity and a bounded container signature; codec
support still depends on the browser. Players never autoplay or preload media.
The adjacent download link remains available if playback fails. External links,
raw HTML and unsupported originals do not become players.

Adding `play=true` to an authorized media download requests playback with a fixed
media type, inline disposition, `no-store` and `nosniff`. One bytes range supports
seeking; invalid or unsatisfiable byte ranges return 416. Multiple ranges or
unsupported units receive the complete representation. HEAD ignores Range;
If-Range receives a full response. Every request verifies the original, so seeks
can add disk-read cost within the existing 128 MiB original bound. Current identity
and publication checks apply to every request and subsequent output blocks;
already buffered bytes cannot be recalled. [Playback limits and rationale](../notes/implemented/2026-09-23-controlled-media-playback.md).


Members editing public content without private-read permission use **Choose public images**. The picker lists current eligible Git images and indexed managed images, inserts relative paths, and excludes private or withdrawn content. Uploading a new original still requires private-write permission.

## Export HTTP interface

In the editor, use **Export** beside a file or inside an expanded folder, or the
file sidebar's export action for the whole workspace. Choose a private copy or a
public copy, generate the ZIP, then download it. Exports use the latest saved
content; unsaved editor changes stay outside the package. Public copies reject
private selections rather than changing publication. Downloads use the browser's
download manager. Closing the dialog leaves an already offered package available
until expiry, so an active download can finish.

On native Linux, `POST /api/admin/workspaces/{workspaceId}/exports` accepts `paths` (explicit Markdown,
indexed-media paths or directory prefixes) and an explicit `publicOnly` boolean.
It returns a temporary handle, ZIP size, SHA-256 and expiry. `GET
/api/admin/workspaces/{workspaceId}/exports/{handle}` downloads the archive; the `/metadata` suffix reads
its receipt, and `POST /api/admin/workspaces/{workspaceId}/exports/{handle}/release` releases it early.
Creation and release use normal session CSRF protection. Every operation rechecks
the owner and workspace; a handle cannot be shared as an anonymous download link.

Private packages require private-read authority and retain source frontmatter.
Public packages contain approved article fields and authorized originals; private
selections fail instead of being published. Relative links point to actual media
inside the ZIP. No original Git history, runtime guide or internal media index is
included. Download checks publication, expiry and identity again, verifies the
stored ZIP before output, and returns an attachment with `no-store` and `nosniff`.

The service stages below `<data-dir>/portable-exports/<workspace-id>` and removes
expired or abandoned packages. Defaults are one build, two downloads (at most one
per workspace), 512 MiB of originals, 256 MiB of text and 10,000 entries. Properties
under `poketto.exports` set `max-zip-bytes` (800 MiB), `max-retained-bytes` (2 GiB),
`max-workspace-bytes` (1600 MiB), `max-packages` (8), `lifetime-seconds` (600), and
`build-seconds` (120). A build reserves its full ZIP allowance before preparation;
only its actual size remains charged after success. Capacity exhaustion returns
429; missing, expired or differently owned handles return 404. Filesystems without
POSIX permission support return 503 before reading export content. The
[export decision](../notes/implemented/2026-09-10-portable-content-exports.md)
owns package selection and lifecycle.

In a CodeAct session, use `poketto export PATH... --output FILE [--public]`.
Selections use repository-relative files or folders; `.` selects the visible workspace.
Public-only sessions always export the host-approved public projection. The ZIP
contains latest saved content and originals; local edits are excluded. Different
existing output files are preserved. Use `get_artifact` after `poketto artifact
create FILE --type application/zip` when the result fits the artifact limits.
`MATERIALIZE_CAPACITY` preserves the session and existing files: free local space,
select fewer files, or use browser export when the ZIP exceeds the worker's capacity.
The [worker reference](../executor-service/README.md) owns deadlines, size bounds,
installation ordering and error codes; [real HTTP MCP acceptance](../acceptance/clients/evidence/2026-09-10-cli-exports.json)
verifies the authenticated export and artifact path.

## MCP and isolated execution

Use `poketto edit PATH --old TEXT --new TEXT` to replace one exact occurrence in an existing local text file. Missing or ambiguous original text is refused without changing the file. `poketto create PATH --text TEXT` creates a local text file only when its path is absent. Both commands leave remote Git and publishing unchanged until an authorized `poketto save`. They recheck the captured local bytes before installation; ordinary shell writes remain available and do not acquire these edit preconditions.


For long text, `poketto create PATH --stdin` reads UTF-8 from a quoted heredoc;
`--text-file FILE` reads an existing UTF-8 file. `poketto edit` accepts `--old-file
FILE` instead of `--old`, and `--new-stdin` or `--new-file FILE` instead of `--new`.
Final newlines are preserved, including an empty replacement. Input-file paths
follow the current shell directory; the target path remains repository-relative.
These options retain absence/exact-match and compare-and-replace checks. They do
not increase the 16,384-character `repo_exec` command bound or 512 KiB encoded bridge
frame bound; use existing input files or smaller exact edits for larger content.

```sh
poketto create private/article.md --stdin <<'MARKDOWN'
# Article

Quotes, `$variables` and backticks remain ordinary Markdown.
MARKDOWN
```

`/mcp` uses Spring AI 2.0.1 WebMVC Streamable HTTP and a workspace Bearer credential (API key or OAuth access token), independently of browser sessions. With the executor enabled, the catalog contains `repo_exec`, `repo_discard`, `get_artifact`, `get_asset` and `put_asset`. The asset tools transfer exact image versions and accept idempotent uploads; upload acknowledgement never implies publication.

`put_asset` accepts `operationKey` plus exactly one of `url` or `file`.
`url` is a public HTTPS image download address on port 443. `file` is a platform
file object with required `download_url` and `file_id`; optional `mime_type` and
`file_name` are hints, never validation authority. The tool declares
`_meta["openai/fileParams"] = ["file"]`; actual attachment forwarding depends on
client support. Downloads use validated public DNS addresses, at most three
redirects, a 30-second deadline and a 16 MiB byte bound. No cookies or authorization
headers are forwarded. Original bytes undergo the existing image validation.

If the client holds a file, call `put_asset` with `mode: "upload"` and
`operationKey`, without an image source. The response supplies `uploadUrl`,
`method: "PUT"`, `contentType: "application/octet-stream"`, `maxBytes`, and
`expiresAt`. From that client's own execution environment, upload raw bytes:

```python
import requests
with open(image_path, "rb") as image:
    response = requests.put(upload_url, data=image,
                            headers={"Content-Type": "application/octet-stream"}, timeout=30)
response.raise_for_status()
receipt = response.json()
```

Upload body collection stops after 30 seconds and releases admission on timeout or disconnect. A proxy may delay the early error while the sender leaves its body unfinished; set a client timeout and GET the upload URL to inspect the result.
Raw upload collection admits four accounts at a time, one upload per account.
Each collector reserves 32 MiB for a bounded 16 MiB body and its completion copy,
leaving at least 128 MiB available for page images under the default budget.
After collection, validation and storage reserve an additional 128 MiB until
completion. Upload-grant requests carry no image bytes and require no image
reservation. Busy uploads receive `TRANSFER_BUSY` and can retry with the same grant.


The URL is a secret, narrow upload grant bound to the requesting principal,
workspace and operation key. Current permissions are checked on every use. It
expires after 15 minutes or application restart; MCP disconnection does not revoke
it. The public base URL comes from `poketto.oauth.issuer`. Up to 512 grants are
retained per instance, with at most 64 total and 8 unfinished grants per account.
GET the same URL to recover a lost
receipt (`UPLOAD_PENDING` if no upload has completed). After expiry, request a
replacement with the same operation key and resend identical bytes; durable
idempotency returns the same original, while different bytes conflict.
MCP request bodies are limited to 128 KiB. Image imports and responses reserve
image memory at their own processing boundary; ordinary text calls do not take an
image reservation. Refusals include `code` and `reason` in the tool result and logs;
an image-budget refusal reports `IMAGE_MEMORY_BUSY`. Inspect uncertain writes
before retrying, regardless of any wait hint.

Both routes return `assetId`, `revision`, `reference`, `mediaType`, and `size`.
Use `poketto media link PATH --asset ID --revision REV`, then explicitly save the
text and `.poketto/assets.json`. Uploading alone does not save Git or publish.


`repo_exec` requires `expectedCopyId`: use `"new"` to open the account's default copy, creating it only when absent, then pass the returned `copyId` on subsequent calls. Reconnection automatically reattaches the original copy and baseline; no generation or resume flag is required. Closing an MCP transport preserves the copy. Each successful authorized copy operation renews its seven-day idle deadline, returned as `retention.expiresAt`. `SESSION_REPLACED` and `EXECUTION_REFUSED` mean this command did not execute. `EXECUTION_UNCONFIRMED` means it may have partially completed, including remote writes. Do not replay an uncertain write: inspect the same copy with a read-only command, `retention.lastInterruptedCommand` and `poketto status`, then use `poketto recover` when a remote save needs reconciliation. [Working-copy identity](../executor-service/README.md#working-copy-identity) describes the execution boundary.

For full-read copies, acknowledged saves and moves update local Git HEAD and the index before the CLI reports success. Unselected working files stay local. `repo_exec.commit` and `poketto status.gitCommit` identify the installed Git baseline. Status reports `localBaselinePending` when a retained remote result still needs local installation; run `poketto recover` without repeating the save. Git metadata never supplies authoritative write preconditions.

For full-read copies, `poketto status` also checks remote main: `remote.state` is `MATCHES_BASE`, `DIFFERS_FROM_BASE`, or `UNAVAILABLE`, with `remote.commit` when known. The comparison uses the last confirmed save/sync base; it does not certify that every local file is current. A failed remote check preserves local status and save receipts. Status never changes working files or their baselines. Run `poketto sync` explicitly to reconcile the whole workspace with one remote revision, including added and deleted paths. It preserves local-only files and conflicting binary bytes, and marks overlapping text edits with LOCAL/BASE/REMOTE sections. It does not save remotely. `poketto status` exposes `syncPending` and progress after interruption; `poketto recover` continues the retained operation, while `poketto recover --skip-local` releases it without undoing installed files. Later saves still check selected files against the authoritative remote. Public copies return `PUBLIC_PROJECTION` and their synthetic commit, without exposing an authority commit; every command still requires the existing public-projection validity check.

To discard local work, call `repo_discard` with its exact `expectedCopyId`. Current execution permission and ownership of the copy are required; lost content-read permission does not prevent cleanup. Busy copies are refused. Disposal is recorded before deletion and resumes safely after process loss. The worker must confirm containment before local files and host metadata are removed. `DISCARDED` or `ABSENT` confirms the addressed copy is gone; a subsequent `new` can create another copy. Retry an unconfirmed discard only with the same ID. Discard never undoes remote Git commits.

Commands in one live lease share their working directory, environment, shell
functions, aliases, private `/tmp` and background processes. `freshSandbox: true`
means this command started a new sandbox at the repository root. Timeout,
output-limit stop, shell exit, idle cleanup or lease replacement discards that
runtime state; repository files remain. The worker's required `idleUnitSeconds`
setting defaults to 1800 in the example configuration and accepts 1 through 86400.
Background processes share the sandbox's resource limits and stop with it. Their
late output is discarded, and host CLI operations require a current command.
The driver retains at most 128 output readers including the current command's
pair. Excess oldest readers close; a later background write can receive
`EPIPE`/`SIGPIPE`.

A command timeout preserves the current working copy after its process tree is confirmed stopped. The response reports timeout; earlier edits and any partial work from that command remain available under the same `copyId`. The next command gets a fresh `/tmp`. Disk-copy retention is independent of transport and runtime-lease closure. After an interruption, inspect the retained command and write state before retrying; expiry and explicit disposal remove local work.

Use `repo_exec` for file listings, search, reads and edits, then the `poketto` CLI for persistence. Read relevant repository-owned `AGENTS.md` files progressively. Standalone `list_directory`, `get_file` and `repo_patch` calls are not supported, including when the worker is disabled. The [CodeAct entrance record](../notes/implemented/2026-09-10-codeact-mcp-entrance.md) defines this boundary; the shared directory reader still serves browser navigation.

Oversized MCP bodies receive 413 before tools run; transport errors contain protocol fields rather than exception internals. Request and concurrency limits are defined in the [integration record](../notes/implemented/2026-09-05-local-execution-supervisor.md#mcp-and-java-integration).

`repo_exec` requires explicit `EXECUTE_REPOSITORY` capability and `POKETTO_EXECUTOR_ENABLED=true`. Configure `POKETTO_EXECUTOR_SOCKET`, `POKETTO_EXECUTOR_SIGNING_KEY` and `POKETTO_EXECUTOR_STAGING_DIRECTORY` on the Linux application, then install and verify the separate root supervisor and unprivileged SRT account as described by the [worker reference](../executor-service/README.md). Defaults admit two sessions and 128 MiB bundles; align application admission and export bounds with the worker and measure production limits before use.

Full-read execution sessions retain authorized current files and original Git history; public-only sessions receive a fresh current-public projection without original history or private metadata. Authorized clients of one account share a disk copy within the same workspace and reading scope; full-source and public projections remain separate. Ordinary edits stay local. `poketto save` commits selected files and explicit deletions through the shared atomic writer while retaining unselected edits; `poketto sync` reconciles one file against its own baseline, and `poketto recover` reconciles a pending save or move without replaying newer edits. Cancellation, revocation and failed renewal close execution authority. A missing worker, mismatched CodeAct protocol or unsupported isolation cannot fall back to an ordinary subprocess.

`poketto media import` stores a workspace-owned immutable original and updates its local logical index; save that index with referring text to persist the references. `poketto media link PATH --asset ID --revision REV` attaches an already uploaded original to that local index without transferring its bytes. `poketto media fetch` uses the local index or an explicitly selected historical commit in full-read sessions, and the host-owned approved mapping in public sessions. CLI paths are repository-relative; use `poketto --help` for commands and file lifetime. The [worker reference](../executor-service/README.md) owns limits, permissions, conflict behavior and coordinated worker installation.

`poketto move SOURCE DESTINATION` moves saved files, directories and indexed media,
repairing Markdown references in one remote commit. Unselected local edits and
unsaved index entries remain local. Dirty selected files and occupied destinations
are refused. If the commit succeeds but local installation is pending, use
`poketto recover` before another save, move or sync; it retains the original
operation and preserves edits made after an acknowledged local installation.
If local changes prevent installation, `poketto recover --skip-local` confirms
the remote move and keeps local files untouched. It releases the pending move
without advancing file baselines; use `poketto sync` on affected paths before
saving them.

`poketto media list` discovers indexed media without fetching bytes. It includes
unsaved imports in full-read sessions; public sessions use only the host-owned
approved mapping. Use `--prefix` to filter paths and continue pages with the
returned `nextOffset` and `indexVersion`. Full readers can select `--commit` for a
historical index. Metadata is checked against original storage when fetched.
Keep `--prefix` and `--commit` unchanged between pages; restart at offset zero
when changing the selection. Historical listing shares original-read concurrency
limits and can return `MEDIA_UNAVAILABLE` while that capacity is occupied.

`poketto artifact create FILE --type MIME` retains an immutable, temporary result
for the originating MCP session. `get_artifact` renders validated raster images
or returns text/binary pages; long command output also supplies artifact handles.
Handles expire after five minutes or session closure and do not upload, save or
publish files. Resource limits and cancellation close the session, so
long output then has only its preview and an explicit artifact-unavailable error.
The [worker reference](../executor-service/README.md#returned-artifacts)
defines quotas, byte paging and authorization.

## Deployment

Every verified `main` commit publishes separate Spring and frontend images from the same source commit. Copy the files under `deploy/` and a filled-in `.env.example` as `.env` into the host's deployment root. Supply the private domain/DNS configuration, one-time owner initialization token, repository/database credentials, separate data directories and four image pins. Run `deploy.sh --app-image <application-image> --app-revision <commit> --frontend-image <frontend-image>`; later runs without options redeploy the recorded pins. Both application revision labels must match, and PostgreSQL/Caddy references must carry registry digests.

For an operator-owned Compose installation, [existing-installation delivery](../notes/implemented/2026-09-08-existing-installation-delivery.md) updates app/frontend images and explicitly supplied identity settings. Install the current protected updater and select `POKETTO_DEPLOY_LAYOUT=existing`. All three `POKETTO_DEPLOY_MODE` values apply: `pull` has the host fetch both digests from the canonical registry using the deployment job's package-read token, `mirror` uses a configured delivery mirror, and `transfer` streams a checksummed archive over SSH for hosts that reach neither registry. Compose files, environment files, unrelated settings and dependencies remain operator-owned.

`transfer.sh --existing --set-stdin` accepts newline-separated `KEY=value` entries for `POKETTO_RESEND_API_KEY`, `POKETTO_EMAIL_FROM`, `POKETTO_EMAIL_DAILY_LIMIT`, `POKETTO_GOOGLE_CLIENT_ID`, `POKETTO_GOOGLE_CLIENT_SECRET`, `POKETTO_SUPPORT_EMAIL` and the five settings in the [GitHub App configuration guide](github-app.md). It sends these only to the privileged updater's standard input; registry credentials go only to the pull helper. Values are literal, including `$` and quotes. The mode-0600 `.deployment/images.json` overlay retains omitted settings. Explicit empty values clear settings. For manual deployment, configure identity settings on the host. Before enabling CI deployment, configure the Resend key, Google credentials and GitHub App settings as GitHub secrets, and the sender, daily limit and support email as GitHub variables. GitHub then owns these settings in both layouts: CI forwards empty values too, so absent or removed GitHub settings clear host values. An absent daily limit resets to 100. Clear both Google fields together; either deployment layout rejects an incomplete pair. Include the overlay last in manual Compose commands. After an interruption, retry the same images and configuration; a changed candidate is refused.

Set `POKETTO_SUPPORT_EMAIL` to the public contact shown on `/privacy` and `/terms`; review those pages for the installation's actual data handling. Both deployment layouts accept this setting, and existing-installation delivery sends it only to the frontend. CI takes it from the repository variable of the same name. For Google branding, use the site's homepage, `/privacy` and `/terms` URLs.

Caddy owns public HTTPS, forwards `/api` and `/mcp` to Spring and other paths to Next.js, and blocks the management entrance. Success requires healthy containers plus the local certificate-verified website and API. HTTPS checks retry within the remaining `POKETTO_HEALTH_TIMEOUT` deadline (default 180 seconds) while certificates and routes become ready. `deploy/transfer.sh` transfers both application images when the host cannot reach GHCR; the host still needs Docker Hub access or the exact cached database/gateway digests. Its `--pull --sync` mode synchronizes current stack files while acquiring application images on the host. Automatic deployment stays separately enabled through the production environment. The host executor is installed and tested independently before setting `POKETTO_EXECUTOR_ENABLED=true`; missing isolation prerequisites fail closed. See the [stack delivery record](../notes/implemented/2026-09-05-blog-stack-delivery.md) for image identity, configuration, persistence and remaining real-installation acceptance.

Set `POKETTO_NETWORK_SUBNET` to an unused RFC1918 IPv4 CIDR with at least 16 addresses, and `POKETTO_NETWORK_DYNAMIC_RANGE` to a canonical strict subpool with at least eight addresses. Set `POKETTO_GATEWAY_INTERNAL_IP` to Caddy's fixed address outside that pool, excluding the subnet's network, first usable bridge and broadcast addresses. Deployment rejects invalid ranges before starting containers; Docker allocates the other services only from the dynamic pool. This deployment alone enables Tomcat forwarding and trusts only that gateway `/32`; Caddy rebuilds client address, protocol and host headers and removes `X-Forwarded-Port` before Spring. Other entry points explicitly default to `server.forward-headers-strategy=none`. `./gradlew proxyForwardingCheck` requires Docker and Python 3.10+ and verifies actual Compose address allocation plus real per-client and shared-account login limits; it is mandatory in `check` and CI.

## Diagnostics

Every request and every MCP tool call leaves one record. A request record names the method, route, status, duration, caller kind and subject, and the workspace when the route selects one. A tool record names the tool, its duration, and the same outcome code the caller received, so a reported `SESSION_REPLACED` or `EXECUTION_REFUSED` can be looked up rather than reconstructed. Refused requests additionally record the status and title the caller was told.

Records carry a request identifier that stays on the server. It joins the several lines one request produces and is never returned to a caller: an identifier nobody outside can redeem adds no diagnostic value and invites a client to invent a use for it. Correlate a reported failure by workspace, caller and time instead.

What never reaches a record: request bodies, which carry repository tokens and passwords; MCP tool arguments, which carry commands and content; query strings and repository file paths; and document content. An admin route is reduced to its stable shape with the workspace identifier moved to its own field, and opaque route segments are collapsed: a UUID becomes `:id` and a long URL-safe run becomes `:opaque`. That keeps image grants, which authorize the exact image they name, out of the record; a public site slug is kept as authored. Container health probes are not recorded at all.

Changes to who can do what are recorded separately under the logger name `poketto.audit`, each naming an action such as `member.access.granted` or `key.revoked`, the actor who decided it, the subject, and the capabilities that actually apply afterwards. A suspension or downgrade is recorded as `member.access.revoked` rather than as a grant. Records are written after the change commits. Authentication outcomes are recorded there too, so a rejected credential is distinguishable from a rejected authorization. Login names, passwords, tokens and invitation codes never appear; a refusal carries this service's own fixed reason, not the submitted value.

The supplied deployment emits one JSON record per line, with each field addressable and stack traces inside the record rather than spread over many lines. `POKETTO_LOG_FORMAT` selects the format and defaults to `ecs`. Development without the deployment keeps the readable format by default.

Every service writes to the host journal. A container log lives and dies with its container, and this deployment replaces containers on each verified commit, so the record of whatever went wrong just before would otherwise be gone. The journal also already holds the executor's own records, which puts the application and its sandbox on one timeline. Give journald an explicit budget, because its default is a share of the filesystem rather than a size you chose:

```sh
sudo mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]
Storage=persistent
SystemMaxUse=1G
MaxRetentionSec=30day
RateLimitIntervalSec=0
'   | sudo tee /etc/systemd/journald.conf.d/poketto.conf
sudo systemctl restart systemd-journald
```

The gateway records what never reaches the application, in both its access log and the process log that carries reverse-proxy failures. Query strings are removed from its records because repository paths travel there, image addresses are skipped because they authorize the image they name, and credential headers are dropped together with the referring address, which carries the administration page's own path, and the download disposition header, which names the file. Rate limiting is disabled deliberately: a dropped record makes a reader conclude that nothing happened, which is worse than a slow query. Read one service with `journalctl CONTAINER_NAME=<container> -o cat`, which yields the record itself; add `| jq` when structured output is enabled. Select the security history with `journalctl -o cat | jq 'select(.log.logger=="poketto.audit")'`.

An operator-owned Compose installation does not receive these files through image delivery. Applying them there means editing that installation's own Compose configuration and gateway file: set each service's logging driver to `journald`, set the application's `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, and add the gateway access log together with the rule that skips image addresses, which authorize the image they name. Until that is done the service keeps running and keeps recording, in the readable format, into logs that a redeployment discards.

See the [diagnostics record](../notes/implemented/2026-09-14-service-diagnostics.md) for what remains uncovered.

## OAuth connections

Set `POKETTO_OAUTH_ISSUER=https://your-domain.example` on the application, with no trailing slash, to enable OAuth. Use the same public origin in `POKETTO_SECURITY_ALLOWED_ORIGINS`. Without an issuer, OAuth discovery and authorization stay disabled and static API keys remain usable. PostgreSQL retains connection and token-digest state. Use the current gateway configuration so the authorization-server and protected-resource discovery URLs reach Spring; existing-layout deployments must update their operator-owned gateway and environment explicitly.

Add `https://your-domain.example/mcp` in the MCP client, choose OAuth and leave client ID/secret empty for dynamic registration. A browser-hosted client registers an HTTPS callback; a command-line client registers a loopback one such as `http://127.0.0.1:<port>/…`, which is accepted on any port, including a port the client learns only when it starts listening. Any other host must use HTTPS and is matched exactly. The client opens Poketto's login and consent page. Sign in, choose a space you belong to, verify the application's return address, choose the requested permissions, and allow the connection. Only permissions you hold can be delegated. Private reading, private writing and publishing are separate choices and start unchecked. The client receives credentials only after consent; the account password stays with Poketto.

Members can list and disconnect their own connections; owners can manage all connections in the space. Saving through isolated commands requires full-source reading plus the write permission for the affected content. Private reading and publication permit public saves without private writing. Without private reading, the execution projection is read-only even with publication permission; it omits source metadata and cannot safely overwrite the author's original.

Select “保持连接” to permit rotating refresh tokens. In administration, “已连接应用” lists permissions and expiry and can disconnect each application independently. Disconnecting revokes access, refresh and affected execution sessions. Connection authorization lasts at most ninety days; a client must request fresh consent after expiry or revocation. Unsupported clients can still send a static key in `Authorization: Bearer <key>`.

The [OAuth decision](../notes/implemented/2026-09-11-mcp-oauth.md) owns protocol bounds, admission limits and interoperability coverage. Public-client DCR and S256 PKCE are supported; confidential-client secrets and CIMD are not advertised. This is MCP authorization, not social login or open account registration.
