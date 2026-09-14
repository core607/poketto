# Workspace Website Delivery

Date: 2026-09-14

## Problem

The multi-user schema records a workspace website switch, but repository-public snapshots alone do not enforce it. Those snapshots also support members who may read repository-public files while the anonymous website is disabled. Treating the website switch as repository permission would remove that required member access.

The [multi-user discovery contract](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md) additionally requires withdrawal to invalidate cached public grants. This supersedes the issued-image survival rule in the [authoring foundations](2026-09-05-repository-authoring-foundations.md), whose storage, source-retention and byte bounds remain applicable.

## Delivery boundary

Keep the repository publication snapshot independent of anonymous website delivery. Public document queries, page media preparation, public image replay and public original downloads must check both authorities. Authenticated public-only reads, previews, exports and executor copies retain their member-authorization and repository-policy checks independently of the website switch.

Executor projection media fetches use a member-authorized download entrance. It validates the currently referenced public original against repository policy and repeats membership and publication checks while streaming. The anonymous download entrance additionally checks the website switch. Both paths preserve exact original identity and transfer limits.

Website settings belong to the workspace. Only a human owner may read or update them through `GET` and `PUT /api/auth/workspaces/{workspaceId}/publication`; the write requires session CSRF protection and an explicit boolean `enabled`. Public-content write permission, an owner-held API key and site administration alone do not replace workspace ownership. New workspaces retain their disabled schema default. Public slug lookup and bounded keyset enumeration expose only enabled spaces.

Read the committed switch before and after each bounded public snapshot operation. Do not hold a workspace membership lock or a database transaction across image preparation or response streaming. Original streaming repeats public authorization at its existing bounded transfer checkpoints; bytes already sent cannot be recalled. A failed final check does not return a prepared response as authorized. Operational repository health remains independent of an intentional website shutdown.

Public image tokens require the currently approved page commit and page path on every replay, for Git, indexed and legacy managed images alike. A snapshot replacement invalidates old tokens even when only an unrelated file changed; reloading the page obtains fresh tokens. Token expiry and source-retention limits remain upper bounds, not permission to serve a withdrawn page. Reject withdrawn tokens before repopulating a derived cache, and recheck publication after image preparation.

## Space entrances and refresh

The owner panel confirms an explicit desired website state. It displays success only after an authoritative response and requires rereading after an ambiguous write. Switching spaces invalidates pending confirmations and old responses.

Canonical pages live below `/s/{slug}`; article, search, tags and archive links stay within that space. Public slug APIs disclose only enabled website names, never account identifiers. Image tokens select their own workspace; original-download URLs explicitly bind workspace, page route, commit and logical media path. The old default article entrance redirects to the canonical default-space route after validating the requested article.

Background refresh reads keyset pages of at most eight enabled workspaces, with the default workspace included once per pass for operational health. A short final page restarts the cursor; an empty tail retries from the beginning. Work remains sequential, so one unavailable remote does not stop later refreshes. Page requests never perform remote fetches. The refresh interval is a delay between batches, not a freshness guarantee for every space; remote latency and the number of spaces affect the revisit time. Expired snapshots fail closed. Large-catalog admission and retained-snapshot memory bounds need separate verification before claiming multi-user capacity.

## Alternatives and consequences

Checking only the page controller would leave existing image and download URLs usable after withdrawal. Applying the switch to every repository-public read would incorrectly block members. Waiting for the five-minute token expiry contradicts the accepted withdrawal behavior.

Revalidating every old reference against a newer page could preserve more cached URLs, but requires a separate proof for changed Markdown, aliases and media indexes. Exact-commit invalidation is the initial bounded rule. Stored originals are not deleted by withdrawing publication; references downloaded by other clients remain outside server control.

## Verification and remaining scope

Real PostgreSQL and HTTP integration exercise owner and CSRF enforcement, string workspace identity in responses, independent repositories with different content at identical paths, and disabled-space exclusion. The native Linux suite exercises withdrawn Git-image cache replay, force-pushed history, preparation races, token expiry and authorized member access to originals.

The [native member-media run](../../executor-native/evidence/2026-09-14-member-projection-media.json) exercises actual CLI fetches while website delivery is disabled, private-path denial, projection withdrawal and cleanup. Its authorization is synthetic; PostgreSQL and HTTP protocol checks remain separate. The focused result does not claim that the earlier all-scenarios native run, which exceeded its aggregate deadline, completed.

The production frontend, Spring application, PostgreSQL and Caddy were exercised together against two synthetic Git repositories. Browser acceptance covers default-off publication, cancellation, explicit enablement, scoped reading and images, mobile tag and search navigation, and withdrawal while member editing remains available. A paused and resumed backend verifies that the error page's reload action requests server-rendered content again. Public probes reject the withdrawn space and its earlier image token while the default site remains available. These fixtures do not prove production HTTPS rollout, provider provisioning or large-catalog capacity.

[Discovery batches](2026-09-14-public-discovery-batches.md), [public signatures](2026-09-14-public-author-names.md), [collection reading](2026-09-14-collection-reading.md), [album thumbnails](2026-09-14-album-thumbnails.md) and [public search return](2026-09-14-search-highlights-and-reading-return.md) own their subsequent public-reading extensions. Complete cross-workspace site search and the remaining administration experience stay under the broader multi-user proposal.

The same-topic audit retains the authoring foundations for repository authority, durable originals, image bounds and publication synchronization, and retains the browser-interface record for rendering and same-origin boundaries. Neither record is archived; the broader multi-user proposal remains active.
