# MCP Request Admission Removal

Date: 2026-09-15
Status: Proposed

## Problem

Every `POST /mcp` passes through `McpBodyLimitFilter` after Spring Security has
already rejected unauthenticated callers. The filter holds three process-wide
semaphores — four data slots, four control slots for `notifications/initialized`
and `notifications/cancelled`, and eight prefix readers — and reserves the whole
MCP image budget before dispatching `get_asset`, image-import `put_asset`,
`get_artifact` or any body over 16 KiB. A small grant-only `put_asset` request is
exempt from the image reservation. A permit is returned when the synchronous chain
ends or, once the SDK answers over SSE, when the servlet async lifecycle completes.
The [local execution supervisor](2026-09-05-local-execution-supervisor.md) record
fixes these numbers, and it states why they exist: a session POST may carry a
`put_asset` Base64 image of up to 16 MiB decoded, so the body may reach 32 MiB
and must be buffered in heap before the SDK sees it, and four such buffers are
the heap budget the instance can afford. Every other rule in the filter — the
separate control slots, the prefix readers, the tool-name sniff, the async
listener bookkeeping — exists to make that budget survive small requests
arriving while large ones are buffered.

[MCP image transfers](../implemented/2026-09-15-mcp-image-transfers.md) moved image
bytes out of the MCP body. A client sends a public HTTPS URL, a platform file
object, or a raw HTTP PUT to a temporary upload URL; the tool description tells
models never to transcribe Base64, and the Base64 input is retained only for
programmatic callers. With that route in place, the largest legitimate MCP
request is an envelope around a 16,384-character `repo_exec` command. The heap
case the slots were built for does not occur in intended use.

The slots still govern every authenticated call, and their bookkeeping is a
failure surface of its own. On 2026-09-15 the author's production instance served
four MCP clients under one account. Two of them open a session per tool call:
one fetches an OAuth token and initializes before every call, the other
initializes, calls, and deletes the session, five HTTP requests per tool call.
After roughly seventy session POSTs, clients persistently received HTTP 429 from
the body filter until the process was restarted. Completed access-log intervals
showed no overlapping MCP work, and servlet worker threads were idle. This strongly
suggests permits were not returned, but does not establish which reservation or
async branch remained live: completed-request logs omit unfinished requests, and
an async request may retain state after its servlet thread returns. The rejection
named neither the bound nor its occupancy. The proposed removal follows from
withdrawing large image request bodies; it does not depend on proving a particular
permit leak or adding a watchdog to the old gate.

## Proposal

Remove the reason, then the mechanism.

1. Withdraw the `base64` input from `put_asset`. The URL, platform file and raw
   upload routes remain the only ways to store an image through MCP. This
   reverses the "remains available for programmatic callers" clause of the image
   transfer decision; programmatic callers use the raw upload URL like any other
   client holding bytes. The Base64 encoding of image content in `get_asset` and
   `get_artifact` responses is protocol output, not an upload route, and stays.
2. Reduce `McpBodyLimitFilter` to two checks that need no memory budget: a
   declared or streamed body over 128 KiB receives 413 before dispatch, and the
   existing streaming envelope preflight (4,096 tokens, 32 nested containers,
   bounded request identifiers and tool names) still refuses malformed structure
   before the SDK builds an argument tree. The command limit counts 16,384 Java
   UTF-16 code units; JSON escaping uses at most six bytes per unit, or 96 KiB for
   the command, leaving space for the ordinary envelope within 128 KiB. Additional
   metadata and whitespace count toward that same total bound. Read each body
   without an image reservation; do not derive a total retained-memory bound from
   servlet thread count, because async requests can outlive those threads.
3. Delete the three semaphores, the async listener that returns their permits,
   the control-frame classification and the tool-name sniff. Concurrency is
   bounded by the existing owners of each resource. `poketto.executor.max-sessions`
   (default 4) limits live execution copies, not MCP clients or running commands.
   The executor separately admits at most four concurrent commands and serializes
   commands within one copy. `poketto.mcp.max-sessions` (default 128) limits MCP
   protocol sessions, not HTTP connections; one session may use multiple requests
   or streams. `CancellableMcpSession` admits at most four active tool calls per
   session. Keep those limits and their cleanup, command deadlines, and the image
   budget below; do not replace the removed filter slots with another global gate.
4. Whoever handles image bytes reserves the budget for them. The filter's
   dispatch-time reservation is replaced by one reservation per owner, all from
   the existing `ImageMemoryAdmission` pool; no second permit system is added.
   - URL and platform-file import (`ImageTransfers.importUrl`): reserve before
     the download starts, release after validation and storage finish.
   - Raw upload: the collection and validation reservations already in
     `ImageTransfers` are unchanged.
   - Image responses (`get_asset`, `get_artifact`): reserve before the bytes are
     read and release only after the SSE send has completed and the actual
     producer has exited — never when the tool method returns, because the image
     may still be in flight. The transport already carries the scope into the
     tool exchange and the cancellable session; that hand-off becomes the owner
     of the reservation's lifetime instead of the body filter.
   Browser image work keeps its existing reservations.
5. A remaining refusal returns a stable `code` and a specific `reason` to the
   caller; diagnostics record the same values without commands, paths or transfer
   credentials. Extend the existing result producers and `McpToolOutcomes`, not a
   separate error framework. An optional wait hint is appropriate only for a
   known transient refusal; it does not predict when capacity becomes available.
   A direct HTTP refusal may use `Retry-After` before headers are committed. Tool
   refusals carry any hint in their MCP result instead of relying on HTTP headers
   that may already have been sent or may not reach the model. Unknown write
   outcomes still require state inspection before replay; a wait hint never
   authorizes an automatic retry of an unconfirmed write. Add no retry scheduler.

