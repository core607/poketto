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

Responses are plain JSON written on the request thread
([stateless MCP transport](2026-10-18-stateless-mcp-transport.md)), so no servlet
async listener has to complete a disconnected stream.

Image work uses the existing `ImageMemoryAdmission` pool at its actual owner:

- `ImageTransfers.importUrl` reserves the existing 256 MiB MCP share before
  downloading, holds it through validation and storage, and releases on actual
  operation exit, including failures. URL and platform-file inputs share this path.
- Raw PUT retains its collection and validation reservations. Preparing a grant
  holds no image bytes and takes no image reservation.
- `StatelessMcpTransport` reserves the MCP share for image-reading tool calls and
  carries the scope through the tool and the blocking write of the response bytes,
  after which the response is complete; actual read/encode/write producers retain
  the scope until they exit. Cancellation cannot release a still-running blocking
  producer.

The browser image budget is unchanged. Image admission failure returns a tool error
with `reason: IMAGE_MEMORY_BUSY`; it does not block text calls through a global
request slot.

Existing bounds retain distinct meanings. `poketto.executor.max-sessions` defaults
to four active execution leases. Command admission separately permits four concurrent
commands and serializes access to one copy. The MCP transport keeps no protocol
sessions and no per-session call limit. Command deadlines and permission boundaries
remain unchanged.

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
and the [stateless MCP transport](2026-10-18-stateless-mcp-transport.md) later
adopted them together with request-ID cancellation.

The body limit bounds one request, not total retained heap. Queued work and
responses can outlive the bytes read; operation cleanup must be verified through
the real transport, and thread count is not a memory proof. A future
tool needing large input must use a separate transfer route rather than raise this
body limit. The existing image shares remain conservative working-set estimates,
not a guarantee of total JVM memory use.

## Verification

`McpBoundsTests`, `McpImageMemoryTests`, `McpCancellationTests` and
`ImageTransfersTests` pin the byte and envelope bounds, image admission at its
owners and producer lifetime; `McpProtocolIntegrationIT` pins the bounds and
budget refusals over real HTTP and PostgreSQL. The `acceptance/image-memory-smoke.py`
probe and its [Linux receipt](../../acceptance/evidence/2026-09-15-mcp-admission.json)
record maximum-size images, slow writes and disconnect recovery on the staged
application.

## Related decisions

- [Local execution supervisor](2026-09-05-local-execution-supervisor.md): executor
  admission is independent of these request bounds.
- [Image transfers](2026-09-15-mcp-image-transfers.md): this record withdraws its
  programmatic Base64 input; grants, download validation and idempotency remain.
- [Authoring foundations](2026-09-05-repository-authoring-foundations.md): retains
  the shared image budget and actual-producer lifetime contract.
