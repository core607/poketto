# Logical Repository Routes

Date: 2026-09-06
Status: Implemented

## Decision

A document route is an absolute logical identifier containing the original repository names. It is not an encoded URI. The derived route removes `.md`; `index.md`, or `README.md` without an `index.md`, owns its folder route ([album entrances](2026-09-14-album-entrances-and-lightbox.md)). Optional `route` metadata uses the same contract and has no effect on a folder landing ([folder routes](2026-09-14-folder-route-moves.md)). The backend never percent-decodes, percent-encodes, trims, or slugifies these identifiers.

Spaces, non-control Unicode whitespace, `%`, `?`, and `#` are ordinary route characters. `/a%20b` and `/a b` identify different documents; `/literal%2Fname` and `/literal/name` are distinct. A leading slash is required. Existing repository path bounds and guards still reject empty, dot and parent segments, Git internals, backslashes, colons, and control characters. Source-path publication rules and normalized collision detection are independent of route syntax: only eligible paths below `public/` publish, while `notes.md` and `notes/index.md` compete for one route.

The URI restrictions previously applied to routes prevented valid Markdown names from entering structured reads, public snapshots, previews, or patches without changing their metadata. A folder containing these characters could not use even that workaround because its index must retain the folder route. Preserving names implements the existing [repository authoring](2026-09-05-repository-authoring-foundations.md) and [phase-one corpus](2026-09-05-phase-one-daily-use.md) contracts without rewriting content.

## Transport boundaries

JSON returns the logical route unchanged. A caller encodes it once for `GET /api/public/document?route=...`; the HTTP query decoder recovers the original value before exact lookup. A page renderer must encode each route segment when constructing a browser URL. It must recover that value exactly once, according to its router's parameter contract.

Markdown destinations remain URIs. A link to a filename containing a literal percent, question mark, or hash uses `%25`, `%3F`, or `%23`; an authored `#heading` remains a fragment. The destination parser decodes the path once before looking up its Git object, and media resolution returns the exact logical route plus any authored fragment. This does not relax traversal checks or permit arbitrary query strings in repository-relative Markdown links.

Encoding inside the repository reader would conflate stored names with transport syntax and require every consumer to know that hidden transformation. Generating slugs or requiring route metadata would change the existing-corpus contract. Neither is needed when URI encoding stays at the transport boundary.

## Verification and related records

Repository reader, patch, image and move tests use real Git objects with names containing spaces, `%`, `?` and `#`, and a PostgreSQL-backed HTTP test saves and retrieves each exact logical route. JGit applies Windows filename restrictions even to an in-memory index, so question-mark filenames run only in Linux tests; Windows `linuxStorageTest` replays those suites natively. These backend tests do not establish a frontend router's decoding behavior, which the [frontend record](2026-08-30-nextjs-frontend.md) owns.
