# Public revision history

Date: 2026-09-24

## Problem

Every space is a Git repository, so an article's earlier versions exist, but readers only see the current text. Showing that an article was corrected, and how, is a trust signal that ordinary blogs cannot give. It is also dangerous. The [content contract](2026-09-09-codeact-content-and-media.md) states that historical private bytes cannot be made safe by filtering, and [account working copies](2026-09-14-account-working-copies.md) forbid public-only grants from obtaining history. [Browser history and restoration](2026-09-23-browser-history-and-restoration.md) therefore added no public history endpoint.

## Decision

**Opt-in.** The space's website settings have 「公开修订历史」, stored as `workspaces.public_history`, off by default and changeable only by an owner through `PUT /api/auth/workspaces/{id}/publication/history`. With the setting off, a history request answers 404 before any snapshot or Git read. Turning it off hides every version at once. Once on, removed passages become readable, which the confirmation dialog states.

**Which versions exist.** [JGitPublicRevisionHistory](../../src/main/java/io/github/core607/poketto/content/internal/JGitPublicRevisionHistory.java) walks first parents from the served snapshot commit. A commit contributes the article's body only while all of these hold at that commit:
- the publishing policy is enabled and permits the article's repository path, so `public/`, exclusions and private segments are applied as at that commit;
- the path is a regular file of at most 1 MiB that decodes and parses;
- its route equals the current route;
- its `publish_at`, if any, is not after the time of the read.

The walk stops at the first commit where any condition fails or the path is absent. Text from before a private, excluded, scheduled or unpublished stretch is never shown, even if the article was public earlier. A stop at a disabled policy, an excluded or absent path, another route or a future release is the author's decision, so the history is complete. A stop at a file over 1 MiB or one that does not parse may hide earlier public text, so the history is marked incomplete. Consecutive commits with the same body collapse into one version dated by the earliest of them, so frontmatter-only edits add no version. Renames are not followed; a moved article starts a new history.

Path and route collisions with other files are not checked. They are not the author's choice about visibility, and detecting them needs every public file of every commit parsed. A version whose file collided at that commit is therefore listed although it was not served then, like versions that the 30-second refresh never sampled.

**Exposed data.** Each version carries only the commit time and the body after frontmatter. Commit ids, messages, author and committer identities, frontmatter and the repository path never leave [PublicRevisionHistory](../../src/main/java/io/github/core607/poketto/content/PublicRevisionHistory.java).

**Reads and bounds.** `GET /api/public/spaces/{slug}/history?route=…` is served by [PublicHistory](../../src/main/java/io/github/core607/poketto/web/internal/PublicHistory.java). It takes the current snapshot, finds the served article by route, and walks through `readImmutableObjects` without fetching.
- At most two reads run at once; a third answers 429.
- A read scans at most 256 commits, keeps at most 50 versions and 2 MiB of UTF-8 bodies, and stops after two seconds. A bound that stops the walk returns the versions found with `complete: false`. The JSON response can exceed 2 MiB only by string escaping.
- A commit object over 1 MiB fails the read.
- After the walk, the website switch and the history setting are checked again, and the answer stands only if the same snapshot commit still serves the route. Otherwise the request fails as repository-unavailable and the reader retries.

**Pages.** `/s/{slug}/history/{route}` lists the versions oldest first and compares two of them line by line with [lib/source-diff.ts](../../frontend/lib/source-diff.ts). The diff always runs from the earlier to the later version, so a removed passage is never shown as added. The page is `noindex` and absent from sitemaps. While the setting is on, every article footer links 「修订历史」 without a version count, because counting would walk Git on every article view.

## Alternatives

- **All commits of the path, filtered by the current rules.** This would show text written while the file was private or excluded.
- **Recording published versions at snapshot installation.** It shows only versions that were actually served, but misses history before the feature and duplicates Git in PostgreSQL. The publication rule at each commit is the recorded decision the author made in Git.
- **Following renames or frontmatter `id` across paths.** Rename similarity cannot distinguish moves from rewrites, a point the community decision also records. Following `id` requires scanning whole trees per commit.
- **Showing history by default.** It would expose passages that authors deliberately removed from sites created before this feature.
- **A version count in the footer.** It needs the walk on every article page, or a cache keyed by snapshot commit and route; the plain link needs neither.

## Consequences and risks

- A version can be publishable at a commit yet never served: refresh samples every 30 seconds, the website switch keeps no history, and collisions are not checked. Those versions appear anyway, because at that commit the author had placed the text in the public root with publication enabled.
- A space with no earlier public version still shows the footer link; the page then says the body has not changed since publication.
- An evicted repository cache makes history temporarily unavailable. The page shows a retry message and does not fetch.
- Version numbers count from the oldest version returned, so an incomplete history numbers from its oldest listed version.

## Verification

[JGitPublicRevisionHistoryTests](../../src/test/java/io/github/core607/poketto/content/internal/JGitPublicRevisionHistoryTests.java) pins where history stops, version collapsing and completeness against in-memory Git histories; [PublicHistoryTests](../../src/test/java/io/github/core607/poketto/web/internal/PublicHistoryTests.java) pins the disabled-setting 404 before any read, the exposed fields and the moved-snapshot discard; [revision-history.test.tsx](../../frontend/tests/revision-history.test.tsx) and [space-publication.test.tsx](../../frontend/tests/space-publication.test.tsx) pin the page and the confirmed setting change.
