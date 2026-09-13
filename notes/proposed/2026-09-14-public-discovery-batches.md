# Public Discovery Batches

Date: 2026-09-14

## Decision

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires stable random browsing across enabled spaces. A server-issued batch retains card metadata and immutable page identities, never document bodies or authority. The homepage redirects to a URL containing its batch ID; pagination preserves that ID. An explicit new-batch link selects and shuffles another bounded sample.

A batch samples up to four documents per space from up to 32 enabled spaces. A continuation batch advances the catalog keyset, returning to the beginning after its final page. Unavailable or expired repository snapshots contribute no cards; HTTP requests never fetch remote Git. This is a discovery sample, not an exhaustive site-search index.

Before emitting each retained card, check current website permission and the exact current page commit and route. Withdrawal or snapshot replacement removes that card without shifting the other batch positions. A page can therefore contain fewer items or be empty. Tokens expire after 30 minutes or earlier on oldest-batch eviction; unknown tokens return 410 and the UI offers an explicit new batch rather than silently replacing one. Application restart also discards these disposable batches.

Retain at most 256 batches and 8 MiB of card string data, with at most 128 cards per batch. Count and text limits bound metadata separately; the text limit is not an exact Java heap measurement. At most two builds run concurrently; excess requests receive 429. This does not establish total public-snapshot cache capacity, which remains a separate requirement.

## Alternatives and remaining work

Reshuffling on every page would repeat or skip cards. Storing document bodies in each batch would duplicate repository snapshots and retain unnecessary content after withdrawal. Reusing stored metadata without current permission checks would expose withdrawn cards.

The initial entrance identifies each space and links articles and folder pages. Author display metadata, album classification and thumbnails, collection sequence, complete site search and reading-return state remain required by the parent contract. This record remains proposed until the batch entrance is verified in the running frontend and backend.

The same-topic audit retains the parent multi-user proposal and the [website delivery boundary](../implemented/2026-09-14-workspace-public-delivery.md). Their product scope and authorization decisions remain applicable.
