# Album and Collection Discovery Cards

Date: 2026-09-14

## Problem

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires recognizable album and collection entrances. The initial [discovery batches](2026-09-14-public-discovery-batches.md) treated folder landings alike. Full gallery preparation for every sampled document would issue unused short-lived image grants that expire before the browsing batch.

## Decision

There is one card per public document. A folder landing with eligible sibling images is an album; a landing with an available, nonempty authored collection is a collection. These properties can coexist. Ordinary folder landings remain directories, and other documents remain articles. Raw media files do not become posts, and private or body-inline images do not establish a sibling album.

The batch retains only text metadata, immutable page identity and collection membership classification. Page delivery enriches at most six visible cards, loading the media inventory once per represented workspace and commit. Each folder selects the first readable eligible sibling image in logical path order from at most eight candidates. Indexed media supplements Git images and retains the existing rule forbidding Git-path collisions. An invalid media index cannot replace an independently eligible Git image.

Candidate reads share a 32 MiB allowance per cover attempt, including failed reads, or at most 192 MiB across six cards. Managed candidates account for the existing 16 MiB image allowance. These are cumulative source-read limits, not reserved heap. Directory and media inventory enumeration retain the repository's existing entry bounds; the eight-candidate bound applies after enumeration. Failures do not refill the candidate set, and nested images are excluded. An album with candidates but no readable cover retains its label and entrance.

Each visible album receives at most one grant for the existing [album thumbnail representation](2026-09-14-album-thumbnails.md). No cover URL enters the browsing batch. Reopening a still-current batch page obtains usable current grants without reshuffling it. Image delivery retains its normal publication, exact snapshot and expiry checks. The selected page is checked against the current snapshot before and after source preparation, without holding the publication lock across media reads. A card whose publication or page changed during preparation is hidden. Re-enabling a website at the same commit can make its card visible again in an unexpired batch; no publication generation or permanent batch revocation is introduced.

The card thumbnail links to the named folder entrance. A failed or unsupported preview becomes an explicit placeholder with the same link; it never downloads an original as fallback. A landing with both properties shows both labels. Collection-only cards retain their title, public signature, space and text summary without inventing a media cover.

## Alternatives and consequences

Using the first image from a complete gallery would prepare up to 128 images and issue unused thumbnail and original grants. Preparing all sampled cards would multiply that work across the 128-card batch. Storing grants in the 30-minute batch would produce expired URLs after their five-minute lifetime. Visible-page enrichment bounds this work while retaining stable browsing.

Treating albums and collections as exclusive types would conceal one valid entrance for a folder with both images and an authored reading sequence. The two labels describe existing content without changing its routing or introducing a new repository metadata authority.

The bounded selection may miss a usable cover after eight unavailable images. Image inventory failure can leave an otherwise readable folder with no album cover or label. These optional presentation failures must not grant private access or prevent reading its text. This change does not provide complete site search, search return state or large-catalog capacity measurements.

## Verification

- Focused asset tests cover sibling eligibility, unavailable sources, candidate and read bounds, shared inventory loading and thumbnail grants. Frontend tests cover both labels, safe unavailable-preview links and recovery when the source token changes.
- Real PostgreSQL and HTTP integration verifies album, collection-only and overlapping classification, a six-card maximum, decodable thumbnail bytes and stable batch order. Purging the real grant store past expiry makes the old token fail and supplies a usable token on reopening. A Git commit update invalidates old covers and removes affected old routes; website disablement denies subsequent cover delivery.
- A real Spring/PostgreSQL/Next.js/Caddy run verifies two public spaces and signatures, article/album/collection/overlap cards, a failed-cover entrance without an original-image fallback, authorized thumbnail URLs, album gallery navigation, stable article/back and reload order, and a 390-pixel viewport without horizontal overflow. The browser fixture uses 320-pixel source images; larger-image resizing remains covered by the thumbnail record.

These checks do not establish production rollout or large-catalog latency. The same-topic audit retains the discovery-batch and thumbnail records, [album entrances](2026-09-14-album-entrances-and-lightbox.md), [collection reading](2026-09-14-collection-reading.md), [public signatures](2026-09-14-public-author-names.md), and [website delivery](2026-09-14-workspace-public-delivery.md). They continue to own batch lifetime, representation, routing, authored sequence, attribution and anonymous access. [Indexed media delivery](2026-09-09-indexed-media-delivery.md), [logical routes](2026-09-06-logical-repository-routes.md) and [member permissions](2026-09-12-member-content-permissions.md) retain their source, URI and authorization boundaries. None is archived or rejected. The parent multi-user proposal remains open until its other accepted behavior is demonstrated.
