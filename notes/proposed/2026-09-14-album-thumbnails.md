# Public Album Thumbnails

Date: 2026-09-14

## Problem and proposal

The [multi-user reading contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires recognizable album thumbnails while preserving original media. The [album entrance and lightbox](../implemented/2026-09-14-album-entrances-and-lightbox.md) currently delivers originals to both the gallery grid and the lightbox. Opening a gallery therefore transfers full images before the visitor chooses one.

Public galleries should return distinct authorized thumbnail and original URLs. The grid loads a metadata-free image whose longest side is at most 640 pixels: PNG for transparency, otherwise JPEG at quality 0.82. The lightbox loads the exact original only after selection. Animated sources use their first frame for the thumbnail. A failed thumbnail shows an explicit preview placeholder that still opens the authorized original. It never silently downloads the original as a thumbnail fallback. Authenticated editor galleries retain their exact original delivery in this slice.

Each grant keeps the original target, workspace, page commit and representation. Thumbnail delivery rechecks the existing website publication contract before cache/source work and after it, including cache hits. Withdrawal, website disablement, a changed commit or an expired grant denies delivery. No derivative filename or cache hit grants access.

The disposable `derived/public-album-thumbnails` directory has a 32 MiB and 1,024-entry bound. Keys include workspace, immutable Git blob identity or managed asset identity and revision, and `album-640-v1`. Atomic files bind the key and output checksum; invalid entries are discarded. Deleting this directory only causes regeneration. The original Git-image cache and managed storage remain independent authorities. A separate namespace is required before authenticated derivatives can share this mechanism.

Image generation runs inside the existing browser memory admission. The decoder inspects dimensions before reading, uses source subsampling, and bounds the resulting raster and encoded output. Output is at most 2 MiB. TwelveMonkeys 3.15 provides JPEG metadata and WebP decoding. JPEG EXIF orientation is read before decoding and applied only to the subsampled raster. WebP sources are capped at four million pixels independently of subsampling. PNG or WebP EXIF chunks have no interpreted orientation in this slice and produce an unavailable preview; the original remains accessible. Other unsupported or oversized transformations use the same explicit fallback.

## Alternatives and risks

Serving originals from grid tiles retains the current bandwidth cost. Eager generation while preparing every album page would multiply decoding latency, including images outside the viewport; lazy authorized requests avoid that work. A shared cache with private previews would add a new disclosure boundary, so this slice remains public-only. The cache does not retain content authority and needs no durable recovery mechanism.

Decoder support, source working sets and malformed media require actual runtime checks before claiming the supported-format matrix. Existing grants expire after five minutes, so a long-lived page may require refresh before opening an original. Album discovery classification and homepage cover selection remain separate work under the parent contract.

## Acceptance

- Real public gallery responses contain distinct thumbnail/original URLs; grid requests decode to at most 640 pixels, and the lightbox delivers unchanged original bytes.
- A cache hit cannot survive website disablement, page withdrawal, commit replacement or grant expiry. Workspaces and representations cannot reuse each other's grants or entries.
- Cache deletion and corruption regenerate disposable files without changing originals. Capacity and output bounds hold under repeated requests.
- The shipped runtime demonstrates PNG, JPEG, GIF and supported WebP behavior, with bounded rejection for unsupported transformations and intact original delivery.
- A real frontend/backend run demonstrates thumbnail loading, an unavailable preview, original lightbox navigation and keyboard return.

The same-topic audit retains the album entrance record, [indexed media delivery](../implemented/2026-09-09-indexed-media-delivery.md), [logical media indexing](../implemented/2026-09-09-logical-media-index.md), and [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md): their original storage, public scope and image admission rules still apply. The multi-user proposal remains open. Earlier proposals excluding transformations retain their separate unfinished scope; this record defines the explicit thumbnail extension.
