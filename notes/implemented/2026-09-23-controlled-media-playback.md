# Controlled Audio and Video Playback

Date: 2026-09-23
Status: Implemented

## Problem and decision

Indexed originals need a playback entrance in articles and editor previews.
Native audio and video controls are provided for explicitly supported managed
media, with an ordinary download link retained beside them. Relative Markdown
links remain the authoring format. Playback does not require raw HTML, external
embeds, a new asset database, transcoding or automatic playback.

Only server-resolved indexed references become players. The initial allowlist is
MP3, WAV, MP4 and WebM, under fixed audio/video response types. Declared metadata
can select a player but cannot authorize output: the playback request verifies
the immutable original's digest and length and checks its container signature
before committing headers or bytes. Unsupported or mislabelled originals remain
available through attachment download. A recognized container does not guarantee
that the browser supports every codec inside it. Signature inspection retains at
most 4 KiB; a container without a recognized prefix falls back to download.

MP4 inspection requires a complete initial `ftyp` box within that bound and an
exact four-byte major or compatible brand: `mp41`, `mp42`, `M4A `, `M4V `,
`isom`, `iso2` through `iso9`, `isoa` through `isoc`, `avc1` or `dash`. These
[registered brands](https://mp4ra.org/registered-types/brands) broaden recognition
beyond the `mp4` prefix in the
[browser sniffing algorithm](https://mimesniff.spec.whatwg.org/#signature-for-mp4).
Version bytes and bytes outside the box cannot match. This is bounded container
recognition, not validation of tracks or decodability; audio and video candidates
share the check because these brands do not establish an exclusive track type.

Playback reuses the exact attachment authorization path. Public requests bind the
workspace, current publication commit, article route and referenced public logical
path. Private previews and moderation retain their current distinct authority.
Each request, including seeks and HEAD requests, rechecks current authorization;
streaming retains the existing checks between blocks of at most 256 KiB. Withdrawal
and revocation stop further requests and subsequent output blocks. Bytes already
buffered by a browser cannot be recalled.

## Delivery and limits

The existing media endpoints accept an explicit `play=true` selector. Ordinary
requests retain attachment disposition and octet-stream type. Playback sends
inline disposition, a fixed allowlisted media type, no-store and nosniff. The
frontend accepts only its scoped same-origin resolved media URLs; raw HTML and
arbitrary authored URLs cannot create players. Players use controls and
`preload="none"`, without autoplay.

Playback supports one HTTP bytes range, including open-ended and suffix ranges,
with exact
206/Content-Range/Content-Length responses. Unsatisfiable ranges return 416;
unsupported units and multiple ranges are ignored rather than generating multipart
responses. An If-Range request receives the complete representation because this
entrance issues no reusable cache validator. HEAD ignores Range. Full original
verification remains bounded by the existing 128 MiB original limit and runs for
every request, including seeks. Playback uses bounded streaming selection over
verified bytes, accepting extra disk reads to preserve one trusted
storage path. Existing per-instance and per-workspace transfer admission applies.

## Alternatives and consequences

- Trusting a declared type for every original would enable arbitrary active
  content. Fixed audio/video types and independent signature checks constrain the
  new rendering surface.
- Loading whole originals into JavaScript blobs would delay first playback and
  duplicate memory in the browser. Native URLs support bounded streaming and
  ordinary seek requests.
- A transcoding or streaming platform would add jobs, derived originals and codec
  policy. It is outside the current playback requirement.
- Directly exposing storage paths would bypass current publication and identity
  checks. All requests stay on the existing authorization service.

## Verification

`MediaPlaybackTests`, `MediaFileServiceTests` and `MediaFileControllerTests` cover container recognition, exact full and ranged bytes, range parsing, false types, corruption, withdrawal and midstream revocation; `RepositoryAdminIntegrationIT` verifies full and partial bodies, headers, HEAD and If-Range behavior, public/private isolation and invalidation of a withdrawn playback address over real PostgreSQL and Git. `frontend/tests/rendering.test.tsx` refuses external, private-in-public and unrecognized playback mappings while keeping downloads.

## Related decisions

[Indexed media delivery](../implemented/2026-09-09-indexed-media-delivery.md) owns
attachment authorization, admission and integrity verification; this record adds
an explicit constrained playback mode. The
[CodeAct content contract](../implemented/2026-09-09-codeact-content-and-media.md)
retains immutable originals, logical paths and independent publication authority.
Its original delivery excluded playback; this record extends that boundary.
The [sandbox toolkit](2026-09-05-local-execution-supervisor.md#sandbox-toolkit)
continues to exclude audio/video processing.

[HTTP range semantics](https://httpwg.org/specs/rfc9110.html#field.range) and the
[MIME container patterns](https://mimesniff.spec.whatwg.org/#matching-an-audio-or-video-type-pattern)
inform transport and signature handling; neither grants content access.
