# Logical Repository Routes

Date: 2026-09-06
Status: Implemented

## Decision

A document route is an absolute logical identifier containing the original repository names. It is not an encoded URI. The derived route removes `.md`; `index.md` owns its folder route. Optional `route` metadata uses the same contract, and an index cannot override its folder route. The backend never percent-decodes, percent-encodes, trims, or slugifies these identifiers.

Spaces, non-control Unicode whitespace, `%`, `?`, and `#` are ordinary route characters. `/a%20b` and `/a b` identify different documents; `/literal%2Fname` and `/literal/name` are distinct. A leading slash is required. Existing repository path bounds and guards still reject empty, dot and parent segments, Git internals, backslashes, colons, and control characters. Source-path publication rules and normalized collision detection remain unchanged: `private/` stays private, while `notes.md` and `notes/index.md` still compete for one route.

The URI restrictions previously applied to routes prevented valid Markdown names from entering structured reads, public snapshots, previews, or patches without changing their metadata. A folder containing these characters could not use even that workaround because its index must retain the folder route. Preserving names implements the existing [repository authoring](2026-09-05-repository-authoring-foundations.md) and [phase-one corpus](../proposed/2026-09-05-phase-one-daily-use.md) contracts without rewriting content.

## Transport boundaries

JSON returns the logical route unchanged. A caller encodes it once for `GET /api/public/document?route=...`; the HTTP query decoder recovers the original value before exact lookup. A page renderer must encode each route segment when constructing a browser URL. It must recover that value exactly once, according to its router's parameter contract.

Markdown destinations remain URIs. A link to a filename containing a literal percent, question mark, or hash uses `%25`, `%3F`, or `%23`; an authored `#heading` remains a fragment. The destination parser decodes the path once before looking up its Git object, and media resolution returns the exact logical route plus any authored fragment. This does not relax traversal checks or permit arbitrary query strings in repository-relative Markdown links.

Encoding inside the repository reader would conflate stored names with transport syntax and require every consumer to know that hidden transformation. Generating slugs or requiring route metadata would change the existing-corpus contract. Neither is needed when URI encoding stays at the transport boundary.

## Verification and related records

Repository reader and patch tests use real Git objects, including distinct literal escape sequences, folder collisions, exact source/revision preservation, and unsafe-path rejection. Image tests exercise public links, same-directory galleries, immutable Git image bytes, private preview, and private-path exclusion. A real PostgreSQL-backed HTTP test logs in, previews and atomically saves names containing spaces, `%`, and `#`, then retrieves each exact logical route, including an explicit route containing `?`.

JGit applies Windows filename restrictions even to an in-memory index. Question-mark filenames therefore run in Linux tests covering discovery, atomic move, public gallery and preview. Windows `linuxStorageTest` includes those owning suites in its required native replay; Linux runs them directly. These backend tests do not establish a frontend framework's decoding behavior; actual page, metadata, RSS and sitemap acceptance belongs to the [frontend proposal](../proposed/2026-08-30-nextjs-frontend.md).

The same-topic audit retains the authoring foundations and the partially implemented [publishing](../proposed/2026-09-01-repository-native-publishing-and-assets.md) and [retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) proposals. Workspace tenancy, earlier write and HTTP decisions, and personal-workspace proposals retain their separate ownership boundaries. No note is archived or rejected.
