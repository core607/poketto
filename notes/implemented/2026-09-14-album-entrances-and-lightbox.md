# Album Entrances and Lightbox

Date: 2026-09-14

## Decision

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires one folder landing and keyboard-accessible image reading. A folder uses `index.md`, or `README.md` when no eligible valid index is available. Both names use the folder route. When both valid files exist, only the index enters structured document results; the README remains available through the authorized raw-file entrance and is reported as a shadowed landing. No source file is rewritten or deleted. Private or excluded indexes cannot affect public landing selection.

Relative folder links resolve either landing using the same rules as collection navigation and rendered Markdown. Public execution copies preserve these links, and portable archives rewrite them when the target landing is included in the selection. A portable folder selection accepts the informational shadowed-landing diagnostic and includes only the selected structured landing, not the raw shadowed README. README-only folders participate in the existing sibling-image gallery and collection navigation.

The gallery opens its existing authorized image URLs in a native modal dialog with previous/next and keyboard navigation in gallery order; public and authenticated preview URLs retain their authorization checks.

The [thumbnail delivery record](2026-09-14-album-thumbnails.md) owns the public grid's derivative cache and image-decoder bounds; the lightbox retains exact originals. [Public author names](2026-09-14-public-author-names.md) owns card and article signatures, [discovery cards](2026-09-14-discovery-album-cards.md) owns album classification and homepage covers, and [public search return](2026-09-14-search-highlights-and-reading-return.md) owns query and result-position restoration.

## Alternatives and verification

Publishing both filenames at the same folder route would either duplicate the landing or make both fail collision validation. Renaming authored files would alter repository content unnecessarily. Opening images in separate tabs would lose sequential browsing and make keyboard return less predictable. The [path-derived folder route rule](2026-09-14-folder-route-moves.md) keeps this directory identity when folders move.

`SpacePublicationIntegrationIT` covers README-only folders, index precedence, an excluded index and untouched raw source over real PostgreSQL; `RepositorySnapshotExportsTests` and `PortableContentPlannerTests` pin landing links in execution projections and portable archives.

Related: [collection reading](2026-09-14-collection-reading.md), [logical routes](2026-09-06-logical-repository-routes.md) and [indexed media delivery](2026-09-09-indexed-media-delivery.md) keep their authority, image and storage guarantees for folder landings.
