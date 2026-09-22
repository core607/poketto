# Controlled Audio and Video Playback

Date: 2026-09-23
Status: Proposed

## Problem and decision

Indexed originals can be downloaded but cannot be played in an article or editor
preview. Add native audio and video controls for explicitly supported managed
media, with an ordinary download link retained beside them. Relative Markdown
links remain the authoring format. Playback does not require raw HTML, external
embeds, a new asset database, transcoding or automatic playback.

Only server-resolved indexed references become players. The initial allowlist is
MP3, WAV, MP4 and WebM, under fixed audio/video response types. Declared metadata
can select a player but cannot authorize output: the playback request verifies
the immutable original's digest and length and checks its container signature
before committing headers or bytes. Unsupported or mislabelled originals remain
available through attachment download. A recognized container does not guarantee
that the browser supports every codec inside it.

Playback reuses the exact attachment authorization path. Public requests bind the
workspace, current publication commit, article route and referenced public logical
path. Private previews and moderation retain their current distinct authority.
Each request, including seeks and HEAD requests, rechecks current authorization;
streaming retains the existing checks between blocks of at most 256 KiB. Withdrawal
and revocation stop further requests and subsequent output blocks. Bytes already
buffered by a browser cannot be recalled.

## Delivery and limits

Use the existing media endpoints with an explicit playback selector. Ordinary
requests retain attachment disposition and octet-stream type. Playback sends
inline disposition, a fixed allowlisted media type, no-store and nosniff. The
frontend accepts only its scoped same-origin resolved media URLs; raw HTML and
arbitrary authored URLs cannot create players. Players use controls and
`preload="none"`, without autoplay.

Support one HTTP bytes range, including open-ended and suffix ranges, with exact
206/Content-Range/Content-Length responses. Reject unsatisfiable ranges with 416;
ignore unsupported units and multiple ranges rather than generating multipart
responses. An If-Range request receives the complete representation because this
entrance issues no reusable cache validator. HEAD ignores Range. Full original
verification remains bounded by the existing 128 MiB original limit and runs for
every request, including seeks. The initial implementation uses bounded streaming
selection over verified bytes, accepting extra disk reads to preserve one trusted
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

Test exact full and ranged bytes, boundary and invalid ranges, corrupt or
mislabelled originals, public/private reference isolation, withdrawal and midstream
revocation. HTTP tests must observe headers and body lengths through the real
entrance. Frontend tests must refuse untrusted playback mappings and retain
downloads. Use the documented browser entrance to play synthetic audio and video,
seek, preview private content and verify that withdrawal denies a fresh request.

## Related decisions

[Indexed media delivery](../implemented/2026-09-09-indexed-media-delivery.md) owns
attachment authorization, admission and integrity verification; this proposal adds
an explicit constrained playback mode. The
[CodeAct content contract](../implemented/2026-09-09-codeact-content-and-media.md)
retains immutable originals, logical paths and independent publication authority.
Its original delivery excluded playback; that boundary is extended only by this
record. [Sandbox content tools](../implemented/2026-09-15-sandbox-content-toolkit.md)
continue to exclude audio/video processing.

[HTTP range semantics](https://httpwg.org/specs/rfc9110.html#field.range) and the
[MIME container patterns](https://mimesniff.spec.whatwg.org/#matching-an-audio-or-video-type-pattern)
inform transport and signature handling; neither grants content access.
