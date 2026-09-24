# Development and operations

Runtime requirements, content configuration, MCP access and deployment for Poketto.

[Project overview](../README.md) · [中文说明](usage.zh.md)

## Community

The **Community** page at `/community` shows updates from followed spaces, private
bookmarks, your likes, notifications, followed spaces and blocked accounts.
COMMUNITY, CREATOR and ADMINISTRATOR accounts can like, bookmark, follow, comment and
reply through a browser session. VIEWER accounts can read public discussion, remove
their own records, mark notifications read, report comments and manage blocks.
Workspace membership and API keys do not grant these interactions.

An article accepts interactions only when its frontmatter `id` is a unique lowercase
canonical UUID. New browser drafts include one; for an existing article, choose
**Enable article interactions** in the editor, then save and publish. A malformed
`id` must be corrected in source. Keep the ID when renaming or moving an article and
generate a new one when copying it; changing the ID starts a new interaction history, and restoring the old ID reconnects it.
Articles without a valid ID stay readable, and their space can still be followed.

Article pages show **阅读 N**, the anonymous readers of that route, once N is at least
one. A reader counts after five seconds of visible reading, once per client, article
and UTC day; crawlers and non-public routes are not counted, client addresses are
never stored, and a moved article starts a new count. See
[public view counts](../notes/implemented/2026-09-24-public-view-counts.md).

Signed-in community members can propose a correction with **建议修改** below a public
article: edit the body, optionally give a reason of up to 500 characters, and choose
whether to be credited. A proposal is refused if the article changed since the page
loaded, if it changes nothing, if the proposer and a space owner have blocked each
other, or while the proposer has another open proposal for the article; the limits
are 5 a minute and 30 a day. Members holding `PUBLISH` review proposals as a line diff
under **读者勘误** in the studio. Accepting replaces only the body, and only while the
remote file still has the body the proposal started from; otherwise the proposal
becomes stale. While an acceptance runs, declining or withdrawing returns a conflict; one unsettled after ten minutes counts as open again. A credited proposer is thanked in the article footer and named by
account id in a `Poketto-Suggested-By` commit trailer. Owners and proposers are
notified, proposers can withdraw open proposals, and resolved proposal text is
cleared after 90 days. See [reader corrections](../notes/implemented/2026-09-24-reader-corrections.md).

Comments are plain text of up to 4,000 Unicode code points, with one level of
replies. Deleting your comment cannot be undone; a root with replies becomes a
tombstone that accepts no new replies. Space owners and site administrators can
remove comments, and removing a root hides its replies. Administrators handle reports
in the studio's **Site administration** section, which grants no access to private
Git content. A report reason holds at most 1,000 code points.

Blocking an account hides its comments and notifications from you and prevents
replies between you; it does not hide public articles. A root comment notifies up to
100 space owners and a reply notifies the root author. Each inbox keeps its latest
1,000 notifications. Bookmarks and follows are private and do not notify the owner.
There are no direct messages or email alerts. When an article is withdrawn,
restricted or removed, its public discussion and cards are hidden but the records
remain. A downgraded account cannot add interactions; its existing comments stay.

Each account keeps at most 1,000 likes, 1,000 bookmarks, 100 followed spaces and 500
blocks. Per minute and per UTC day, an account can post 10 and 300 comments, make 60
and 3,000 likes, bookmarks and follows, file 5 and 30 reports, and add 30 and 500
blocks. A following-feed scan is bounded to 100,000 documents and five seconds, two at
a time; when refused, retry later or follow fewer spaces.

Public community reads use `/api/public/community/spaces/{slug}` and its
`/articles/{articleId}` child; browser mutations and private lists use
`/api/auth/community` with the session, Origin and CSRF checks and bodies up to
64 KiB. A comment request carries a fresh `requestId`; reuse it to retry an uncertain
request without posting twice. A list page whose records are hidden can be empty and
still carry a cursor. The [community decision](../notes/implemented/2026-09-23-community-interactions.md)
owns identity, visibility and transaction boundaries.

## Development

