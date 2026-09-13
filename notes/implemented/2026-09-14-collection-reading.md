# Collection Reading

Date: 2026-09-14

## Decision

The [multi-user reading contract](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md) requires authored collection order and explicit reading context. A navigation index is derived once per verified public snapshot from folder landing documents. Resolve links with the same repository-path rules used by rendered Markdown. Ignore external links, fragments alone, unavailable/private destinations and the landing page itself; keep each eligible article once, in first-link order. Retain route/title references, not copied article bodies.

An article response supplies its collection memberships and immediate neighbors in each collection. The browser selects context only from a matching membership identified by a `collection` query parameter. A direct entry lists memberships without choosing one. Landing-page article links and previous/next links carry that context. The final entry explicitly ends the sequence. Author text stays unchanged; only navigation is derived. A landing that exceeds Markdown traversal or reference bounds reports unavailable navigation without making unrelated articles unavailable. Its response still carries the original body; unresolved link, download, image and gallery mappings are empty, and gallery status is unavailable. Invalid or oversized document input remains rejected.

Current public snapshots and website authorization own the index lifetime and visibility. No additional cache or remote fetch is introduced. Folder fallback to README, album previews, author metadata and search-return scroll state remain required by the parent contract and are outside this implementation slice.

## Alternatives and verification

Inferring a collection from a path would lose curated cross-directory order and guess incorrectly for articles in multiple collections. Parsing every collection on each article request would repeat work across up to a full workspace; deriving the index with the snapshot shares its existing lifetime.

Real repository/HTTP integration verifies authored order, duplicate and fragment links, private and missing targets, ambiguous membership, separate workspaces and withdrawal. Browser acceptance against real Spring, PostgreSQL, Next and Caddy verifies landing-to-article context, next/previous, browser Back, collection return, direct-entry choices, the final entry, keyboard activation and rejection of forged external collection targets. The mobile page has no horizontal overflow. The Linux storage and asset regression suite passes. These are local checks, not production or large-catalog capacity evidence.

Article rendering hides only a first level-one heading whose visible text equals the separately rendered page title. The original heading ID remains as an empty anchor; distinct or later headings and editor previews remain unchanged. Rendering tests and browser acceptance verify the duplicate title disappears while its fragment target remains.

The same-topic audit retains the parent proposal, [public discovery batches](../implemented/2026-09-14-public-discovery-batches.md), [website delivery](../implemented/2026-09-14-workspace-public-delivery.md) and [logical routes](../implemented/2026-09-06-logical-repository-routes.md). Their scope, publication checks and URI encoding remain independently applicable.
