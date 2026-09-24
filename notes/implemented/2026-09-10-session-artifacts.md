# Session Artifacts

Date: 2026-09-10

## Problem and decision

CodeAct commands need to return images, binary results and long text without
turning temporary work into repository content. A filename in stdout does not
deliver an image, and an unbounded response cannot preserve the executor's memory
and transport limits.

The worker captures regular files into immutable protected storage under the
admitted lease. `poketto artifact create` returns a handle. `get_artifact` serves
it to any MCP session of the same account, workspace and reading scope while the
capturing lease stays open, after checking current authority; the handle is not
bound to the originating MCP session. A request under a different credential of
the account first moves the copy to a new lease, which closes the old lease and
its artifacts. A public-only grant never reads a full copy's artifacts, and public
projection withdrawal also blocks retained results. Sandbox commands cannot read
the protected copies. Artifact handles never publish, upload an original or save
Git content.

A lease retains at most 16 artifacts and 256 MiB, charged to the copy's disk
quota, with a 128 MiB per-file bound and five-minute expiry. Removal releases an
artifact early. There is no cross-workspace object registry or deduplication.
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