Build requirements and commands are in [AGENTS.md](../AGENTS.md#commands). On
Windows, use `.\gradlew.bat` and set variables through `$env:...`; `check` runs the
Linux executor tests and `linuxStorageTest` in a pinned Linux container. Use the
[isolated browser entrance](../acceptance/README.md) to exercise the real application
with synthetic data; frontend settings are in [frontend/README.md](../frontend/README.md).

The application needs PostgreSQL, an absolute `POKETTO_DATA_DIR` and one
pre-provisioned private HTTPS Git repository. Set `SPRING_DATASOURCE_URL`, database
credentials, `POKETTO_REPOSITORY_REMOTE_URI`, `POKETTO_REPOSITORY_USERNAME` and
`POKETTO_REPOSITORY_PASSWORD` before `bootRun`. The default workspace follows that
repository's `main`; its checkout below `<data-dir>/workspaces/<workspace-id>/content`
is a disposable cache. Optional settings:

- `POKETTO_REPOSITORY_CACHE_MAX_WORKSPACES` (default 32) and
  `POKETTO_REPOSITORY_TIMEOUT_SECONDS` (default 30).
- `POKETTO_REPOSITORY_REFRESH_SECONDS` (default 30): how often served content is
  re-validated against remote `main`. A valid direct push becomes visible after the
  next refresh; writes through Poketto are visible immediately.
- `POKETTO_REPOSITORY_STALE_AFTER_SECONDS` (default 3600; at most 3600 and not below
  the refresh interval): how long served content may go unvalidated before readiness
  reports out of service and public reads fail closed. The process keeps running and
  retrying.

`GET /actuator/health` serves deployment checks, and `GET /api/public/documents`
lists the default workspace's public documents.

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto \
POKETTO_REPOSITORY_REMOTE_URI=https://git.example.com/owner/private-content.git \
POKETTO_REPOSITORY_USERNAME=operator \
POKETTO_REPOSITORY_PASSWORD=... \
./gradlew bootRun
```

Start the application once to create the schema, then run
`./deploy.sh --initialize-admin` in an interactive terminal on the deployment host and
enter a username and hidden password. It creates the first site administrator and
default-workspace owner once and cannot replace existing accounts; there is no
browser setup endpoint. For custom container installations, see
[operator administrator setup](../notes/implemented/2026-09-11-operator-administrator-setup.md).
Set `POKETTO_SECURITY_ALLOWED_ORIGINS` to the exact browser origin; local HTTP also
needs `POKETTO_SESSION_COOKIE_SECURE=false`. Clients fetch `/api/auth/csrf` before
login and send its named CSRF header with the session cookie.

Guests register with a verified email, password and display name, or with Google when
configured. Existing accounts keep username/password login and can bind a verified
email. An invitation grants its stated space membership, not a higher account group.
A browser stays signed in across restarts and deploys until 90 days pass without use
(`poketto.security.account-session-idle-days`), the account signs out, its password
changes or it is suspended; a session that never signs in expires after 30 idle
minutes.

Set `POKETTO_RESEND_API_KEY` and `POKETTO_EMAIL_FROM`, whose domain must be a verified
sending domain, to enable email verification and password recovery. Six-digit codes
expire after ten minutes, allow five failed attempts and work once; resending waits
sixty seconds. `POKETTO_EMAIL_DAILY_LIMIT` (default 100) caps sends per UTC day, on top
of per-email and per-address limits. Password recovery invalidates the account's
browser sessions and machine credentials but keeps workspace membership. Rotating the
Resend key expires outstanding codes.

Set `POKETTO_GOOGLE_CLIENT_ID` and `POKETTO_GOOGLE_CLIENT_SECRET` to enable Google
login. Register a Google Web application with the redirect URI
`<POKETTO_OAUTH_ISSUER>/api/auth/identity/google/callback`, where
`POKETTO_OAUTH_ISSUER` is the exact HTTPS origin without a path (loopback HTTP for
local development), and request only `openid`, `email` and `profile`. A matching email
does not merge accounts: sign in and link Google explicitly. The last login method
cannot be removed. An unconfigured provider shows no login button.

New accounts are **viewers**. Administrators assign one group: viewer, community
member, creator or site administrator. Creators and administrators can connect
repositories and publish websites. Site administrators search accounts, change
groups with a reason, see the change history and owned spaces, and review
repository-public articles and media without private access. The last administrator
cannot be demoted. A space's website is public only while every owner is a creator or
administrator: demoting an owner hides its public routes, discovery, search, feeds and
media until eligibility returns, while editing and member and MCP permissions
continue.

On Linux, the data directory must support file and directory synchronization;
without it, publication-affecting writes and image uploads are refused and public
service closes. On Windows, public snapshots are held in memory only, so after an
offline restart public reads stay closed until remote verification succeeds.

## Content and images

The management page lists the signed-in account's spaces; select one before editing.
The studio's **Account** section connects an existing private GitHub or CNB
repository, retries an interrupted creation and accepts invitations. Provider tokens
need metadata read and Git write access. Set `POKETTO_REPOSITORY_CREDENTIAL_KEY` to a
Base64-encoded 32-byte secret before enabling repository connection. New spaces start
with the website disabled.

With the GitHub App configured ([configuration guide](github-app.md)), creators and
administrators authorize their personal GitHub account in account settings, then
install the App through **Manage GitHub repository access**; a selected-repository
installation needs at least one existing repository, such as an empty private one.
Confirm the account and an unused name to create the space's private repository. If
the App lacks access to it, select it in the installation settings and continue the
same request. Reconcile an uncertain creation in the request history before starting
another; the browser never resends an uncertain remote write.

To restore a GitHub App connection, the owner who supplied the original authorization
restores it in account settings, selects the repository through **Manage GitHub
repository access**, then uses **Verify and restore connection** in the space's
**Storage** section, giving the current name if it was renamed. This works after a
group downgrade too. A transferred repository or a new one with the old name cannot
replace the original. If short-lived verification expires while an operation waits,
retry it; GitHub authorization is not needed again.

Set the App's webhook URL to `/api/hooks/github` on the public HTTPS origin and its
secret to `POKETTO_GITHUB_WEBHOOK_SECRET`, and subscribe to Repository events.
Revocation stops the affected repository access without deleting content or
membership; only an explicit reconnection restores it. After an outage, redeliver
failed notifications from GitHub; an already processed delivery answers 409.

Owners of token connections replace the Git username and token in **Storage**; the
server validates them first, and the repository address cannot change. Revoke the old
token at the provider after a confirmed update. Deployment-managed repositories need
an operator configuration change.

Private HTTP routes use `/api/admin/workspaces/{workspaceId}` and never fall back to a
default space; `GET /api/auth/workspaces` lists memberships, and `/mcp` takes its space
from the credential. See [workspace routing](../notes/implemented/2026-09-11-workspace-browser-and-mcp-routing.md).

Owners grant private reading, private writing and public editing/publishing
separately, in member management or on an invitation. Invitations default to
public-scope reading only; private writing requires private reading. Members can read
the public scope even while the website is disabled. Reducing grants revokes
connections that exceed them; increasing grants does not widen existing connections.
See [member content permissions](../notes/implemented/2026-09-12-member-content-permissions.md).

Creating a space commits the [content template](../content-template/AGENTS.md) to an
empty repository: a root guide, `private/` and `public/` trees with their own guides,
and a disabled publication policy. For a repository that already has content, or when
that commit did not complete, the space's **Storage** section adds only the missing
template files. The same section adds template sets for journal, reading notes,
albums and news digest, each a folder pair under `private/` and `public/` with guides
that tell agents how to name, structure and publish that content; a set only creates
absent files. See [space templates](../notes/implemented/2026-09-24-space-templates.md).

Create content under `private/`. To publish, move it and its media into `public/` and
enable `.poketto/publishing.yaml`:

```yaml
enabled: true
mode: public-root
exclude:
  - public/drafts/**
```

Only paths below the exact root `public/` are eligible. Exclusions use full
repository-relative paths and win; `AGENTS.md` in any letter case and paths with a
hidden segment stay private. A missing or disabled policy publishes nothing; an
invalid policy closes public service. **Publish** and **Withdraw to draft** in the
editor move a saved note between matching private and public paths; they refuse
private dependencies and collisions, and do not enable a disabled website or override exclusions or account restrictions. Move the
containing folder when media must travel with a note.

Markdown metadata is optional, and unchanged source bytes are preserved. Default routes
omit `public/` and `.md`; an explicit frontmatter route cannot grant publication. A
folder's `index.md`, or its `README.md` when no valid index exists, is the folder
landing and takes the folder's route (`public/index.md` owns `/`). A landing shows the
folder's own images as a gallery of thumbnails; an image without one, such as a WebP
over four million pixels, still opens its original. See
[album thumbnails](../notes/implemented/2026-09-14-album-thumbnails.md).

To schedule a publishable article, set `publish_at` to `YYYY-MM-DD` (00:00 UTC) or an
offset timestamp such as `2026-10-01T09:00:00+08:00`. Until then the article is absent
from every anonymous surface, including search, feeds, covers and community, and it
appears at that instant without a new commit. Without `created_at` or `date`, its date
is `publish_at`. An unparseable value reports `INVALID_MARKDOWN` and keeps the article
unpublished. Members see the file early, marked **定时发布** in the editor. See
[scheduled publishing](../notes/implemented/2026-09-24-scheduled-publishing.md).

Owners can turn on **公开修订历史** in the website settings. Article footers then link
**修订历史** at `/s/{slug}/history/{route}`, which lists the article's public bodies
oldest first and compares any two. History reaches back only while the article
stayed publishable at the same path and route; commit ids, messages, identities and
frontmatter are never shown. A read covers at most 256 commits, 50 versions and 2 MiB
of bodies within two seconds; beyond that, the page says earlier versions are not
listed. See [public revision history](../notes/implemented/2026-09-24-public-revision-history.md).

Public pages, cards and search results show the frontmatter `public_author`,
otherwise the space's public signature, otherwise the space name. `public_author` and
the signature are single-line text of at most 120 code points. In the studio's
**Website** section, a signed-in owner sets the signature, renames the space (1–120
characters, `PUT …/publication/name`) and sets a description of at most 280 code
points shown on the space site (`PUT …/publication/description`); both take
`{ "text": … }`. Other metadata such as `author`, account details and Git commit
identities stay private.

Article pages estimate reading time at about 400 CJK characters or 200 other words a
minute, and list headings as in-page links when an article has at least three at its
two shallowest levels (h1 to h3). Fenced code blocks that name a language, such as
```` ```ts ````, are highlighted; the language is never guessed. Math uses doubled
dollars only: `$$x^2$$` inline, and `$$` on lines of their own or a ```` ```math ````
block for display; a single `$` stays text. A ```` ```mermaid ```` block is drawn as a
diagram. The editor preview uses the same rules. See
[reading aids](../notes/implemented/2026-09-24-reading-aids.md).

Search matches titles and parsed reading text literally, including link labels,
image descriptions, code, table cells and referenced footnotes, but not link
destinations or raw HTML. Site search at `/search` covers every enabled website and
`/s/{slug}/search` one space. A site search fails as a whole, rather than returning
partial results, when a snapshot is unavailable or its bounds (256 spaces, 100,000
documents, five seconds) are exceeded; retry, or search one space. See
[public site search](../notes/implemented/2026-09-14-public-site-search.md).

**New note** and **New folder** prepare a draft under `private/`, keeping the selected
category; **Save** writes it, a folder as its `index.md`. **File history**, for
members with private-read permission, lists changes to the exact path without
following renames. **Restore to editor** needs write permission and only replaces the
editor text; **Save** then commits it and still refuses concurrent changes. Deleted, binary, oversized
and managed-media versions cannot be restored. **Search filenames** matches
repository-relative paths literally, and public-only members see only
publication-eligible paths. A scan over more than 100,000 combined Git tree and indexed media entries is refused rather than answered with partial counts. See [content navigation](../notes/implemented/2026-09-14-admin-content-navigation.md),
the [history record](../notes/implemented/2026-09-23-browser-history-and-restoration.md)
and [filename search](../notes/implemented/2026-09-14-administration-filename-search.md).

Unsaved text is kept in this browser as plaintext recovery data per account, space,
path and tab: it does not sync, clearing browser data loses it, and anyone with access
to the browser profile can read it. **Local drafts** lists recoverable records;
restoring never saves, and saving still checks the original revision. Rely on recovery
only after the retained-draft notice appears. The browser holds at most 20 drafts and
2 MiB across accounts and spaces, 1 MiB per draft, and never evicts one silently; free
a full store from the other account or space, or by clearing site data, which removes
all drafts. Saving, discarding and logging out clear the corresponding drafts.

Paste or drop one PNG, JPEG, WebP or GIF image of up to 16 MiB into the source editor
to upload it and insert a reference; this requires private-write permission and
neither saves nor publishes. Members without private-read permission insert existing
public images with **Choose public images**. Moving a directory includes its indexed
media and repairs Markdown references in the same commit; moving one document leaves
shared dependencies in place.

Managed originals live under `<data-dir>/managed-originals` and are retained;
`<data-dir>/derived/repository-images` is disposable. Public image URLs last at most
five minutes and stop working when the website is disabled or its snapshot changes;
reload the page for current URLs. See the
[website delivery boundary](../notes/implemented/2026-09-14-workspace-public-delivery.md)
and the [foundations record](../notes/implemented/2026-09-05-repository-authoring-foundations.md).

`POST /api/admin/workspaces/{workspaceId}/media` stores raw originals up to 128 MiB
with an `Idempotency-Key` and optional `X-Media-Type`; `poketto.assets.max-file-bytes`
lowers the bound without affecting existing originals. Image uploads to
`/api/admin/workspaces/{workspaceId}/assets` also need an `Idempotency-Key` and take up
to 16 MiB. Uploading neither writes the media index nor publishes. The
[logical media index](../notes/implemented/2026-09-09-logical-media-index.md)
and [indexed media delivery](../notes/implemented/2026-09-09-indexed-media-delivery.md)
records own how indexed media is listed, saved with text and delivered.

Relative links to indexed MP3, WAV, MP4 or WebM originals show native players,
without autoplay or preload, beside a download link. Import them as `audio/mpeg`,
`audio/wav` (or `audio/wave`, `audio/x-wav`), `audio/mp4`, `video/mp4`, `audio/webm` or
`video/webm`. Codec support depends on the browser, and bytes a browser already
buffered cannot be recalled after access ends. See
[playback limits and rationale](../notes/implemented/2026-09-23-controlled-media-playback.md).

Signed-in owners turn the website on or off in the space's **Website** section, or with
`PUT /api/auth/workspaces/{workspaceId}/publication`, the session CSRF token and
`{ "enabled": true }` or `{ "enabled": false }`; after an uncertain response, read the
state with `GET` before retrying. Turning it off does not affect members' repository
access. Each enabled website lives at `/s/{slug}` with its own search, tags, archive,
`/read/...` pages and an RSS feed of its latest 30 records at `/s/{slug}/rss.xml`;
`/api/public/spaces/{slug}` endpoints are scoped to that space, and unknown or
disabled slugs return 404. A newly enabled site can stay unavailable until background
refresh reaches it.

`/sitemap.xml` indexes the root site and every enabled space, and returns 503 rather
than an incomplete list when a snapshot is unavailable. `/robots.txt` uses
`POKETTO_PUBLIC_URL` and discourages crawling `/admin`, `/api/` and homepage batch
addresses. Each public article has a stable cover address, `/s/{slug}/cover/{route}`,
used by link previews: it serves the first public inline image's thumbnail, redirects
to the site's `/share.png` when there is none, and answers 404 once the article is no
longer public.

The homepage samples enabled spaces into a browsing batch that keeps its order for up
to 30 minutes; **New batch** reshuffles, and an expired link offers a new one. A batch
takes at most four cards from each of up to 32 spaces, so it is not exhaustive. Each
space's cards favor an authored `featured: true` article, a recent one and tag
variety, then random content; private reading history and follow lists are never
used.
**Discover by tag** filters by a complete, case-sensitive tag of up to 64 characters.
Links in a folder landing form a collection read in authored order with
previous/next links. See [discovery batches](../notes/implemented/2026-09-14-public-discovery-batches.md),
[authoring and discovery](../notes/implemented/2026-09-23-authoring-and-discovery-experience.md)
and [collection reading](../notes/implemented/2026-09-14-collection-reading.md).

## Capture inbox

A capture creates one new private note directly in `private/inbox/` from a link, a
selected passage, a note and an optional image, named `YYYY-MM-DD-HHmm-<title>.md` in
UTC with `-2`, `-3` … added when the name is taken. Its frontmatter holds a fresh
`id`, `title`, `source` and `saved`; the passage becomes a block quote, and an image is
stored as a managed original and linked. Nothing existing is read back, changed or
published. Each account may capture 30 times a minute and 500 times a UTC day, across
all its keys and the browser.

- **Phone.** A key with only the `CAPTURE` capability posts to `POST /api/capture` with `Authorization: Bearer <key>`, as JSON (`title`, `url`, `text`, `note`) or as a multipart form that may add a file field `image` of up to 16 MiB. The key's own space receives the note, and the answer is `201` with `{ "path", "commit" }`. The space's **AI assistant** section issues such a key to an owner and lists the iOS Shortcut steps. OAuth connection tokens are refused here; they belong to `/mcp`.
- **Browser.** The same section offers a bookmarklet that opens `/capture` as a popup with the page's title, address and selection. The popup saves with the signed-in session through `POST /api/admin/workspaces/{id}/capture`; opening it never writes.

Only a holder with private writing can be granted `CAPTURE`, and private writing
includes it. It cannot read, overwrite, move, delete or publish anything, or create
files outside the inbox. See [capture inbox](../notes/implemented/2026-09-24-capture-inbox.md).

## Export HTTP interface

In the editor, use **Export** beside a file or folder, or the file sidebar's action
for the whole workspace, choose a private or public copy, and download the ZIP. It
holds the latest saved content, not unsaved edits. A public copy refuses private
selections rather than publishing them. A private copy requires private-read
permission and keeps source frontmatter. Packages contain no Git history, runtime
guides or internal media index, and relative links point to the media inside the ZIP.

On native Linux, `POST /api/admin/workspaces/{workspaceId}/exports` takes `paths` and a
required `publicOnly` boolean and returns a temporary handle, ZIP size, SHA-256 and
expiry; `GET …/exports/{handle}` downloads it, `/metadata` reads its receipt, and
`POST …/exports/{handle}/release` releases it early. A handle works only for its owner
and workspace, until it expires.

Packages are staged under `<data-dir>/portable-exports/<workspace-id>`. One build and
two downloads (one per workspace) run at a time, and a package holds at most 512 MiB of
originals, 256 MiB of text and 10,000 entries. Properties under `poketto.exports` set
`max-zip-bytes` (800 MiB), `max-retained-bytes` (2 GiB), `max-workspace-bytes`
(1600 MiB), `max-packages` (8), `lifetime-seconds` (600) and `build-seconds` (120).
Exhausted capacity returns 429; a missing, expired or foreign handle returns 404; a
filesystem without POSIX permissions returns 503. See the
[export decision](../notes/implemented/2026-09-10-portable-content-exports.md).

In a CodeAct session, run `poketto export PATH... --output FILE [--public]`; `.`
selects the visible workspace, and public-only sessions always export the public
projection. The ZIP holds saved content, not local edits, and never overwrites a
different existing file. Retrieve it with
`poketto artifact create FILE --type application/zip` and `get_artifact` when it fits
the artifact limits. `MATERIALIZE_CAPACITY` keeps the session and files: free local
space, select fewer files, or use browser export.

## MCP and isolated execution

`/mcp` serves MCP over Streamable HTTP with a workspace Bearer credential (API key or
OAuth access token), independently of browser sessions. The catalog always contains
`get_asset` and `put_asset`; with the executor enabled it also contains `repo_exec`,
`repo_discard` and `get_artifact`. There are no standalone file tools: use `repo_exec`
for listings, search, reads and edits, the `poketto` CLI to persist changes, and read
the repository's `AGENTS.md` files as you go. See the
[CodeAct entrance record](../notes/implemented/2026-09-10-codeact-mcp-entrance.md).

`repo_exec` requires the `EXECUTE_REPOSITORY` capability and
`POKETTO_EXECUTOR_ENABLED=true`. Configure `POKETTO_EXECUTOR_SOCKET`,
`POKETTO_EXECUTOR_SIGNING_KEY` and `POKETTO_EXECUTOR_STAGING_DIRECTORY` on the Linux
application, then install and verify the root supervisor and unprivileged SRT account
as the [worker reference](../executor-service/README.md) describes. The application
admits four sessions (`POKETTO_EXECUTOR_MAX_SESSIONS`) and 128 MiB bundles by default;
keep these within the worker's limits. A missing worker or unsupported isolation never
falls back to an ordinary subprocess.

Request bodies are limited to 128 KiB (413 before any tool runs), a command to 16,384
characters and an encoded bridge frame to 512 KiB. Refusals carry `code` and
`reason`. See [MCP request admission removal](../notes/implemented/2026-09-15-mcp-request-admission-removal.md).

`repo_exec` requires `expectedCopyId`: pass `"new"` to open the account's default
copy, creating it only when absent, then pass the returned `copyId`. Reconnecting
reattaches the copy, and closing the transport keeps it. Each successful operation
renews its seven-day idle deadline (`retention.expiresAt`); expiry and discard remove
local work. Clients of one account share the copy within a workspace and reading
scope. `SESSION_REPLACED` and `EXECUTION_REFUSED` mean the command did not run.
`EXECUTION_UNCONFIRMED` means it may have partly run, including remote writes: do not
replay it; inspect the same copy with a read-only command,
`retention.lastInterruptedCommand` and `poketto status`, and use `poketto recover` when
a remote save needs reconciliation. See
[working-copy identity](../executor-service/README.md#working-copy-identity).

Commands in one live lease share the working directory, environment, shell functions,
aliases, private `/tmp` and background processes. A timeout, output-limit stop, shell
exit, idle cleanup or lease replacement discards that runtime state but keeps the
copy's files, including partial work; the next command reports `freshSandbox: true`.
Idle cleanup follows the worker's `idleUnitSeconds` (1 to 86400; 1800 in the example
configuration). Background processes stop with the sandbox and cannot run `poketto`
operations after their command ends.

Full-read sessions hold authorized current files and Git history. Public-only
sessions get the current public projection without history or private metadata,
report `PUBLIC_PROJECTION` with a synthetic commit, and cannot save.

`poketto edit PATH --old TEXT --new TEXT` replaces one exact occurrence in an existing
local text file, refusing missing or ambiguous text. `poketto create PATH --text TEXT`
creates a file only when the path is absent. For long text, `create` accepts `--stdin`
or `--text-file FILE`, and `edit` accepts `--old-file FILE` for `--old` and
`--new-stdin` or `--new-file FILE` for `--new`. Input is UTF-8 and keeps final
newlines; input files resolve from the shell's current directory, while target paths
stay repository-relative. These options do not raise the command or frame bounds.
Both commands recheck the local bytes before writing, unlike plain shell writes, and
change nothing remote until `poketto save`.

```sh
poketto create private/article.md --stdin <<'MARKDOWN'
# Article

Quotes, `$variables` and backticks remain ordinary Markdown.
MARKDOWN
```

`poketto save` commits selected files and explicit deletions, keeping other edits
local, and checks each selected file against the remote. In full-read copies an
acknowledged save or move also updates local Git HEAD; `repo_exec.commit` and
`poketto status.gitCommit` report that baseline. If status shows
`localBaselinePending`, run `poketto recover` instead of saving again.

`poketto status` in full-read copies compares remote `main` with the last confirmed
base: `remote.state` is `MATCHES_BASE`, `DIFFERS_FROM_BASE` or `UNAVAILABLE`. Status
never changes files. `poketto sync` merges the whole workspace with one remote
revision, keeping local-only files and conflicting binary bytes and marking
overlapping text edits with LOCAL/BASE/REMOTE sections; it saves nothing remotely.
After an interruption, status shows `syncPending`; `poketto recover` continues and
`poketto recover --skip-local` releases the operation without undoing installed
files. See [workspace synchronization](../notes/implemented/2026-09-15-workspace-synchronization.md).

`poketto move SOURCE DESTINATION` moves saved files, directories and indexed media
and repairs Markdown references in one remote commit; dirty selected files and
occupied destinations are refused. If local installation is then pending, run
`poketto recover` before another save, move or sync. If local changes block it,
`poketto recover --skip-local` accepts the remote move without touching local files;
run `poketto sync` before saving affected paths.

`repo_discard` with the exact `expectedCopyId` deletes the copy and its unsaved work.
It needs execution permission and ownership of the copy; busy copies are refused.
`DISCARDED` or `ABSENT` confirms the copy is gone, after which `new` creates another.
Retry an unconfirmed discard only with the same ID. Discarding never undoes remote
commits.

`poketto media import` stores an immutable original and updates the local media
index; save the index with the referring text to persist the reference.
`poketto media link PATH --asset ID --revision REV` adds an already uploaded original.
`poketto media fetch` and `poketto media list` read the local index or, in full-read
sessions, a historical `--commit`; public sessions see only approved media. Page
`media list` with the returned `nextOffset` and `indexVersion`, keeping `--prefix` and
`--commit` unchanged. Historical listing can return `MEDIA_UNAVAILABLE` while
original reads are busy. See `poketto --help` and the
[worker reference](../executor-service/README.md) for limits and conflicts.

`poketto artifact create FILE --type MIME` keeps an immutable, temporary result that
`get_artifact` returns to any MCP session of the same account, workspace and reading
scope, as a rendered image or text or binary pages; long command output also supplies
artifact handles. Handles expire after five minutes or when the lease closes, for
example when another credential of the account takes over the copy. See
[returned artifacts](../executor-service/README.md#returned-artifacts).

`put_asset` imports an image and returns `assetId`, `revision`, `reference`,
`mediaType` and `size`; link it with `poketto media link` and save the text and
`.poketto/assets.json`. Uploading alone neither saves nor publishes. Each call takes
an `operationKey`, reused for identical retries, and one source:

- `url`: a public HTTPS image address on port 443, fetched within three redirects,
  30 seconds and 16 MiB, without cookies or authorization headers.
- `file`: a platform file object with `download_url` and `file_id`. The tool declares
  `_meta["openai/fileParams"] = ["file"]`; forwarding depends on the client.
- `mode: "upload"` with no source, when the client holds the file. The response
  supplies `uploadUrl`, `method: "PUT"`, `contentType: "application/octet-stream"`,
  `maxBytes` and `expiresAt`; upload the raw bytes from the client's environment:

```python
import requests
with open(image_path, "rb") as image:
    response = requests.put(upload_url, data=image,
                            headers={"Content-Type": "application/octet-stream"}, timeout=30)
response.raise_for_status()
receipt = response.json()
```

The upload URL is a secret grant bound to the caller, workspace and operation key,
based on `poketto.oauth.issuer`. It expires after 15 minutes or an application
restart; an account holds at most 64 grants, 8 of them unfinished. The server stops
reading a body after 30 seconds, so set a client timeout. GET the URL to recover a
lost receipt (`UPLOAD_PENDING` until an upload completes). After expiry, request a new
grant with the same operation key and resend identical bytes; different bytes
conflict. Retry `TRANSFER_BUSY` with the same grant; `IMAGE_MEMORY_BUSY` means the
image memory budget is occupied.
See [MCP image transfers](../notes/implemented/2026-09-15-mcp-image-transfers.md).

## Deployment

Every verified `main` commit publishes separate Spring and frontend images from the
same source commit. Copy the files under `deploy/` and a filled-in `.env.example` as
`.env` into the host's deployment root, with the domain, repository and database
credentials, separate data directories and four image pins. Run
`deploy.sh --app-image <application-image> --app-revision <commit> --frontend-image <frontend-image>`;
later runs without options redeploy the recorded pins. Both application revision
labels must match, and PostgreSQL and Caddy references must carry registry digests.
Then create the first administrator with `./deploy.sh --initialize-admin`.

For an operator-owned Compose installation,
[existing-installation delivery](../notes/implemented/2026-09-08-existing-installation-delivery.md)
updates only the app and frontend images and explicitly supplied identity settings.
Install the current protected updater and set `POKETTO_DEPLOY_LAYOUT=existing`.
`POKETTO_DEPLOY_MODE` is `pull` (the host fetches both digests from the canonical
registry with the deployment job's package-read token), `mirror` (a configured
delivery mirror) or `transfer` (a checksummed archive over SSH, for hosts that reach
neither registry).

`transfer.sh --existing --set-stdin` accepts newline-separated `KEY=value` entries for
`POKETTO_RESEND_API_KEY`, `POKETTO_EMAIL_FROM`, `POKETTO_EMAIL_DAILY_LIMIT`,
`POKETTO_GOOGLE_CLIENT_ID`, `POKETTO_GOOGLE_CLIENT_SECRET`, `POKETTO_SUPPORT_EMAIL` and
the five [GitHub App settings](github-app.md), passing them only to the privileged
updater's standard input. Values are literal, including `$` and quotes. The mode-0600
`.deployment/images.json` overlay keeps omitted settings, and an explicit empty value
clears one; include the overlay last in manual Compose commands. For manual
deployment, configure identity settings on the host. Before enabling CI deployment,
store the Resend key, Google credentials and GitHub App settings as GitHub
secrets and the sender, daily limit and support email as GitHub variables. GitHub then
owns them in both layouts: absent or removed settings clear host values, and an absent
daily limit resets to 100. Clear both Google fields together. After an interruption,
retry with the same images and configuration.

Set `POKETTO_SUPPORT_EMAIL` to the public contact shown on `/privacy` and `/terms`, and
review those pages for the installation's actual data handling. For Google branding,
use the site's homepage, `/privacy` and `/terms` URLs.

Caddy serves public HTTPS, forwards `/api`, `/mcp` and the OAuth discovery paths under
`/.well-known/` to Spring and other paths to Next.js, and blocks `/actuator`.
Deployment succeeds once the containers are healthy and the certificate-verified
website and API respond within `POKETTO_HEALTH_TIMEOUT` (default 180 seconds). When
the host cannot reach GHCR, `deploy/transfer.sh` transfers both application images;
the host still needs Docker Hub or the exact cached database and gateway digests, and
`--pull --sync` synchronizes the stack files while the host pulls. Automatic
deployment is enabled separately through the production environment. Install and test
the host executor before setting `POKETTO_EXECUTOR_ENABLED=true`. See the
[stack delivery record](../notes/implemented/2026-09-05-blog-stack-delivery.md).

Set `POKETTO_NETWORK_SUBNET` to an unused RFC1918 IPv4 CIDR with at least 16
addresses, `POKETTO_NETWORK_DYNAMIC_RANGE` to a canonical strict subpool with at least
eight, and `POKETTO_GATEWAY_INTERNAL_IP` to Caddy's fixed address outside that pool,
excluding the subnet's network, first usable and broadcast addresses. Only this
deployment trusts forwarded headers, and only from that gateway; other entry points
use `server.forward-headers-strategy=none`.

## Diagnostics

Every request and every MCP tool call leaves one record: method, route, status,
duration, caller kind and subject, and workspace for requests; tool, duration and the
outcome code the caller received for tool calls. Records carry a request identifier
that is never returned to callers, so correlate a reported failure by workspace,
caller and time. Records never contain request bodies, tool arguments, query strings,
repository file paths, document content or image grants; container health probes are
not recorded.

Permission changes and authentication outcomes are recorded under the logger name
`poketto.audit`, naming the action (such as `member.access.granted` or
`key.revoked`), the actor, the subject and the resulting capabilities. Login names,
passwords, tokens and invitation codes never appear.

The supplied deployment writes one JSON record per line; `POKETTO_LOG_FORMAT` selects
the format (default `ecs`). Every service writes to the host journal, which survives
container replacement. Give journald an explicit budget:

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

The gateway logs requests that never reach the application, without query strings,
image addresses, credential headers, referrers or download file names. Read one
service with `journalctl CONTAINER_NAME=<container> -o cat` (add `| jq` for
structured output) and the security history with
`journalctl -o cat | jq 'select(.log.logger=="poketto.audit")'`.

An operator-owned Compose installation does not receive these settings through image
delivery: set each service's logging driver to `journald`, the application's
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, and the gateway access log with its rule that
skips image addresses. Until then, logs stay readable text that a redeployment
discards. See the [diagnostics record](../notes/implemented/2026-09-14-service-diagnostics.md).

## OAuth connections

Set `POKETTO_OAUTH_ISSUER=https://your-domain.example`, with no trailing slash, to
enable OAuth, and use the same origin in `POKETTO_SECURITY_ALLOWED_ORIGINS`. Without
an issuer, OAuth stays disabled and static API keys remain usable. The gateway must
route the OAuth discovery URLs to Spring; existing-layout deployments update their own
gateway and environment.

Add `https://your-domain.example/mcp` in the MCP client, choose OAuth and leave the
client ID and secret empty. A browser-hosted client registers an HTTPS callback; a
command-line client registers a loopback one such as `http://127.0.0.1:<port>/…` on
any port; other hosts must use HTTPS and match exactly. On the consent page, sign in,
choose a joined space, verify the return address, choose permissions and allow the
connection. You can delegate only permissions you hold; private reading, private
writing and publishing start unchecked.

Saving through isolated commands requires full-source reading plus write permission
for the affected content: private reading with publishing can save public files
without private writing, and without private reading the projection is read-only.
Members disconnect their own connections and owners any in the space. “保持连接”
permits rotating refresh tokens; “已连接应用” lists each connection's permissions and
expiry and disconnects it, revoking its tokens and execution sessions. Authorization
lasts at most ninety days. Clients without OAuth can send a static key as
`Authorization: Bearer <key>`.

Public-client dynamic registration with S256 PKCE is supported; confidential-client
secrets and CIMD are not advertised. The [OAuth decision](../notes/implemented/2026-09-11-mcp-oauth.md)
owns protocol bounds and interoperability.
