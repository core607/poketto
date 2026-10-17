# Stateless MCP Transport

Date: 2026-10-18

## Problem

The Streamable HTTP server kept a session per `initialize`: at most 128 site-wide, each closed after 30 idle minutes, each allowing four active tool calls. Nothing durable depended on those sessions, because [account working copies](2026-09-14-account-working-copies.md) belong to the account and workspace, not to a transport. The sessions still limited agents in three ways:

- A client idle for more than 30 minutes got HTTP 404 on its next call. Some clients fail that call before they reconnect.
- A client that initializes for every call, as the ChatGPT connector did, left one orphaned session per call for 30 minutes. A few such clients could exhaust the 128 sessions for everyone.
- Every subagent of one Claude Code process shares that process's session, so the fifth concurrent call was refused with "MCP request id or capacity unavailable".

At the target of hundreds of agents working at once, the session table becomes the first site-wide bound, ahead of execution capacity.

## Decision

`/mcp` serves the SDK's stateless protocol through `StatelessMcpTransport`:

- Every POST is one JSON-RPC message, answered with one JSON response on the request thread. `initialize` issues no `Mcp-Session-Id`, and a header that a client sends anyway is ignored. GET and DELETE return 405 with `Allow: POST`.
- Each request carries its own API key. `McpIdentities` resolves the key and its workspace from the security context, and every tool call reauthorizes. No per-transport state can carry one key's identity or workspace into another request.
- `McpCalls` registers each tool call in flight under the calling key's subject ID and the client's JSON-RPC ID, tagged by type so that `1` and `"1"` differ. A `notifications/cancelled` from the same key cancels the call, and a key can never cancel another key's call. An ID that is still in flight is refused with JSON-RPC error -32600. Once its call ends, the ID can be reused. Credential revocation and shutdown cancel the calls they affect, and the executor still stops affected leases on its own.
- Image reads reserve the MCP image share before the tool runs and hold it through the blocking write of the response bytes ([request admission](2026-09-15-mcp-request-admission-removal.md) owns the budget).
- The SDK only exposes `clientInfo` in a session. Plaza therefore takes the client's self-reported name from the first product token of the `User-Agent` header, still marked self-reported.

There is no session count, idle expiry or per-session call limit. The bounds that remain are the request body bound, the servlet's request threads, and [execution admission](2026-09-14-account-working-copies.md).

## Alternatives

- **Raise the session cap and the per-session limit, and shorten the idle window.** The cap and the expiry 404 would remain, and a shorter window would expire more legitimate idle clients.
- **Evict the least recently used session when the table is full.** This keeps a table whose only effect is to break the client being evicted. It was not adopted.
- **Keep sessions for server-to-client messages.** Poketto sends no sampling requests, progress, logging or list-changed notifications (`tool-change-notification=false`), so a session would carry nothing.

## Consequences

- The server cannot start a request or a notification toward a client. A future feature that needs one has to bring back a stream bound to the request that wants it, not a site-wide session table.
- If a client loses its connection without sending a cancellation, the call runs to completion, within the command's 60-second bound. Cancellation now needs both the key and the request ID, where before the session alone was enough.
- Each tool call still holds one servlet request thread for its whole duration, so the thread pool now bounds how many calls run at once.

## Verification

`McpBoundsTests`, `McpCancellationTests` and `McpImageMemoryTests` exercise the transport's routing and refusals, the cancellation pairing, and how long the image budget is held. `McpProtocolIntegrationIT` pins the session-less initialization, per-key authorization, 405 for DELETE, and revocation over real HTTP and PostgreSQL.
