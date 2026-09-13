# Album Entrances and Lightbox

Date: 2026-09-14

## Decision

The [multi-user reading contract](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md) requires one folder landing and keyboard-accessible image reading. A folder uses `index.md`, or `README.md` when no eligible valid index is available. Both names use the folder route. When both valid files exist, only the index enters structured document results; the README remains available through the authorized raw-file entrance and is reported as a shadowed landing. No source file is rewritten or deleted. Private or excluded indexes cannot affect public landing selection.

Relative folder links resolve either landing using the same rules as collection navigation and rendered Markdown. README-only folders participate in the existing sibling-image gallery and collection navigation.

The gallery opens its existing authorized image URLs in a modal dialog. Previous/next buttons and arrow keys follow gallery order, with explicit bounds. Escape and Close restore focus to the opener, and the native modal contains keyboard focus. An unavailable image reports its failure without hiding navigation. Public and authenticated preview URLs retain their existing authorization checks.

This slice does not implement thumbnail delivery or classify album cards across discovery. Original gallery bytes remain in use. Bounded derivative caching, image-decoder admission, recognizable homepage thumbnails, author metadata and search-return state remain required by the parent contract.

## Alternatives and verification

Publishing both filenames at the same folder route would either duplicate the landing or make both fail collision validation. Renaming authored files would alter repository content unnecessarily. Opening images in separate tabs would lose sequential browsing and make keyboard return less predictable.

Real PostgreSQL and repository integration covers README-only folders, index precedence, an excluded index, relative folder links and untouched raw source. The production frontend, Spring, PostgreSQL and Caddy fixture demonstrates a named folder gallery, previous/next by mouse and keyboard, focus containment, Escape and Close return, a delivered image's decode failure, and a 390-pixel mobile layout without horizontal overflow. Native Linux storage and asset regressions preserve public withdrawal and private-image authorization. These local checks do not prove production HTTPS rollout or thumbnail delivery.

The same-topic audit retains [collection reading](../implemented/2026-09-14-collection-reading.md), [logical routes](../implemented/2026-09-06-logical-repository-routes.md), [indexed media delivery](../implemented/2026-09-09-indexed-media-delivery.md) and [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md). This record extends their folder convention; their authority, image and storage guarantees remain applicable. The full multi-user proposal remains open.
