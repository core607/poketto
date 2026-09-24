# Path-derived folder routes

Date: 2026-09-14

## Problem

Folder landings use their directory's canonical route. Rejecting a README or
index.md whose frontmatter names a different route makes structural moves depend
on stale metadata anywhere in the repository: reference repair parses every
Markdown file before preparing a candidate. A valid folder route can also become
stale when its parent directory moves, although moves preserve frontmatter.

## Decision

Always derive README.md and index.md routes from their containing repository
directory. A route field in these files has no effect on folder identity; its
authored bytes remain intact. Ordinary articles continue to accept validated
explicit routes. Publication scope remains determined by the repository path,
publication policy and website switch.

The [atomic move contract](../implemented/2026-09-09-atomic-content-moves.md)
continues to preserve frontmatter and repair links in one candidate. This rule
lets the existing move service parse folder metadata before and after relocation
without changing its authorization, collision or dependency checks. Invalid YAML
and other invalid metadata retain their existing validation.

## Alternatives and consequences

Requiring users to remove or update folder routes before every move conflicts
with ordinary directory operations and cannot be repaired by a generic path
picker. Rewriting frontmatter during a move would break its source-preservation
contract. Deriving the route once in the parser gives website projection,
collection navigation and move reference repair the same folder identity.

The [collection reading contract](../implemented/2026-09-14-collection-reading.md)
retains its canonical folder identity. Previously rejected folder pages with a
route override become eligible only when their path and all other publication
rules permit them; the override never publishes a private file.

The [album entrance decision](../implemented/2026-09-14-album-entrances-and-lightbox.md)
retains index precedence and README fallback. Its fixed route convention is
unchanged; derivation replaces rejection of redundant or stale override metadata.

## Verification

Real PostgreSQL, JGit and authenticated HTTP integration pins folder route overrides, public and private moves with preserved frontmatter and repaired backlinks, and unsafe-path rejection; parser and content-reader tests retain ordinary article route behavior.
