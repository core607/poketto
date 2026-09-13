# Workspace Website Delivery

Date: 2026-09-14

## Problem

The multi-user schema records a workspace website switch, but repository-public snapshots alone do not enforce it. Those snapshots also support members who may read repository-public files while the anonymous website is disabled. Treating the website switch as repository permission would remove that required member access.

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) additionally requires withdrawal to invalidate cached public grants. This supersedes the issued-image survival rule in the [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md), whose storage, source-retention and byte bounds remain applicable.

## Delivery boundary

Keep the repository publication snapshot independent of anonymous website delivery. Public document queries, page media preparation, public image replay and public original downloads must check both authorities. Authenticated public-only reads, previews, exports and executor copies retain their member-authorization and repository-policy checks independently of the website switch.

Website settings belong to the workspace. Only a human owner may read or update them through `GET` and `PUT /api/auth/workspaces/{workspaceId}/publication`; the write requires session CSRF protection and an explicit boolean `enabled`. Public-content write permission, an owner-held API key and site administration alone do not replace workspace ownership. New workspaces retain their disabled schema default. Public slug lookup and bounded keyset enumeration expose only enabled spaces.

Read the committed switch before and after each bounded public snapshot operation. Do not hold a workspace membership lock or a database transaction across image preparation or response streaming. Original streaming repeats public authorization at its existing bounded transfer checkpoints; bytes already sent cannot be recalled. A failed final check does not return a prepared response as authorized. Operational repository health remains independent of an intentional website shutdown.

Public image tokens require the currently approved page commit and page path on every replay, for Git, indexed and legacy managed images alike. A snapshot replacement invalidates old tokens even when only an unrelated file changed; reloading the page obtains fresh tokens. Token expiry and source-retention limits remain upper bounds, not permission to serve a withdrawn page. Reject withdrawn tokens before repopulating a derived cache, and recheck publication after image preparation.

## Alternatives and consequences

Checking only the page controller would leave existing image and download URLs usable after withdrawal. Applying the switch to every repository-public read would incorrectly block members. Waiting for the five-minute token expiry contradicts the accepted withdrawal behavior.

Revalidating every old reference against a newer page could preserve more cached URLs, but requires a separate proof for changed Markdown, aliases and media indexes. Exact-commit invalidation is the initial bounded rule. Stored originals are not deleted by withdrawing publication; references downloaded by other clients remain outside server control.

## Acceptance and remaining integration

Verify human-owner and CSRF enforcement using real PostgreSQL and HTTP entry points. An enabled page must issue a working image URL; disabling the website must reject both the page and that earlier URL while preserving an authorized member's public-file and original-file access. Verify independent workspace switches and disabled-space exclusion from slug lookup and pagination. Exercise withdrawn Git-image cache replay, force-pushed history, preparation races and token expiry through the native Linux suite.

The boundary implementation and focused tests precede the browser control and canonical cross-space routes. This record remains proposed until those entrances are integrated and exercised through the running application. Cross-space discovery batches, author attribution, collections and reading-return state remain owned by the broader multi-user record.

The same-topic audit retains the authoring foundations for repository authority, durable originals, image bounds and publication synchronization, and retains the browser-interface record for rendering and same-origin boundaries. Neither record is archived; the broader multi-user proposal remains active.
