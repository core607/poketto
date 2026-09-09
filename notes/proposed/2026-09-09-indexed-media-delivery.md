# Indexed Media Delivery

Date: 2026-09-09
Status: Proposed

## Contract

Extend the [logical media index](../implemented/2026-09-09-logical-media-index.md) and [original storage](../implemented/2026-09-05-repository-authoring-foundations.md) into browser and host-service transfers. The [CodeAct content plan](2026-09-09-codeact-content-and-media.md) remains the complete delivery target.

`POST /api/admin/media` accepts raw `application/octet-stream` bytes, an idempotency key and optional `X-Media-Type` metadata. It acknowledges only durable workspace originals; an index/text save is separate. The body passes through current identity and bounded request admission before streaming. Form and multipart types are rejected on this entrance to prevent implicit servlet form parsing. The existing multipart image entrance retains its image validation contract.

Private downloads select a logical path at an authorized exact repository commit. Public downloads require an exact current publication commit, a public article route, an eligible indexed path and an actual Markdown reference from that article. Downloads use attachment disposition with a UTF-8 filename, octet-stream content type, no-store and nosniff. Headers are delayed until original length and digest have been verified. No declared type enables inline HTML, SVG or document execution.

Transfers admit at most four simultaneous operations per instance and two per workspace. They recheck authorization between blocks of at most 256 KiB, without holding a database authorization transaction across consumer I/O. Revocation or publication withdrawal stops subsequent blocks; already delivered bytes cannot be recalled. A failed transfer releases admission and leaves immutable originals intact. Stream byte bounds do not claim to interrupt an operating-system I/O call that is itself blocked.

Article preparation resolves indexed image paths through the existing image validation and memory admission. Attachment links retain exact commits and route bindings. Indexed image grants include the logical path and require current publication at read time. Public rendering omits private media mappings. Full-authority image reads can resolve historical indexes; sibling image galleries combine bounded Git and indexed candidates. Text remains available when media metadata or originals are unavailable.

## Verification and remaining acceptance

Native tests exercise real Git indexes, immutable originals, public/private aliases, relative image and attachment links, galleries, historical reads and publication withdrawal. Transfer tests cover exact bytes, guessed workspace references, corruption before output, bounded uploads and revocation during output. HTTP tests verify attachment headers and raw-body handling; real Spring/PostgreSQL integration remains part of the verification gate.

Real browser evidence from the exact changed tree is required before this proposal moves to implemented and the change is submitted. Browser control was unavailable during preparation; unit and native results do not replace that evidence. Source-cache lifecycle, transfer saturation and actual browser downloads must be included in final acceptance. Public-root conversion, CodeAct materialization, moves and portable exports remain separate work in the complete plan. The related foundation records retain their independent ownership and are not archived or rejected.
