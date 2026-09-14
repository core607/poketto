# Administration Filename Search

Date: 2026-09-14

## Problem

The [multi-user administration contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires filename search across all authorized files, separately from body search. The editor currently filters only loaded directory branches. A matching file inside a collapsed directory is invisible, and the structured Markdown index cannot represent arbitrary files or indexed media.

## Proposal

`GET /api/admin/workspaces/{workspaceId}/repository/filenames` matches the query literally against complete repository-relative paths. It enumerates regular Git files and logical media-index paths without parsing document bodies, loading originals, following symlinks or entering submodules. Paths rejected by the existing path rules are not searchable files. Results include the resolved commit, matching paths in Java string order, total count, offset and limit. An unborn repository returns an empty result.

The shared authorized reader selects full or publication-eligible current content from the caller's current membership and capabilities, and rechecks authorization after materialization. Public-only members do not gain private filenames, private counts or historical access. Their repository-public search remains available while anonymous website delivery is off. Full readers can pin a commit in current main's history. Result pages after the first require the returned commit, and public-only continuations must still name current main. The existing media-index collision rules run before returning combined Git and media results.

Queries contain 1–200 characters. A page defaults to 50 entries, permits up to 200 and caps its offset at 100,000. A complete scan admits at most 100,000 combined Git tree and indexed-media entries; excess work fails explicitly rather than reporting partial results as complete. Repository path and media-index bounds remain unchanged.

The editor replaces the expanded-tree filter with a dedicated filename-search form and paged results. Search itself leaves the current draft intact; opening a result uses the existing dirty confirmation. A new query resolves current main, while previous and next pages retain its commit. Changes to the displayed tree invalidate old results, and late responses cannot replace results from a new query or workspace. Original-byte and text-read restrictions still apply when a result is opened.

Filename results and the existing authorized body-search title and snippet highlight literal matches with the shared escaped-text `mark` renderer. Body search retains its current corpus and response bound. Neither kind of search changes the document body or publishing state.

## Alternatives and consequences

Expanding every browser directory would make a search depend on earlier navigation and issue a request per folder. Reading the Markdown index omits non-Markdown files and unnecessarily parses content. A persistent filename index adds invalidation and storage authority. A bounded Git-tree and logical-index read uses the existing repository authority and current authorization instead.

Filename results describe accessible paths, not a guarantee that every result can be edited as UTF-8 text. Binary and oversized files retain the existing diagnostic behavior. A repository change can require a public-only user to restart pagination; it cannot expose a withdrawn historical filename.

## Acceptance

- Real repository and authenticated HTTP queries find nested Markdown, ordinary files and indexed media without expanding the tree, exclude symlinks and submodules, preserve stable pinned pagination and reject invalid bounds or media collisions.
- Two workspaces and public/private memberships have independent path results and counts. Revocation after traversal prevents delivery; public-only searches cannot select old commits or reveal excluded paths while website delivery is off.
- A real Spring/PostgreSQL/Next/Caddy browser run finds a file in an unopened folder, opens it through the dirty guard, pages results, preserves a draft after search failure, and highlights literal filename/body matches safely on desktop and mobile.

The same-topic audit retains [directory navigation](../implemented/2026-09-08-repository-directory-navigation.md), [logical media](../implemented/2026-09-09-logical-media-index.md), [member permissions](../implemented/2026-09-12-member-content-permissions.md), [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md), [reading text](../implemented/2026-09-12-shared-checks.md), [search highlights](../implemented/2026-09-14-search-highlights-and-reading-return.md), and [content navigation](../implemented/2026-09-14-admin-content-navigation.md) as independent contracts. The parent proposal remains active for its outstanding work.
