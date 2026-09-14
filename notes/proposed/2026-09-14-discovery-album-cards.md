# Album and Collection Discovery Cards

Date: 2026-09-14

## Problem

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires recognizable album and collection entrances. Stable [discovery batches](../implemented/2026-09-14-public-discovery-batches.md) currently treat folder landings alike. Expensive gallery preparation for every sampled document would also issue short-lived image grants that expire before the browsing batch.

## Proposal

Keep one card per public document. A folder landing with eligible sibling images is an album; a landing with an available, nonempty authored collection is a collection. These properties can coexist. Ordinary folder landings remain directories, and other documents remain articles. Raw media files do not become posts, and private or body-inline images do not establish a sibling album.

Retain only text metadata, immutable page identity and collection membership classification in the batch. Enrich at most the six visible cards when producing a page, loading the media inventory once per represented workspace and commit. For each folder, choose the first readable eligible sibling image in logical path order from at most eight candidates. Indexed media supplements Git images and retains the existing rule forbidding Git-path collisions. An invalid media index cannot replace an independently eligible Git image.

Candidate reads share a 32 MiB allowance per cover attempt, including failed reads, or at most 192 MiB across six cards. Managed candidates reserve the existing 16 MiB image allowance. These are cumulative source-read limits, not reserved heap. Directory and media inventory enumeration retain the repository's existing entry bounds; the eight-candidate bound applies after enumeration. Do not refill the candidate set after failures or include nested images. An album with candidates but no readable cover retains its label and entrance.

Issue at most one thumbnail grant per visible album, using the existing [album thumbnail representation](../implemented/2026-09-14-album-thumbnails.md). Store no cover URL in the browsing batch. Reopening a still-current batch page obtains current grants without reshuffling it. Image delivery retains its normal publication, exact snapshot and expiry checks. Check the selected page against the current snapshot before and after source preparation, without holding the publication lock across media reads. Hide a card whose publication or page changed while preparing its cover. Re-enabling a website at the same commit can make its card visible again in an unexpired batch; no publication generation or permanent batch revocation is introduced.

The card thumbnail links to the named folder entrance. A failed or unsupported preview becomes an explicit placeholder with the same link; it never downloads an original as fallback. A landing with both properties shows both labels. Collection-only cards retain their title, public signature, space and text summary without inventing a media cover.

## Alternatives and consequences

Using the first image from a complete gallery would prepare up to 128 images and issue unused thumbnail and original grants. Preparing all sampled cards would multiply that work across the 128-card batch. Storing grants in the 30-minute batch would produce expired URLs after their five-minute lifetime. Visible-page enrichment bounds this work while retaining stable browsing.

Treating albums and collections as exclusive types would conceal one valid entrance for a folder with both images and an authored reading sequence. The two labels describe existing content without changing its routing or introducing a new repository metadata authority.

The bounded selection may miss a usable cover after eight unavailable images. Image inventory failure can leave an otherwise readable folder with no album cover or label. These optional presentation failures must not grant private access or prevent reading its text. This change does not provide complete site search, search return state or large-catalog capacity measurements.

## Acceptance

- Real PostgreSQL and HTTP requests distinguish articles, empty directories, authored collections, albums and overlapping album/collection landings across public spaces.
- Only visible cards receive cover grants. Reopening a current batch after grant expiry preserves card order and supplies usable current URLs. Withdrawal and snapshot replacement deny stale cover delivery and hide affected cards.
- Cover selection excludes private, inline and nested images, honors indexed media identity, and stays within candidate and source-read bounds. An unavailable preview retains its album entrance.
- A real frontend/backend run shows recognizable homepage covers, both labels on a shared landing, navigation into the named album and collection, keyboard-accessible placeholders, stable return order and the mobile layout.

The same-topic audit retains the discovery-batch and thumbnail records, [album entrances](../implemented/2026-09-14-album-entrances-and-lightbox.md), [collection reading](../implemented/2026-09-14-collection-reading.md), [public signatures](../implemented/2026-09-14-public-author-names.md), and [website delivery](../implemented/2026-09-14-workspace-public-delivery.md). They continue to own batch lifetime, representation, routing, authored sequence, attribution and anonymous access. [Indexed media delivery](../implemented/2026-09-09-indexed-media-delivery.md), [logical routes](../implemented/2026-09-06-logical-repository-routes.md) and [member permissions](../implemented/2026-09-12-member-content-permissions.md) retain their source, URI and authorization boundaries. None is archived or rejected. The parent multi-user proposal remains open until its other accepted behavior is demonstrated.
