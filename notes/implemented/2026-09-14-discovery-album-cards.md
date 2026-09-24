# Album and Collection Discovery Cards

Date: 2026-09-14

## Problem

The [multi-user discovery contract](2026-09-11-multiuser-workspaces-and-discovery.md) requires recognizable album and collection entrances. The initial [discovery batches](2026-09-14-public-discovery-batches.md) treated folder landings alike. Full gallery preparation for every sampled document would issue unused short-lived image grants that expire before the browsing batch. Link previews and structured data also need an image address, and a grant URL stops working after five minutes.

## Decision

There is one card per public document. A folder landing with eligible sibling images is an album; a landing with an available, nonempty authored collection is a collection. These properties can coexist. Ordinary folder landings remain directories, and other documents remain articles. Raw media files do not become posts, and private or body-inline images do not establish a sibling album.

The batch retains only text metadata, immutable page identity and collection membership classification. Page delivery enriches at most six visible cards, loading the media inventory once per represented workspace and commit. Each album selects the first readable eligible sibling image in logical path order from at most eight candidates. Indexed media supplements Git images and retains the existing rule forbidding Git-path collisions. An invalid media index cannot replace an independently eligible Git image. An article, and a folder landing whose folder is known to hold no further images, uses its first readable public inline image in document order, managed references included, from at most eight candidates; private and unreadable references are skipped, never substituted. A folder whose inventory is unknown claims neither an album nor a cover.

Candidate reads share a 32 MiB allowance per cover attempt, including failed reads, or at most 192 MiB across six cards. Managed candidates account for the existing 16 MiB image allowance. These are cumulative source-read limits, not reserved heap. Directory and media inventory enumeration retain the repository's existing entry bounds; the eight-candidate bound applies after enumeration. Failures do not refill the candidate set, and nested images are excluded. An album with candidates but no readable cover retains its label and entrance.

Each visible card receives at most one grant for the existing [album thumbnail representation](2026-09-14-album-thumbnails.md). No cover URL enters the browsing batch. Reopening a still-current batch page obtains usable current grants without reshuffling it. Image delivery retains its normal publication, exact snapshot and expiry checks. The selected page is checked against the current snapshot before and after source preparation, without holding the publication lock across media reads. A card whose publication or page changed during preparation is hidden. Re-enabling a website at the same commit can make its card visible again in an unexpired batch; no publication generation or permanent batch revocation is introduced.

An album thumbnail links to the named folder entrance, and an article cover links to the article. A failed or unsupported preview becomes an explicit placeholder with the same link; it never downloads an original as fallback. A landing with both properties shows both labels. A collection-only card shows a cover only from its own public inline image.

**Stable cover address.** Every public article also has `/s/{slug}/cover/{route}`, which page metadata and structured data use. Next.js serves it from `GET /api/public/spaces/{slug}/cover`, which selects the cover as above, prepares the same thumbnail on each request without issuing a grant, and checks the snapshot before and after. An article without a cover redirects to `/share.png`, a missing or withdrawn article answers 404, and a failure answers 503 with `no-store`. The image and the redirect are served with `Cache-Control: public, max-age=300`, so link previews and search engines may keep them briefly. Withdrawal answers 404 at the origin at once, but a shared cache such as a link-preview service can keep a withdrawn cover for up to five minutes. This is an accepted exception to the [withdrawal rule](2026-09-14-workspace-public-delivery.md): the bound equals the five-minute upper bound on public image grants.

## Alternatives and consequences

Using the first image from a complete gallery would prepare up to 128 images and issue unused thumbnail and original grants. Preparing all sampled cards would multiply that work across the 128-card batch. Storing grants in the 30-minute batch would produce expired URLs after their five-minute lifetime. Visible-page enrichment bounds this work while retaining stable browsing.

Treating albums and collections as exclusive types would conceal one valid entrance for a folder with both images and an authored reading sequence. The two labels describe existing content without changing its routing or introducing a new repository metadata authority.

Putting a grant URL in page metadata would leave previews and search results pointing at expired images; the stable address stays valid while the article is public.

The bounded selection may miss a usable cover after eight unavailable images. Image inventory failure can leave an otherwise readable folder with no album cover or label. These optional presentation failures must not grant private access or prevent reading its text. Large-catalog latency is not measured.

## Verification

`PublicAlbumCoverTests` and `PublicDiscoveryTests` pin cover selection, bounds, grants and the stable cover; `SpacePublicationIntegrationIT` exercises classification, the six-card maximum, grant expiry and withdrawal over real PostgreSQL and HTTP. `frontend/tests/discovery-cover.test.tsx` and `frontend/tests/structured-data.test.tsx` cover placeholders, links and the cover address.

Related: [album entrances](2026-09-14-album-entrances-and-lightbox.md), [collection reading](2026-09-14-collection-reading.md) and [public signatures](2026-09-14-public-author-names.md) own routing, authored sequence and attribution; [indexed media delivery](2026-09-09-indexed-media-delivery.md) owns original sources.
