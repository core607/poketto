# Collection Reading

Date: 2026-09-14

## Decision

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires authored collection order and explicit reading context. A navigation index is derived once per verified public snapshot from folder landing documents. Resolve links with the same repository-path rules used by rendered Markdown. Ignore external links, fragments alone, unavailable/private destinations and the landing page itself; keep each eligible article once, in first-link order. Retain route/title references, not copied article bodies.

An article response supplies its collection memberships and immediate neighbors in each collection. The browser selects context only from a matching membership identified by a `collection` query parameter. A direct entry lists memberships without choosing one. Landing-page article links and previous/next links carry that context. The final entry explicitly ends the sequence. Author text stays unchanged; only navigation is derived. A landing that exceeds Markdown traversal or reference bounds reports unavailable navigation without making unrelated articles unavailable. Its response still carries the original body; unresolved link, download, image and gallery mappings are empty, and gallery status is unavailable. Invalid or oversized document input remains rejected.

Current public snapshots and website authorization own the index lifetime and visibility. No additional cache or remote fetch is introduced. [Album entrances](2026-09-14-album-entrances-and-lightbox.md) owns folder fallback to README, [thumbnail delivery](2026-09-14-album-thumbnails.md) owns album previews, and [public signatures](2026-09-14-public-author-names.md) owns attribution. [Public search return](2026-09-14-search-highlights-and-reading-return.md) restores search context independently of authored collection navigation.

## Alternatives and verification

Inferring a collection from a path would lose curated cross-directory order and guess incorrectly for articles in multiple collections. Parsing every collection on each article request would repeat work across up to a full workspace; deriving the index with the snapshot shares its existing lifetime.

`SpacePublicationIntegrationIT` verifies authored order, duplicate and fragment links, private and missing targets and multiple memberships over real PostgreSQL and HTTP.

[Reading aids](2026-09-24-reading-aids.md) owns the rule that hides an opening heading repeating the page title. [Discovery batches](2026-09-14-public-discovery-batches.md), [website delivery](2026-09-14-workspace-public-delivery.md) and [logical routes](2026-09-06-logical-repository-routes.md) keep their scope, publication checks and URI encoding.
