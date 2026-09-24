# Workspace Website Delivery

Date: 2026-09-14

## Problem

The multi-user schema records a workspace website switch, but repository-public snapshots alone do not enforce it. Those snapshots also support members who may read repository-public files while the anonymous website is disabled. Treating the website switch as repository permission would remove that required member access.

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) additionally requires withdrawal to invalidate cached public grants. This supersedes the issued-image survival rule in the [authoring foundations](2026-09-05-repository-authoring-foundations.md), whose storage, source-retention and byte bounds remain applicable.

## Delivery boundary

Keep the repository publication snapshot independent of anonymous website delivery. Public document queries, page media preparation, public image replay and public original downloads must check both authorities. Authenticated public-only reads, previews, exports and executor copies retain their member-authorization and repository-policy checks independently of the website switch.

Executor projection media fetches use a member-authorized download entrance. It validates the currently referenced public original against repository policy and repeats membership and publication checks while streaming. The anonymous download entrance additionally checks the website switch. Both paths preserve exact original identity and transfer limits.

Website settings belong to the workspace. Only a human owner may read or update them through `GET` and `PUT /api/auth/workspaces/{workspaceId}/publication`; the write requires session CSRF protection and an explicit boolean `enabled`. Public-content write permission, an owner-held API key and site administration alone do not replace workspace ownership. New workspaces retain their disabled schema default. A website is served only while its switch is on and every owner is eligible under [consumer identity and site policy](2026-09-20-consumer-identity-and-site-policy.md), which refuses enabling the switch while an owner is ineligible. Public slug lookup and bounded keyset enumeration expose only served spaces.

Read the committed switch before and after each bounded public snapshot operation. Do not hold a workspace membership lock or a database transaction across image preparation or response streaming. Original streaming repeats public authorization at its existing bounded transfer checkpoints; bytes already sent cannot be recalled. A failed final check does not return a prepared response as authorized. Operational repository health remains independent of an intentional website shutdown.

Public image tokens require the currently approved page commit and page path on every replay, for Git, indexed and legacy managed images alike. A snapshot replacement invalidates old tokens even when only an unrelated file changed; reloading the page obtains fresh tokens. Token expiry and source-retention limits remain upper bounds, not permission to serve a withdrawn page. Reject withdrawn tokens before repopulating a derived cache, and recheck publication after image preparation. The stable article cover address carries no token and is the one accepted exception: shared caches may keep a withdrawn cover for up to five minutes, as the [discovery cards record](2026-09-14-discovery-album-cards.md) states.

## Space entrances and refresh

The owner panel confirms an explicit desired website state. It displays success only after an authoritative response and requires rereading after an ambiguous write. Switching spaces invalidates pending confirmations and old responses.

Canonical pages live below `/s/{slug}`; article, search, tags and archive links stay within that space. Public slug APIs disclose only enabled website names, never account identifiers. Image tokens select their own workspace; original-download URLs explicitly bind workspace, page route, commit and logical media path. The old default article entrance redirects to the canonical default-space route after validating the requested article.

Background refresh reads keyset pages of at most eight enabled workspaces, with the default workspace included once per pass for operational health. A short final page restarts the cursor; an empty tail retries from the beginning. Work remains sequential, so one unavailable remote does not stop later refreshes. Page requests never perform remote fetches. The refresh interval is a delay between batches, not a freshness guarantee for every space; remote latency and the number of spaces affect the revisit time. Expired snapshots fail closed. Large-catalog admission and retained-snapshot memory bounds need separate verification before claiming multi-user capacity.

## Alternatives and consequences

Checking only the page controller would leave existing image and download URLs usable after withdrawal. Applying the switch to every repository-public read would incorrectly block members. Waiting for the five-minute token expiry contradicts the accepted withdrawal behavior.

Revalidating every old reference against a newer page could preserve more cached URLs, but requires a separate proof for changed Markdown, aliases and media indexes. Exact-commit invalidation is the initial bounded rule. Stored originals are not deleted by withdrawing publication; references downloaded by other clients remain outside server control.

## Verification

`SpacePublicationIntegrationIT` exercises owner and CSRF enforcement, string workspace identity, independent repositories with different content at identical paths, disabled-space exclusion and withdrawal of cached public images while member access remains. The native Linux storage suite covers withdrawn Git-image cache replay, force-pushed history, preparation races, token expiry and member access to originals, and the [native member-media run](../../executor-native/evidence/2026-09-14-member-projection-media.json) covers CLI fetches while website delivery is disabled.

[Discovery batches](2026-09-14-public-discovery-batches.md), [public signatures](2026-09-14-public-author-names.md), [collection reading](2026-09-14-collection-reading.md), [album thumbnails](2026-09-14-album-thumbnails.md), [site search](2026-09-14-public-site-search.md) and [public search return](2026-09-14-search-highlights-and-reading-return.md) extend public reading on this boundary; the [authoring foundations](2026-09-05-repository-authoring-foundations.md) keep repository authority, durable originals and image bounds.
