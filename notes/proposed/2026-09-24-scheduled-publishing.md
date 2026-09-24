# Scheduled publishing

Date: 2026-09-24
Status: Proposed

## Problem

An author, or the author's agent over MCP, can prepare several articles at once, but each goes public the moment it lands in `public/` under an enabled policy. There is no way to say "not before Monday 09:00". Today the parser ignores unknown frontmatter keys, so writing such a key publishes immediately.

## Proposal

**Format.** Markdown frontmatter gains an optional `publish_at` key, snake_case like `created_at`. It accepts the same values as the other date keys in [RepositoryMarkdownParser](../../src/main/java/io/github/core607/poketto/content/internal/RepositoryMarkdownParser.java): `YYYY-MM-DD`, read as 00:00 UTC, or an offset timestamp such as `2026-10-01T09:00:00+08:00`. An unparseable value produces the existing `INVALID_MARKDOWN` diagnostic and drops the article, so a mistyped schedule never publishes early.

**Gate.** `publish_at` only delays a file that is otherwise publishable: under `public/`, allowed by the [publication policy](../implemented/2026-09-09-codeact-content-and-media.md), and parsed and admitted by the snapshot. Before the instant, the article is absent from every anonymous surface. At the instant it appears without a new commit. A file outside `public/` never becomes public through this key.

**Mechanism.**
- The snapshot keeps scheduled articles with their due instant.
- `PublicContentSnapshots.withCurrent` and `current` present only articles due at the current clock instant. This covers pages, listings, tags, archive, discovery, site search, sitemaps, RSS, covers, image grants, community targets, feeds, moderation review, public exports and the public-only MCP projection, which all read the snapshot.
- The filtered view is cached until the next due instant, so collections and sorting are rebuilt only when an article becomes due.
- Same-commit renewal in `JGitPublicContentSnapshots.installAcknowledged` reuses the stored list, so the time filter must sit at read time, not at installation.
- The path-policy reads that serve members without `READ_PRIVATE` (`AuthorizedRepositoryReader` and the asset and media equivalents) apply the same gate. Otherwise those members see the article early.

**Dates.** When `created_at` and `date` are absent, `createdAt` is `publish_at` rather than the first commit time, so a due article sorts as new.

**Editor.** `PublicFilePresentation` gains a `SCHEDULED` state carrying the due instant. The editor shows 「定时发布：<local time>」 and the diagnostics explain an invalid value. The [editor public page state](../implemented/2026-09-14-editor-public-page-state.md) continues to derive availability from the exact commit plus the current snapshot.

**Deployment order.** An older server ignores `publish_at` and publishes immediately. The usage docs and the content template's `AGENTS.md` should mention the key only after the gate is deployed.

## Relationship to the rejected visibility flag

The [content contract](../implemented/2026-09-09-codeact-content-and-media.md) rejects a metadata-only visibility flag because every file consumer, media included, would have to interpret it. `publish_at` is narrower:
- It cannot widen publication.
- Media stay public only through a reference from a currently served article, so an image referenced only by a scheduled article gets no grant until the article is due.
- A consumer that ignores the key can only publish content early, and only content the author already placed in the public root.

The implementation must list and cover every such consumer, as the Mechanism section does. It must also add a cross-link from the content contract to this record.

## Alternatives

- **A server-side scheduled move from `private/` to `public/`.** This keeps publication purely path-based. However, Poketto would then commit to the author's remote unattended, re-authorizing a member whose grants may have changed. A due time passes silently if the host or the remote is down. And a key inside `private/` would let metadata widen publication.
- **Filtering only when the snapshot is installed.** An unchanged commit reuses the previous article list, so a due article would wait for the next unrelated commit.
- **A schedule stored in PostgreSQL and set from the editor.** Agents working over MCP could not schedule through ordinary file edits. The schedule would also live outside the repository that holds the content.

## Consequences and risks

- The article list can change within one commit. Code that compares article lists, such as the public export fingerprint and `JGitRepositorySnapshotExports`, must treat a newly due article as a content change.
- Clock skew on the host shifts the release by the same amount.
- Git history records the article before it is due. Anyone with repository read access sees it early; that access already implies private reads.

## Verification plan

- Parser: accepted date and timestamp forms; an invalid value yields a diagnostic.
- Snapshot: with an injected clock, an article is absent before its instant and present after, with no reinstall.
- Surfaces: listings, search, the sitemap and a cover request for a scheduled route return not-found before the instant.
- A public-scope member read is refused before the instant.
- Editor state is `SCHEDULED`.
