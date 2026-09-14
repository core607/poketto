# Service Diagnostics

Date: 2026-09-14

## Problem

A failure that reached a caller left nothing behind. `repo_exec` refusals returned a structured code to the client while the service recorded neither the refusal nor its cause, so the only account of a failed call lived in that client's transcript. The unexpected-failure handler recorded a stack trace and returned "an unexpected error occurred", with nothing tying the two together.

The whole application held 47 log statements: 38 warnings, 4 errors, 3 debug and 2 info. The `auth` package held none, so sign-in failures, throttling, invitation redemption, permission changes, key revocation, consent and credential rotation were all silent. Container logs also used the default Docker driver, so a redeploy discarded them; on a repository where every verified commit deploys, that can be minutes.

The reader is usually an agent working from one error string, not a person watching a console. That decides the shape: an outcome must be addressable from what the caller already holds, one event must be one line, and field names must be predictable enough to filter without sampling the format first.

## Decision

Every HTTP request and every MCP tool call produces one record.

`RequestDiagnosticsFilter` runs ahead of admission, origin and authentication filters, so a request refused before reaching a controller is still recorded. It names the method, route, status, duration, caller kind and subject, and the workspace when the route selects one. An asynchronous request completes on another thread, so the record is written from a completion listener using values captured on the request thread rather than thread-local context.

`McpToolOutcomes` wraps the single dispatch point every tool shares. It records the tool, the duration, and the outcome code read back from the result. Reading the code from the body rather than accepting it as a parameter also covers an operation that returns a refusal of its own without raising. A refusal built without a code is recorded as `UNREPORTED` rather than omitted, because that is a defect in the producer.

`ProblemResponses` records the status and title of every mapped failure at its one shared construction point.

A request identifier is generated per request, placed in the logging context so other lines on that thread carry it, and never returned to a caller. An identifier that only the operator can redeem gives an external client nothing, and an unexplained token invites a model to invent a use for it. Correlation with a reported failure therefore uses what both sides already hold: workspace, caller and time. That key loses precision as concurrent activity grows; the answer then is more recorded dimensions, not a returned identifier.

`spring.application.name` names the service in structured records. A deployment sets `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` to emit one JSON line per record; leaving it unset keeps the readable console format. Nothing depends on that variable being set, so an operator-owned installation that does not set it keeps working with plain text.

## What is never recorded

Request bodies carry repository tokens and account passwords. MCP tool arguments carry commands and document content. Query strings and repository paths name private material. None of them reach a record. An admin route is reduced to its stable shape and its workspace identifier moved to a separate field, so a route never varies per workspace. Container health probes are excluded entirely: they run continuously and report nothing a reader acts on.

Recording a path digest instead of a path, so that repeated failures on one file can still be recognised, is accepted but not implemented here. It belongs with the per-file operations in content and execution.

## Alternatives

Returning an event identifier to the caller is the usual way to make a reported error addressable. It was rejected: the MCP caller is an external product user rather than an operator, no support path exists for redeeming an identifier, and adding an opaque token to a stable protocol surface invites misuse by a model. Logging paths verbatim would make media, move and save failures directly diagnosable at the cost of putting a member's private file names in an operator log; a uniform digest recovers most of that without the names, and applying it uniformly avoids a classifier mistake leaking one.

Silencing the MCP client-initialize records was considered and rejected. They dominated recent log volume, but they are also what showed that one client reconnects on every call.

## Consequences and remaining work

Log volume rises by roughly one line per request and per tool call. Structured output is opt-in, so an existing installation is unaffected until it sets the variable.

Not covered here, in the order they matter: identity and permission events in `auth` and `spaces`, which still produce nothing; path digests for per-file operations; the worker's own records, which today amount to two statements; retention and delivery, meaning the journald log driver, journald limits and a gateway access log, none of which reach an operator-owned Compose installation through image delivery.

## Verification

`RequestDiagnosticsFilterTests` covers the recorded route, status, caller and workspace, a server failure recorded at warning, an excluded health probe, a malformed admin route, and that a query string carrying a private path reaches no record. `McpToolOutcomeTests` covers a refusal recorded with the code the caller received, a success recorded without its output, a refusal without a code, unreadable content, and that the result is returned unchanged.

These are unit checks on the record's shape. They do not establish behaviour under a real transport, and no deployed installation emits structured records until an operator sets the variable.