## Implementation scope and dependencies

Application: `McpBodyLimitFilter`, `McpEnvelopeBounds`, `PutAssetInput`, the
`put_asset` schema in `RepositoryMcpTools`, the import path in `ImageTransfers`,
the reservation hand-off in `McpTransportConfiguration` and
`CancellableMcpSession`, and the existing refusal results and `McpToolOutcomes`.
Tests: `McpBoundsTests`
keeps declared, chunked and repeated-stream-access coverage at the new bound;
`McpImageMemoryTests` cases that exist only to prove slot release are deleted,
and the cases that pin the image reservation through an SSE write, timeout and
disconnect move with the reservation to its new owner. Acceptance:
`acceptance/image-memory-smoke.py` stores its image through the raw upload URL
instead of Base64; `acceptance/image-transfer-smoke.py` drops its Base64
assertions. Documents: `docs/usage.md` and `docs/usage.zh.md` describe `put_asset`
as accepting exactly one of `url`, `file` or an upload grant, and the supervisor
record's MCP paragraph is superseded by this note for the body bound and slots.

No database schema, content-format or deployment configuration change is involved.
The MCP input schema changes by withdrawing `base64`. The phase clause in
AGENTS.md applies: no compatibility shim for the withdrawn input.

## Alternatives considered

- **Harden the gate.** Lease-based permits with a watchdog, per-caller buckets
  beneath a global ceiling, a short wait before refusing, and `Retry-After` on
  every 429. Each is a reasonable rule for an admission gate that has a purpose;
  here they would preserve a 32 MiB heap budget for a body size the system no
  longer accepts, and each added return path is another way to leak a permit.
- **Keep Base64 with a small cap.** Bounding the input at a few hundred kilobytes
  keeps two transfer routes for the same bytes, needs its own justification for
  the cap, and still requires the filter to size bodies by tool. The raw upload
  URL already serves a caller that holds bytes and can issue an HTTP PUT.
- **Move admission to the reverse proxy.** Proxy rate limits are the right tool
  against anonymous abuse, which never reaches this filter. The failure here was
  heap budgeting for one body shape, not request rate.
- **Plain JSON responses instead of SSE sessions.** The
  [Streamable HTTP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
  allows a server to answer a request with a single JSON response, which would
  remove the async lifecycle the permits are tied to. Cancellation and the
  observed client connection behavior depend on the current SSE session
  handling, so replacing the transport widens the change well beyond this
  removal. It remains a separate decision.

## Acceptance

- A `POST /mcp` body over 128 KiB receives 413 before the SDK dispatches it,
  whether the length is declared or chunked. A 16,384-code-unit command using
  six-byte JSON escapes reaches argument validation within an ordinary envelope.
- `McpBodyLimitFilter` contains no semaphore and no async listener; reading the
  filter shows only the size bound and the envelope preflight.
- The `put_asset` tool schema offers `url`, `file` and `mode` and no `base64`
  property; a request carrying `base64` is refused as an unknown argument.
- `get_asset` and `get_artifact` still reserve the image budget before reading
  bytes and hold it through the SSE write; the retained memory tests pass against
  the new owner.
- URL and platform-file imports reserve before downloading and release after
  actual download, validation and storage work exits, including failure paths.
- The real failure paths are exercised against the real transport: consecutive
  tool calls on one session; a new connection and session for every call; a
  `DELETE /mcp` or a dropped connection immediately after a call; and text tool
  calls that proceed while a slow image transfer holds its reservation. Within the
  retained resource limits, these calls produce no persistent capacity refusal;
  legitimate budget exhaustion remains distinguishable from malformed input.
  After repeated batches, request state and image reservations are released;
  sessions disappear on DELETE or configured idle expiry, and post-cleanup heap
  use stabilizes rather than growing with completed calls. Servlet thread count
  alone is not evidence of cleanup. This verifies the tested topology, not sizing.
- Caller-visible refusal results and diagnostics agree on `code` and `reason`.
  Tool refusals remain understandable after SSE headers are committed; wait hints
  are optional, and unconfirmed writes still require inspection before replay.
- Tests that exist only to prove the old gate returned its permits are deleted;
  the image-budget and cancellation checks that pin reservation lifetime through
  an SSE write, a timeout and a disconnect are retained against the new owners.
- Java style, module boundary and repository document checks pass; the usage
  documents and the acceptance scripts do not mention Base64 input.

## Risks

- A programmatic caller that transcribes Base64 stops working. Before 1.0 this is
  accepted; the raw upload route is the replacement and is already documented.
- Moving the image reservation out of the filter can reintroduce the memory
  exposure the filter was written to close if the new owner releases before the
  SSE write finishes. The retained tests for cancelled blocking writes and late
  callbacks are the guard, and the change is not complete until they pass at the
  new location.
- The 128 KiB bound caps one request body, not the total retained heap. Async
  requests, queued work and response buffers can outlive servlet threads. Keep
  the existing session and work bounds, and verify reclamation through the real
  transport before claiming an aggregate memory envelope. A future tool needing
  a larger body requires its own transfer route rather than a larger MCP bound.

## Related decisions

- [Local execution supervisor](2026-09-05-local-execution-supervisor.md): its MCP
  paragraph defines the slots this note removes; the executor admission and
  worker contract it describes are unchanged and remain the concurrency bound
  for `repo_exec`.
- [MCP image transfers](../implemented/2026-09-15-mcp-image-transfers.md): the
  transfer routes, grants, ledger and upload collection reservations remain the
  image ingestion contract; only its Base64 clause is reversed here.
- [Repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md):
  the process-wide image memory budget and its shares remain as specified.
- [CodeAct MCP entrance](../implemented/2026-09-10-codeact-mcp-entrance.md): tool
  ownership and the session contract are unchanged.
