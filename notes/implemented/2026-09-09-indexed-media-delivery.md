# Indexed Media Delivery

Date: 2026-09-09
Status: Implemented

## Contract

The [logical media index](2026-09-09-logical-media-index.md) and [original storage](2026-09-05-repository-authoring-foundations.md) support browser and host-service transfers. The [CodeAct content contract](2026-09-09-codeact-content-and-media.md) owns the complete content boundary.

`POST /api/admin/workspaces/{workspaceId}/media` accepts raw `application/octet-stream` bytes, an idempotency key and optional `X-Media-Type` metadata. It acknowledges only durable workspace originals; an index/text save is separate. The body passes through current identity and bounded request admission before streaming. Form and multipart types are rejected on this entrance to prevent implicit servlet form parsing. The existing multipart image entrance retains its image validation contract.

Private downloads select a logical path at an authorized exact repository commit. Public downloads require an exact current publication commit, a public article route, an eligible indexed path and an actual Markdown reference from that article. Downloads use attachment disposition with a UTF-8 filename, octet-stream content type, no-store and nosniff. Headers are delayed until original length and digest have been verified. No declared type enables inline HTML, SVG or document execution.

Transfers admit at most four simultaneous operations per instance and two per workspace. Anonymous public downloads may occupy at most two instance slots and one workspace slot, reserving capacity for authorized uploads and private downloads even when public consumers stall. Download preparation takes the same admission before reading repository media or original metadata, releases it before returning a descriptor, and reacquires it for streaming. An unused descriptor retains no permit. GET/HEAD downloads do not occupy the separate request-body admission used by editor writes.

Transfers recheck authorization before output and between blocks of at most 256 KiB, without holding a database authorization transaction across consumer I/O. Revocation or publication withdrawal stops subsequent blocks; already delivered bytes cannot be recalled. A complete download performs no further authorization check after its last byte. A failed transfer releases admission and leaves immutable originals intact; if authorization also fails, the underlying transfer failure remains a suppressed diagnostic while the caller receives the authorization failure. Stream byte bounds do not claim to interrupt an operating-system I/O call that is itself blocked.

Article preparation resolves indexed image paths through the existing image validation and memory admission. Attachment links retain exact commits and route bindings. Indexed image grants include the logical path and require current publication at read time. Public rendering omits private media mappings. Full-authority image reads can resolve historical indexes; sibling image galleries combine bounded Git and indexed candidates. Text remains available when media metadata or originals are unavailable. An unavailable media catalog omits indexed media but preserves Git images under their independent immutable-object and publication checks; otherwise available Git galleries report partial results.

Resolved media carries a separate `downloads` map for attachment HTTP URLs. Article `links` retain logical routes. Public pages and authenticated previews pass both maps to Markdown rendering, so a download never gains an article `/read/` prefix or heading-fragment namespace. Public attachment URLs intentionally contain the eligible logical path and filename; these are public document references, not physical original-storage coordinates or private index entries. The public renderer rejects private download mappings; the HTTP service independently enforces download authorization.

## Verification and consequences

`IndexedMediaDeliveryTests`, `MediaFileServiceTests` and `MediaFileControllerTests` pin index resolution, transfer admission, authorization between blocks and attachment headers; the native storage replay and the Spring/PostgreSQL integration run them against real Git indexes and immutable originals.

Returning attachment addresses in the article route map causes frontends to encode them as article paths. A separate download map preserves their transport semantics without reserving an otherwise valid article route. Serving declared media types inline would let metadata authorize active content; attachment delivery avoids that trust change. Uploading an original still does not publish it or save a document. The logical-index, authoring and atomic-move records retain their independent ownership.

[Controlled playback](2026-09-23-controlled-media-playback.md) adds an explicit audio/video mode with independent container checks and single-range delivery. Ordinary attachment requests retain the contract above.
