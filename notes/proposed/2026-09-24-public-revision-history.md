# Public revision history

Date: 2026-09-24
Status: Proposed

## Problem

Every space is a Git repository, so an article's earlier versions exist, but readers only see the current text. Showing that an article was corrected, and how, is a trust signal that ordinary blogs cannot give. It is also dangerous. The [content contract](../implemented/2026-09-09-codeact-content-and-media.md) states that historical private bytes cannot be made safe by filtering, and [account working copies](../implemented/2026-09-14-account-working-copies.md) forbid public-only grants from obtaining history. [Browser history and restoration](../implemented/2026-09-23-browser-history-and-restoration.md) therefore added no public history endpoint.

## Proposal

**Opt-in.** The space's **Website** settings gain 「公开修订历史」, off by default and changeable only by an owner. With the setting off, no history request reaches Git. Turning it off hides every version at once. Authors who removed text by mistake can keep the setting off, because once on, removed passages become readable.

**Which versions exist.**
- A version is the public body of the current article's repository path at a first-parent commit of the served snapshot commit. The body is the text after frontmatter, as served.
- Walking backwards, a commit contributes a version only if the full publication rule holds for that path at that commit:
  - the policy is enabled;
  - the path is under `public/` and not excluded;
  - the file parses;
  - it is admitted without path or route collision;
  - its route equals the current route.
- The walk stops at the first commit where the rule fails or the path is absent. Text from before a private or unpublished period is never shown, even if the article was public even earlier.
- Consecutive commits with an identical body collapse into one version.
- Renames are not followed. A moved article starts a new history.

**Exposed data.**
- For each version: the commit time and the body.
- Never exposed: commit messages, author or committer names and emails, frontmatter, the repository path, or the commit id.
- Versions are numbered from oldest to newest.

**Reads and bounds.**
- History reads anchor on the served snapshot commit through `readImmutableObjects`, without fetching.
- They follow the admin history budgets: 256 commits scanned, 50 versions, 1 MiB per body, a two-second deadline, and a `Semaphore(2)` answering 429 when busy.
- After the work, the snapshot commit and the website switch are checked again, as [workspace public delivery](../implemented/2026-09-14-workspace-public-delivery.md) requires.

**Pages.**
- `/s/{slug}/history/{route}` lists versions by date. Selecting two versions shows a line diff using [lib/source-diff.ts](../../frontend/lib/source-diff.ts).
- The article footer links there with 「修订历史 · N 个版本」 when more than one version exists.
- History pages are `noindex` and are not listed in sitemaps.

## Alternatives

- **All commits of the path, filtered by the current rules.** This would show text written while the file was private or excluded.
- **Recording published versions at snapshot installation.** It shows only versions that were actually served, but misses history before the feature and duplicates Git in PostgreSQL. The publication rule at each commit is the recorded decision the author made in Git.
- **Following renames or frontmatter `id` across paths.** Rename similarity cannot distinguish moves from rewrites, a point the community decision also records. Following `id` requires scanning whole trees per commit.
- **Showing history by default.** It would expose passages that authors deliberately removed from sites created before this feature.

## Consequences and risks

- A version can be publishable at a commit yet never served, because refresh samples every 30 seconds and the website switch keeps no history. Those versions appear anyway: at that commit the author had placed the text in the public root with publication enabled.
- An evicted repository cache makes history temporarily unavailable. The page shows a retry message and does not fetch.

## Verification plan

- Git fixture tests:
  - a private period stops the walk;
  - excluded and colliding commits end history;
  - identical bodies collapse;
  - a route change ends history;
  - a disabled setting refuses without reading Git;
  - no commit metadata appears in responses.
- Frontend:
  - diff rendering between two versions;
  - the footer link threshold;
  - `noindex`.
