# MCP Request Admission Removal

Date: 2026-09-15

## Problem

The former `McpBodyLimitFilter` held four data slots, four control slots and eight
prefix-reader slots. Its 32 MiB request-body allowance supported image uploads
encoded inside tool arguments. Slots and image reservations survived asynchronous
responses through a servlet listener.

During multi-client use on 2026-09-15, authenticated requests persistently received
HTTP 429 from that filter until restart. Completed-request logs showed no overlapping
work and servlet threads were idle. This suggests unreleased admission, but does not
identify a leaked reservation or async branch: unfinished requests are absent from
completion logs, and async state can outlive a servlet thread.

[Image transfers](2026-09-15-mcp-image-transfers.md) already provide URL import,
platform file references and temporary raw PUT grants. Removing image bytes from
MCP input removes the reason for its large-body admission machinery.

## Decision

`put_asset` accepts a URL, platform file object, or a request for an upload grant.
Its `base64` input is removed without a compatibility shim. Image content returned
by `get_asset` and `get_artifact` retains the protocol's Base64 encoding.

The body filter reads at most 128 KiB plus one sentinel byte and returns HTTP 413
for declared or streamed excess before SDK dispatch. It retains the envelope
preflight: at most 4,096 tokens, 32 nested containers, bounded identifiers and tool
names, and fixed malformed-envelope errors. All tool envelopes share the same byte
bound. A maximum command uses 16,384 Java UTF-16 code units; six-byte JSON escapes
take 96 KiB, leaving space for the ordinary envelope. Extra metadata counts toward
the same 128 KiB. The filter has no semaphore or image reservation.

`McpStreamCompletion` retains only servlet error/timeout completion. Spring WebMVC's
SSE builder ignores `complete` and `error` after a failed send; a disconnected
response otherwise remains unfinished. This listener completes that servlet request
without closing the MCP session or releasing image memory. Actual producers retain
their independent memory lifetime.

Image work uses the existing `ImageMemoryAdmission` pool at its actual owner:

- `ImageTransfers.importUrl` reserves the existing 256 MiB MCP share before
  downloading, holds it through validation and storage, and releases on actual
  operation exit, including failures. URL and platform-file inputs share this path.
- Raw PUT retains its collection and validation reservations. Preparing a grant
  holds no image bytes and takes no image reservation.
- `CancellableMcpSession` reserves the MCP share for image-reading tool calls and
  carries the scope through the tool and `ImageBudgetTransport`. Stream termination
  marks the response complete; actual read/encode/write producers retain the scope
  until they exit. Cancellation cannot release a still-running blocking producer.

The browser image budget is unchanged. Image admission failure returns a tool error
with `reason: IMAGE_MEMORY_BUSY`; it does not close the MCP session or block text
calls through a global request slot.

Existing bounds retain distinct meanings. `poketto.executor.max-sessions` defaults
to four live execution copies. Command admission separately permits four concurrent
commands and serializes access to one copy. `poketto.mcp.max-sessions` defaults to
128 protocol sessions, each of which can use several HTTP requests or streams.
`CancellableMcpSession` permits four active tool calls per session. Their cleanup,
command deadlines and permission boundaries remain unchanged.

Refusal results expose `code` and `reason`, and `McpToolOutcomes` records both without
commands, paths, image URLs or credentials. Existing execution refusal reasons and
unconfirmed-write handling remain intact. Tool errors do not depend on HTTP headers
that may already have been sent. No retry scheduler or capacity forecast is added;
unconfirmed writes still require state inspection before replay.

## Alternatives and consequences

Keeping the large-body gate would require maintaining its async bookkeeping after
its original upload route has gone. A smaller Base64 route still duplicates raw
PUT. Neither is retained. The worker, database schema, content format, OAuth grants
and sandbox restrictions do not change.

Plain JSON responses are allowed by the
[Streamable HTTP specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports),
but changing transport would also change cancellation and observed client connection
behavior. That is a separate decision, not a prerequisite for removing these slots.

The body limit bounds one request, not total retained heap. Async requests, queued
work and responses can outlive servlet threads. Session and operation cleanup must
be verified through the real transport; thread count is not a memory proof. A future
tool needing large input must use a separate transfer route rather than raise this
body limit. The existing image shares remain conservative working-set estimates,
not a guarantee of total JVM memory use.

## Verification

The focused bounds, image-memory, cancellation and image-transfer checks cover
declared/chunked excess, maximum escaped command input, exact stream consumption,
image admission before download, storage failure cleanup and blocking-write lifetime.
Tests that only exercised the removed filter slots and listeners are removed.

`McpProtocolIntegrationIT` uses real HTTP, Spring, the SDK and PostgreSQL for the
tool catalog, permissions, request bounds, repeated sessions and DELETE, and readable
budget refusals while text and upload-grant calls remain available. The independent
`acceptance/image-memory-smoke.py` probe exercises maximum-size images and slow
socket writes, cancellation, disconnect recovery and raw upload receipts against
the staged Linux application. The [Linux receipt](../../acceptance/evidence/2026-09-15-mcp-admission.json) records successful repeated calls, bounded refusals and cleanup. These are isolated fixtures; production deployment
and external-client results must be reported separately.

## Related decisions

- [Local execution supervisor](../proposed/2026-09-05-local-execution-supervisor.md):
  this record supersedes its MCP body sizes, global filter slots and filter-owned
  image reservation; executor admission remains independent.
- [Image transfers](2026-09-15-mcp-image-transfers.md): this record withdraws its
  programmatic Base64 input; grants, download validation and idempotency remain.
- [Authoring foundations](2026-09-05-repository-authoring-foundations.md): retains
  the shared image budget and actual-producer lifetime contract.
