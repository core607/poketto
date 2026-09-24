# Public Album Thumbnails

Date: 2026-09-14

## Decision

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires recognizable album thumbnails while preserving original media. The [album entrance and lightbox](2026-09-14-album-entrances-and-lightbox.md) owns folder navigation and sequential image reading. Thumbnail delivery avoids transferring full originals before the visitor selects an image.

Public galleries return distinct authorized thumbnail and original URLs. The grid loads a metadata-free image whose longest side is at most 640 pixels: PNG for transparency, otherwise JPEG at quality 0.82. The lightbox loads the exact original only after selection. Animated sources use their first frame for the thumbnail. A failed thumbnail shows an explicit preview placeholder that still opens the authorized original. It never silently downloads the original as a thumbnail fallback. Authenticated editor galleries retain their exact original delivery in this slice.

Each grant keeps the original target, workspace, page commit and representation. Thumbnail delivery rechecks the existing website publication contract before cache/source work and after it, including cache hits. Withdrawal, website disablement, a changed commit or an expired grant denies delivery. No derivative filename or cache hit grants access.

The disposable `derived/public-album-thumbnails` directory has a 32 MiB and 1,024-entry bound. Keys include workspace, immutable Git blob identity or managed asset identity and revision, and `album-640-v1`. Atomic files bind the key and output checksum; invalid entries are discarded. Deleting this directory only causes regeneration. The Git-original cache remains separate; remote Git and managed original storage remain the byte authorities. A separate namespace is required before authenticated derivatives can share this mechanism.

Image generation runs inside the existing browser memory admission. The decoder inspects dimensions before reading, uses source subsampling, and bounds the resulting raster and encoded output. Output is at most 2 MiB. TwelveMonkeys 3.15 provides JPEG metadata and WebP decoding. JPEG EXIF orientation is read before decoding and applied only to the subsampled raster. WebP sources are capped at four million pixels independently of subsampling. A PNG eXIf or WebP EXIF chunk is read for its TIFF orientation tag, and a payload without one counts as normal. A malformed payload, a second payload or an orientation outside 1–8 produces an unavailable preview; the original remains accessible. Other unsupported or oversized transformations use the same explicit fallback.

## Alternatives and risks

Serving originals from grid tiles retains the current bandwidth cost. Eager generation while preparing every album page would multiply decoding latency, including images outside the viewport; lazy authorized requests avoid that work. A shared cache with private previews would add a new disclosure boundary, so this slice remains public-only. The cache does not retain content authority and needs no durable recovery mechanism.

Malformed or unsupported media can still make a preview unavailable. Existing grants expire after five minutes, so a long-lived page may require refresh before opening an original. [Discovery cards](2026-09-14-discovery-album-cards.md) owns album classification and homepage cover selection.

## Verification

`AlbumThumbnailRendererTests` covers output formats, dimension bounds, JPEG, PNG and WebP EXIF orientation, malformed payloads and the WebP source cap; `PublicThumbnailCacheTests` covers workspace and source isolation, corruption and deletion recovery; `RepositoryAdminIntegrationIT` and `SpacePublicationIntegrationIT` verify distinct thumbnail and original URLs, unchanged originals, and denial of cached public images after withdrawal.

Related: [indexed media delivery](2026-09-09-indexed-media-delivery.md), [logical media indexing](2026-09-09-logical-media-index.md) and [authoring foundations](2026-09-05-repository-authoring-foundations.md) keep original storage, public scope and image admission; earlier proposals that excluded transformations are extended by this thumbnail representation only.
