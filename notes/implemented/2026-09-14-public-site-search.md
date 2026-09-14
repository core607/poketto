# Search Across Published Spaces

Date: 2026-09-14

## Problem

The [multi-user contract](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md) requires site search across enabled public spaces. The site search page previously called the default workspace's document list, leaving other published spaces absent from search. Different spaces may also publish the same route, so a route alone cannot identify a search result or its return anchor.

## Decision

`GET /api/public/search` searches every currently enabled website's verified public snapshot. Space search keeps its existing space-specific endpoint. The default document, tag and archive endpoints retain their default-workspace scope. Requests never fetch remote Git, read private documents or enumerate raw media as search results.

The service uses the shared literal title and parsed reading-text matcher and match-centered snippets. Results sort by creation time descending, then public space slug and route. Each result carries the space slug, public display name, document summary and resolved public author. Counts and offsets describe the combined corpus. Stable inputs produce stable pagination; later publication changes may change the result set. Existing query, offset, scope, return-anchor and scroll behavior remains applicable.

Each request reads the complete enabled-space catalogue in bounded pages. It admits at most two simultaneous searches, 256 spaces, 100,000 documents and 64 Mi UTF-16 source characters, and checks a five-second processing deadline between documents and catalogue reads. Exceeding a bound refuses the whole response rather than sampling spaces or reporting a partial total. An unavailable or expired snapshot likewise refuses the query. The browser explains that site search is temporarily unavailable and offers retry; space search remains available independently.

Matching and snippet work occur outside publication installation locks. Before returning, the service rechecks each selected workspace's current commit and website state, and rechecks the enabled-space catalogue and public presentation fields. A changed corpus refuses that response. These are workspace-level checks, not a cross-workspace Git transaction or a guarantee against changes after a response has been delivered. No search-result cache grants ongoing publication authority.

The UI links every result and tag to its own space and includes that space in the result key and anchor. Search-return scope remains the site when a result from any space is opened. Only existing structured search parameters build the return link; no arbitrary return URL is accepted.

## Alternatives and consequences

Reusing discovery's sampled batches would silently omit spaces and articles and cannot satisfy search. A persistent search index or a retained, cursor-driven query session could reduce repeated scans for a larger installation, but introduces invalidation or expiry state. The existing bounded snapshot scan supports complete search within stated limits and explicitly refuses excess work. It does not silently select a smaller corpus or present an incomplete count as complete.

One unavailable published workspace temporarily blocks combined search. This keeps the completeness contract explicit; readers can still search another available space directly. The operational limits do not change valid content bounds or the storage format.

## Verification

Focused search tests cover complete catalogue traversal beyond discovery's space limit, combined ordering and pagination, duplicate routes, capacity refusal, unavailable snapshots and changed publication state. Real Spring/PostgreSQL HTTP queries cover two independent enabled spaces and scoped search through the production controllers.

A real Spring/PostgreSQL/Next/Caddy browser run searches two synthetic spaces with 28 matching articles each. A mixed results page retains each space's canonical article and tag links, public identity and independent result anchor. Literal title and snippet matches render as text marks, and a query containing SVG syntax remains text. Browser Back and the explicit return link restore the site query, page and selected result; direct space search remains scoped. The mobile page has no horizontal overflow at a 390-pixel CSS viewport. This local fixture does not establish production HTTPS, external MCP behavior or provider interoperability.

The same-topic audit retains [website delivery](../implemented/2026-09-14-workspace-public-delivery.md), [discovery batches](../implemented/2026-09-14-public-discovery-batches.md), [reading text](../implemented/2026-09-12-shared-checks.md), [public authorship](../implemented/2026-09-14-public-author-names.md), and [search return](../implemented/2026-09-14-search-highlights-and-reading-return.md) as independent owners. The parent remains proposed for its remaining requirements.
