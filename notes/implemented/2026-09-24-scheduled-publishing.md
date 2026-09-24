# Scheduled publishing

Date: 2026-09-24

## Problem

An author, or the author's agent over MCP, can prepare several articles at once, but each goes public the moment it lands in `public/` under an enabled policy. There was no way to say "not before Monday 09:00", and unknown frontmatter keys were ignored, so any attempt published immediately.

## Decision

**Format.** Markdown frontmatter accepts an optional `publish_at`, snake_case like `created_at`. [RepositoryMarkdownParser](../../src/main/java/io/github/core607/poketto/content/internal/RepositoryMarkdownParser.java) reads it with the other date keys:
- a `YYYY-MM-DD` date, read as 00:00 UTC;
- an offset timestamp such as `2026-10-01T09:00:00+08:00`.

An unparseable value makes the file `INVALID_MARKDOWN`, which keeps it out of the snapshot, so a mistyped schedule never publishes early.

**Gate.** `publish_at` only delays a file that is otherwise publishable: under `public/`, allowed by the [publication policy](2026-09-09-codeact-content-and-media.md), parsed and admitted by the snapshot. A file outside `public/` never becomes public through the key.

**Mechanism.** [JGitPublicContentSnapshots](../../src/main/java/io/github/core607/poketto/content/internal/JGitPublicContentSnapshots.java) installs a snapshot holding every publishable article, each carrying its `publishAt`. Every way out of the service presents a view filtered at the current clock instant:
- `current`;
- `withCurrent`;
- the snapshot returned by `refresh` or offline restoration.

In the view:
- `articles()` holds only due articles, and collections are rebuilt from them;
- `scheduled()` maps the repository paths of pending articles to their release instants, for authoring views only;
- the view is cached until the next release instant, so readers that compare two calls see one object until publication actually changes.

A same-commit renewal reuses the installed articles, and the filter runs at read time, so an article becomes public at its instant without a new commit. Every anonymous surface reads through this service, so all of them are covered: pages, listings, tags, archive, discovery, site search, sitemaps, RSS, covers, image grants, community targets and feeds, moderation review, public exports and the public-only MCP projection.

**Dates.** For a file under `public/` without `created_at` or `date`, `createdAt` is the `publish_at` instant rather than the first commit time, so a due article sorts as new. Without `updated_at`, `updatedAt` is the later of the last commit time and `publish_at`. On a private file the key changes nothing, dates included. A file under `public/` that the publication policy excludes is still dated from its `publish_at`, since the key states the author's intended release.

**Editor.** [PublicFilePresentation](../../src/main/java/io/github/core607/poketto/web/internal/PublicFilePresentation.java) reports `SCHEDULED` with the release instant for a file in the view's `scheduled()` map. The editor shows 「定时发布：<local time>」.

**Members.** Space members who can read the public scope see scheduled files before they are due in the studio tree and file reads, which follow the path policy as the [editor public page state](2026-09-14-editor-public-page-state.md) and member grants do. Members with public editing need the file to edit it. A public-only MCP copy is built from the filtered view, so it omits scheduled articles until they are due; a full-read copy contains them.

## Relationship to the rejected visibility flag

The [content contract](2026-09-09-codeact-content-and-media.md) rejects a metadata-only visibility flag because every file consumer, media included, would have to interpret it. `publish_at` is narrower:
- It cannot widen publication.
- Media become public only through a reference from a served article, so an image referenced only by a scheduled article gets no grant until the article is due.
- A consumer that bypasses the snapshot service can only publish content early, and only content the author already placed in the public root.

## Alternatives

- **A server-side scheduled move from `private/` to `public/`.** This keeps publication purely path-based, but Poketto would commit to the author's remote unattended and re-authorize a member whose grants may have changed. A release would pass silently if the host or the remote were down. A key inside `private/` would also let metadata widen publication.
- **Filtering when the snapshot is installed.** An unchanged commit reuses the previous article list, so a due article would wait for the next unrelated commit.
- **A schedule stored in PostgreSQL and set from the editor.** Agents working over MCP could not schedule through ordinary file edits, and the schedule would live outside the repository that holds the content.
- **Hiding scheduled files from public-scope members too.** Every path-policy read would need the snapshot's clock. Public editors could no longer see the file they are preparing.

## Consequences and risks

- The article list can change within one commit. Code that compares views, such as export fingerprints and discovery batches, treats a newly due article as a content change and refreshes or refuses as it does for a new commit. Within one commit the public set only grows, so the public MCP export keys its fingerprint cache by commit and public article count, and its final recheck compares both.
- Clock skew on the host shifts the release by the same amount.
- Site search rechecks the commit and the public article count after its bounded scan, so a search that spans a release asks for a retry instead of omitting the article that just became due.
- Git history, and members who read the public scope, see a scheduled article early. Only anonymous readers wait.
- An older server ignores the key and publishes at once. The key is documented only with the release that enforces it.

## Verification

[ScheduledPublishingTests](../../src/test/java/io/github/core607/poketto/content/internal/ScheduledPublishingTests.java) pins the read-time filter, release without a new commit, the derived dates and private-file immunity against a real Git fixture and a moving clock; [PublicFilePresentationTests](../../src/test/java/io/github/core607/poketto/web/internal/PublicFilePresentationTests.java) and [tests/scheduled-publishing.test.tsx](../../frontend/tests/scheduled-publishing.test.tsx) pin the editor state.
