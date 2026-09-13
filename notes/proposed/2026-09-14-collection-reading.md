# Collection Reading

Date: 2026-09-14

## Decision

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires authored collection order and explicit reading context. Derive a navigation index once per verified public snapshot from folder landing documents. Resolve links with the same repository-path rules used by rendered Markdown. Ignore external links, fragments alone, unavailable/private destinations and the landing page itself; keep each eligible article once, in first-link order. Retain route/title references, not copied article bodies.

An article response supplies its collection memberships and immediate neighbors in each collection. The browser selects context only from a matching membership identified by a `collection` query parameter. A direct entry lists memberships without choosing one. Landing-page article links and previous/next links carry that context. The final entry explicitly ends the sequence. Author text stays unchanged; only navigation is derived. A landing that exceeds Markdown reference bounds reports unavailable navigation without making unrelated articles unavailable.

Current public snapshots and website authorization own the index lifetime and visibility. No additional cache or remote fetch is introduced. Folder fallback to README, album previews, author metadata and search-return scroll state remain required by the parent contract and are outside this implementation slice.

## Alternatives and acceptance

Inferring a collection from a path would lose curated cross-directory order and guess incorrectly for articles in multiple collections. Parsing every collection on each article request would repeat work across up to a full workspace; deriving the index with the snapshot shares its existing lifetime.

Acceptance uses real repository snapshots and HTTP responses for authored order, duplicate/fragment links, private and missing targets, ambiguous membership and withdrawal. The running frontend must preserve context from a landing through consecutive articles and back, show direct-entry choices and the final entry, and reject forged external collection targets. Existing publication and asset authorization checks remain required.

The same-topic audit retains the parent proposal, [public discovery batches](../implemented/2026-09-14-public-discovery-batches.md), [website delivery](../implemented/2026-09-14-workspace-public-delivery.md) and [logical routes](../implemented/2026-09-06-logical-repository-routes.md). Their scope, publication checks and URI encoding remain independently applicable.
