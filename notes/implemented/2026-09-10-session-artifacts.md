# Session Artifacts

Date: 2026-09-10

## Problem and decision

CodeAct commands need to return images, binary results and long text without
turning temporary work into repository content. A filename in stdout does not
deliver an image, and an unbounded response cannot preserve the executor's memory
and transport limits.

The worker captures regular files into immutable protected storage under the
admitted lease. `poketto artifact create` returns a handle; `get_artifact` checks
the originating workspace, identity, MCP session and current authority before
returning bytes. Public projection withdrawal also blocks retained results.
Sandbox commands cannot read the protected copies. Artifact handles never
publish, upload an original, save Git content or grant access to another client.

A lease retains at most 16 artifacts and 256 MiB, subject to its tmpfs quota,
with a 128 MiB per-file bound and five-minute expiry. Removal releases a copy
early. There is no cross-workspace object registry or deduplication. Validated
PNG, JPEG, GIF and WebP images use MCP image content with the shared raster and
memory policies. Text uses UTF-8 byte pages; other types, including SVG, use
binary resource pages. Explicit byte reads also retrieve files that cannot pass
image-preview validation. The [worker reference](../../executor-service/README.md#returned-artifacts)
owns protocol fields, paging limits and installation requirements.

Commands capture at most 4 MiB of combined output and return 16 KiB previews per
stream. Longer output becomes an artifact with a separate capture-truncation
flag. Exceeding this output bound stops the command and preserves unsaved work
after process cleanup. Timeouts, resource failures, cancellation, revocation and
lease expiry retain the existing session-closing policy: handles cannot outlive
that authority. Unavailable long output is explicit and is interpreted with the
command's termination reason.

## Alternatives and consequences

A persistent blob registry would add durable identities and cleanup obligations
for disposable results. Public URLs would extend access beyond the execution
session. Printing base64 into stdout would consume preview capacity and would
not supply a model-visible image. Lease-owned handles avoid these alternatives,
but clients must retrieve them before expiry; they are not permanent downloads
or portable workspace exports.

The application requires the additive `artifactProtocol: 1` readiness marker.
Install the complete worker before the application; an incompatible worker
rejects admission. Restarting the worker ends existing leases. The broader
[workspace plan](../proposed/2026-09-09-codeact-workspaces.md) and
[content contract](2026-09-09-codeact-content-and-media.md) own bootstrap,
root-format, move, export and tool boundaries. The [local supervisor](../proposed/2026-09-05-local-execution-supervisor.md)
still owns process isolation and resource topology; immutable uploaded originals
remain a separate [storage contract](2026-09-05-repository-authoring-foundations.md#managed-originals-and-image-delivery).

## Verification

[Native evidence](../../executor-native/evidence/2026-09-10-scoped-artifacts.json)
covers immutable capture, scope isolation, publication withdrawal, long-output
retention and process/storage cleanup. [Actual-client evidence](../../acceptance/clients/evidence/2026-09-10-artifacts.json)
covers images, exact text and binary bytes, removed handles and unchanged Git
authority through Codex and Claude Code. Claude saves binary resources locally;
independent byte/hash checks confirm that delivery. MCP tests additionally cover
SVG byte delivery without active rendering, invalid raster/digest rejection,
UTF-8 page boundaries and final authorization before image delivery. These
fixtures do not establish final HTTPS installation acceptance.
