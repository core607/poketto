# Session Artifacts

Date: 2026-09-10

## Problem and decision

CodeAct commands need to return images, binary results and long text without
turning temporary work into repository content. A filename in stdout does not
deliver an image, and an unbounded response cannot preserve the executor's memory
and transport limits.

`poketto artifact create` captures a regular file into protected storage and returns
a handle. Each `get_artifact` page checks the caller's account, workspace and
reading scope. Withdrawing a public projection blocks its retained results.
Sandbox commands cannot read the protected copies.

The [signed worker](../../executor-service/README.md#returned-artifacts) charges
the copy's disk quota and releases artifacts with the capturing lease. The
[native backend](../../executor-service/native-repository.md) charges the application's
transfer pool and retains handles across shell replacement for the same copy.
Both allow 16 artifacts and 256 MiB, with a 128 MiB per-file bound and five-minute
expiry. Explicit removal releases bytes early.
Validated PNG, JPEG, GIF and WebP images use MCP image content with the shared
raster and memory policies. Text uses UTF-8 byte pages; other types, including
SVG, use binary resource pages. Explicit byte reads also retrieve files that
cannot pass image-preview validation. The [worker reference](../../executor-service/README.md#returned-artifacts)
owns protocol fields, paging limits and installation requirements.

Commands capture at most 4 MiB of combined output and return 16 KiB previews per
stream. Longer output becomes an artifact with a separate capture-truncation
flag. A command timeout or an output-limit stop ends the sandbox unit but keeps
the lease, so its artifacts remain readable
([per-lease command sandboxes](2026-09-16-per-lease-command-sandboxes.md)).
Resource exhaustion, cancellation, revocation and lease expiry close the lease:
handles cannot outlive that authority. Unavailable long output is explicit and is
interpreted with the command's termination reason.

## Alternatives and consequences

A persistent blob registry would add durable identities and cleanup obligations
for disposable results. Public URLs would extend access beyond the execution
lease. Printing base64 into stdout would consume preview capacity and would
not supply a model-visible image. Lease-owned handles avoid these alternatives,
but clients must retrieve them before expiry; they are not permanent downloads
or portable workspace exports.

The application requires the additive `artifactProtocol: 1` readiness marker;
an incompatible worker rejects admission. Restarting the worker ends existing
leases and their artifacts. Immutable uploaded originals remain a separate
[storage contract](2026-09-05-repository-authoring-foundations.md#managed-originals-and-image-delivery).

## Verification

`McpArtifactTests` and the worker's `test_artifacts.py` pin paging, raster and
digest validation and final authorization before delivery; the
[native evidence](../../executor-native/evidence/2026-09-10-scoped-artifacts.json)
and [actual-client evidence](../../acceptance/clients/evidence/2026-09-10-artifacts.json)
record capture, scope isolation and delivery through real clients.
