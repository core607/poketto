# MCP Request Admission Removal

Date: 2026-09-15
Status: Proposed

## Problem

Every `POST /mcp` passes through `McpBodyLimitFilter` after Spring Security has
already rejected unauthenticated callers. The filter holds three process-wide
semaphores — four data slots, four control slots for `notifications/initialized`
and `notifications/cancelled`, and eight prefix readers — and reserves the whole
MCP image budget before dispatching `get_asset`, `put_asset`, `get_artifact` or
any body over 16 KiB. A permit is returned when the request's synchronous chain
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
After roughly seventy session POSTs the data-slot semaphore held no permits while
no request was in flight and every servlet worker thread was idle. From then on
every authenticated MCP call from every client received 429 until the process
was restarted. The rejection carried no `Retry-After` and no gate identity, and
the request diagnostics recorded only the status code. In the author's judgment
the gate protects nothing the system needs, penalizes the owner's own agents
for a body shape they never send, and would need a lease watchdog, per-caller
buckets and richer diagnostics to become trustworthy — all of it maintenance of
a mechanism whose reason has gone.

## Proposal

Remove the reason, then the mechanism.

1. Withdraw the `base64` input from `put_asset`. The URL, platform file and raw
   upload routes remain the only ways to store an image through MCP. This
   reverses the "remains available for programmatic callers" clause of the image
   transfer decision; programmatic callers use the raw upload URL like any other
   client holding bytes.
2. Reduce `McpBodyLimitFilter` to two checks that need no memory budget: a
   declared or streamed body over 128 KiB receives 413 before dispatch, and the
   existing streaming envelope preflight (4,096 tokens, 32 nested containers,
   bounded request identifiers and tool names) still refuses malformed structure
   before the SDK builds an argument tree. 128 KiB holds a 16,384-character
   command at four bytes per character with JSON escaping. At that size the whole
   body is read into a byte array without reservation; the worst case across the
   servlet thread pool is a few tens of mebibytes.
3. Delete the three semaphores, the async listener that returns their permits,
   the control-frame classification and the tool-name sniff. Concurrency is
   bounded where the work is: the servlet container's thread pool, the executor's
   own session admission (`poketto.executor.max-sessions`, default 4), and the
   image memory budget below.
4. Keep image memory admission, but at the layer that produces image bytes.
   After step 1 the only MCP work that holds image bytes is a `get_asset` or
   `get_artifact` response. The transport already propagates the reservation
   into the tool exchange and releases it after the actual SSE write; that
   ownership moves from the body filter to the MCP tool and session layer, with
   the same lifetime contract: reserved before bytes are read, held through the
   response write, released on timeout or disconnect, never released while the
   producer is still writing. Raw upload collection and browser image work keep
   their existing reservations unchanged.
5. A remaining refusal names its bound. Executor admission and image-budget
   rejections state which bound refused in the request diagnostics record and
   send `Retry-After` when waiting can help, so a client that is refused can tell
   a full executor from a malformed request.

## Implementation scope and dependencies

Application: `McpBodyLimitFilter`, `McpEnvelopeBounds`, `PutAssetInput`, the
`put_asset` schema in `RepositoryMcpTools`, the reservation hand-off in
`McpTransportConfiguration` and `CancellableMcpSession`. Tests: `McpBoundsTests`
keeps declared, chunked and repeated-stream-access coverage at the new bound;
`McpImageMemoryTests` cases that exist only to prove slot release are deleted,
and the cases that pin the image reservation through an SSE write, timeout and
disconnect move with the reservation to its new owner. Acceptance:
`acceptance/image-memory-smoke.py` stores its image through the raw upload URL
instead of Base64; `acceptance/image-transfer-smoke.py` drops its Base64
assertions. Documents: `docs/usage.md` and `docs/usage.zh.md` describe `put_asset`
as accepting exactly one of `url`, `file` or an upload grant, and the supervisor
record's MCP paragraph is superseded by this note for the body bound and slots.

No schema, content-format or deployment change is involved. The phase clause in
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

## Acceptance

- A `POST /mcp` body over 128 KiB receives 413 before the SDK dispatches it,
  whether the length is declared or chunked.
- `McpBodyLimitFilter` contains no semaphore and no async listener; reading the
  filter shows only the size bound and the envelope preflight.
- The `put_asset` tool schema offers `url`, `file` and `mode` and no `base64`
  property; a request carrying `base64` is refused as an unknown argument.
- `get_asset` and `get_artifact` still reserve the image budget before reading
  bytes and hold it through the SSE write; the retained memory tests pass against
  the new owner.
- A soak with four concurrent clients, two of them opening a session per call,
  runs at least one hundred tool calls each without a 429 from `/mcp` and with
  heap returning to its idle level afterwards. The soak is evidence for this
  instance's topology, not a sizing claim.
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
- Without slots, a burst of session POSTs is bounded only by the servlet thread
  pool. Each request buffers at most 128 KiB, so the exposure is bounded and
  small; if a future tool needs a larger body, it needs its own transfer route
  rather than a larger MCP bound.

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
