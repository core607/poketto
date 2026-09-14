# Search Highlights and Reading Return

Date: 2026-09-14

## Problem

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires visible matches and a return to the result that opened an article. Search pages preserved query and pagination in their URLs, but article links discarded that context. An explicit return link also needs the correct query, page and reading position.

## Decision

Result titles and visible-text snippets highlight literal, case-sensitive query occurrences, matching `DocumentSearch`. Every part renders as React text and matches use `mark`; neither query nor result text is interpreted as HTML or a regular expression. Article bodies and non-search listings retain their normal rendering.

Search result article links carry the query, numeric page offset and a `site` or `space` scope marker. Article pages construct the return destination from those validated fields and the article's public space. They do not accept an arbitrary return URL or a different space slug. The existing default-article redirect preserves only these validated fields. Direct article visits retain their normal space and collection entrances.

Each search history entry retains its result anchor and vertical offset in `history.state`, preserving Next.js state fields. Selecting a result updates that entry and passes an opaque entry ID through the article URL. A tab-local `sessionStorage` copy of at most 32 positions supports an explicit return to the same canonical query, page and space; it contains no article body and carries no content authority. Returning through browser history uses that entry's state first. An explicit return uses its matching entry copy, restores focus to the result heading and restores the saved offset after rendering. The ID belongs to the history entry and is reused when selecting another result from that entry. An explicit return creates a new history entry, which receives a distinct ID on its first result selection.

Positions never transfer to a different query, page or space. A missing result anchor, missing or evicted entry, disabled storage or a new browsing context leaves the ordinary query/page return link usable without promising exact scroll restoration. Page authorization is always checked by the existing public API; locally remembered positions cannot preserve access to withdrawn content.

## Alternatives and consequences

A raw `returnTo` URL would require trusting another navigation target. Structured fields constrain navigation to known search routes. A single position keyed only by query would mix separate visits to the same result page. Entry IDs preserve their independent histories, while the bounded tab-local copy bridges an explicit new navigation without guessing whether the previous history entry is safe to revisit.

Keeping only a fragment would restore an anchor but lose the reader's vertical position. Keeping only a pixel offset could land on an unrelated result after withdrawal; restoration therefore also requires the original result anchor. Position storage is disposable and never changes the search corpus or matching semantics.

This change applies to existing public search entrances. It does not turn the legacy site-search corpus into cross-workspace search or implement management search highlighting. Those accepted requirements remain under the parent proposal.

## Verification

- Nine focused frontend cases cover escaped literal highlights, structured return fields, query/page binding, history and explicit resume, the 32-entry storage limit, unavailable storage and rejecting cross-origin result targets. The full frontend formatting, type checks, 76 tests and production build pass at the browser source revision.
- A real Spring/PostgreSQL/Next.js/Caddy fixture supplies 28 matching documents. Page two shows results 13 through 24. Opening result 24 through the default article redirect and returning through Back or the explicit link restores its query, page, heading focus and vertical position. Space search retains its fixed scope; mismatched query/page state does not restore.
- Deleting result 24 through the authorized fixture changes the result set to 27 documents; the old resume entry neither focuses a different result nor prevents ordinary browsing.
- A fresh fixture at the same source revision verifies a 390-by-844 viewport, no horizontal overflow on search or article pages, and restored page-two focus and scroll after the mobile return. Original desktop and mobile screenshots are retained separately from product source. Storage-disabled behavior is covered by the focused frontend test, not browser mutation.

These local checks do not establish production HTTPS rollout or complete the cross-workspace site-search corpus.

The same-topic audit retains the parent proposal and [shared search rules](../implemented/2026-09-12-shared-checks.md) for corpus, matching and snippet bounds. The [browser interface](../implemented/2026-09-06-blog-browser-interface.md), [website delivery](../implemented/2026-09-14-workspace-public-delivery.md) and [logical routes](../implemented/2026-09-06-logical-repository-routes.md) retain their rendering, authorization and URI boundaries. [Collection reading](../implemented/2026-09-14-collection-reading.md) and [discovery batches](../implemented/2026-09-14-public-discovery-batches.md) retain independent authored-navigation and browsing-batch behavior. No record is archived or rejected; the parent proposal remains open for its other accepted requirements.
