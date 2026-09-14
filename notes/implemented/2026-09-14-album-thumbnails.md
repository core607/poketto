# Public Album Thumbnails

Date: 2026-09-14

## Decision

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires recognizable album thumbnails while preserving original media. The [album entrance and lightbox](2026-09-14-album-entrances-and-lightbox.md) owns folder navigation and sequential image reading. Thumbnail delivery avoids transferring full originals before the visitor selects an image.

Public galleries return distinct authorized thumbnail and original URLs. The grid loads a metadata-free image whose longest side is at most 640 pixels: PNG for transparency, otherwise JPEG at quality 0.82. The lightbox loads the exact original only after selection. Animated sources use their first frame for the thumbnail. A failed thumbnail shows an explicit preview placeholder that still opens the authorized original. It never silently downloads the original as a thumbnail fallback. Authenticated editor galleries retain their exact original delivery in this slice.

Each grant keeps the original target, workspace, page commit and representation. Thumbnail delivery rechecks the existing website publication contract before cache/source work and after it, including cache hits. Withdrawal, website disablement, a changed commit or an expired grant denies delivery. No derivative filename or cache hit grants access.

The disposable `derived/public-album-thumbnails` directory has a 32 MiB and 1,024-entry bound. Keys include workspace, immutable Git blob identity or managed asset identity and revision, and `album-640-v1`. Atomic files bind the key and output checksum; invalid entries are discarded. Deleting this directory only causes regeneration. The Git-original cache remains separate; remote Git and managed original storage remain the byte authorities. A separate namespace is required before authenticated derivatives can share this mechanism.

Image generation runs inside the existing browser memory admission. The decoder inspects dimensions before reading, uses source subsampling, and bounds the resulting raster and encoded output. Output is at most 2 MiB. TwelveMonkeys 3.15 provides JPEG metadata and WebP decoding. JPEG EXIF orientation is read before decoding and applied only to the subsampled raster. WebP sources are capped at four million pixels independently of subsampling. PNG or WebP EXIF chunks have no interpreted orientation in this slice and produce an unavailable preview; the original remains accessible. Other unsupported or oversized transformations use the same explicit fallback.

## Alternatives and risks

Serving originals from grid tiles retains the current bandwidth cost. Eager generation while preparing every album page would multiply decoding latency, including images outside the viewport; lazy authorized requests avoid that work. A shared cache with private previews would add a new disclosure boundary, so this slice remains public-only. The cache does not retain content authority and needs no durable recovery mechanism.

Malformed or unsupported media can still make a preview unavailable. Existing grants expire after five minutes, so a long-lived page may require refresh before opening an original. [Discovery cards](2026-09-14-discovery-album-cards.md) owns album classification and homepage cover selection.

## Verification

- Focused renderer tests cover PNG/JPEG output, dimension bounds, JPEG EXIF 6/8 orientation, actual lossless WebP decoding and rejection above the WebP source cap. Cache tests cover workspace/source isolation, hits, corruption and deletion recovery.
- Real PostgreSQL and HTTP integration verifies distinct thumbnail/original URLs, unchanged original bytes and authenticated editor URLs. Separate regressions deny cached thumbnail delivery after Git publication-policy withdrawal and after owner website disablement.
- The pinned production Java 26 image loads TwelveMonkeys 3.15 and decodes self-generated 96-by-64 and 1,984-by-1,984 lossless WebP images with a 128 MiB heap. The larger case checks behavior near the source cap; it is not a performance benchmark or a measurement of the entire HTTP request's heap use.
- A real Spring/PostgreSQL/Next.js/Caddy browser run demonstrates thumbnail loading, distinct original delivery in the lightbox, keyboard navigation, an unavailable preview with an explicit original action, and a 390-pixel layout without horizontal overflow. Production HTTPS rollout remains a separate delivery check.

The same-topic audit retains the album entrance record, [indexed media delivery](2026-09-09-indexed-media-delivery.md), [logical media indexing](2026-09-09-logical-media-index.md), and [authoring foundations](2026-09-05-repository-authoring-foundations.md): their original storage, public scope and image admission rules still apply. The multi-user proposal remains open. Earlier proposals excluding transformations retain their separate unfinished scope; this record defines the explicit thumbnail extension.
