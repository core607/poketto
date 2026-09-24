# Public Discovery Batches

Date: 2026-09-14

## Decision

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires stable random browsing across enabled spaces. A server-issued batch retains card metadata and immutable page identities, never document bodies or authority. The homepage answers at `/`, so crawlers see content rather than a redirect, and a client script then writes the new batch ID into the address bar; pagination preserves that ID. Without scripts, a reload starts a new batch. An explicit new-batch link selects and shuffles another bounded sample.

A batch samples up to four documents per space from up to 32 enabled spaces. A continuation batch advances the catalog keyset, returning to the beginning after its final page. Unavailable or expired repository snapshots contribute no cards; HTTP requests never fetch remote Git. This is a discovery sample, not an exhaustive site-search index.

[Authoring and discovery](2026-09-23-authoring-and-discovery-experience.md) replaces
uniform selection with authored choices, recency, tag diversity and random exploration,
and binds an explicit tag filter to the batch. The replay and capacity rules below remain.

Before emitting each retained card, check current website permission and the exact current page commit and route. Withdrawal or snapshot replacement removes that card without shifting the other batch positions. A page can therefore contain fewer items or be empty. Tokens expire after 30 minutes or earlier on oldest-batch eviction; unknown tokens return 410 and the UI offers an explicit new batch rather than silently replacing one. Application restart also discards these disposable batches.

Retain at most 256 batches and 8 MiB of card string data, with at most 128 cards per batch. Count and text limits bound metadata separately; the text limit is not an exact Java heap measurement. At most two builds run concurrently; excess requests receive 429. This does not establish total public-snapshot cache capacity, which remains a separate requirement.

## Alternatives and verification

Reshuffling on every page would repeat or skip cards. Storing document bodies in each batch would duplicate repository snapshots and retain unnecessary content after withdrawal. Reusing stored metadata without current permission checks would expose withdrawn cards.

[Public signatures](2026-09-14-public-author-names.md), [collection reading](2026-09-14-collection-reading.md), [album thumbnails](2026-09-14-album-thumbnails.md) and [album and collection cards](2026-09-14-discovery-album-cards.md) extend the cards; [site search](2026-09-14-public-site-search.md) provides complete cross-space search, and [public search return](2026-09-14-search-highlights-and-reading-return.md) result-position restoration.

`PublicDiscoveryTests` and `SpacePublicationIntegrationIT` pin batch replay, mixing of independent repositories, private-content exclusion, withdrawal without shifting positions and tag binding; `frontend/tests/pagination-pages.test.tsx` covers expiry recovery. Large-catalog capacity is not measured.
