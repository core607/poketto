# Service Diagnostics

Date: 2026-09-14

## Problem

A failure that reached a caller left nothing behind. `repo_exec` refusals returned a structured code to the client while the service recorded neither the refusal nor its cause, so the only account of a failed call lived in that client's transcript. The unexpected-failure handler recorded a stack trace and returned "an unexpected error occurred", with nothing tying the two together.

The whole application held 47 log statements: 38 warnings, 4 errors, 3 debug and 2 info. The `auth` package held none, so sign-in failures, throttling, invitation redemption, permission changes, key revocation, consent and credential rotation were all silent. Container logs also used the default Docker driver, so a redeploy discarded them; on a repository where every verified commit deploys, that can be minutes.

The reader is usually an agent working from one error string, not a person watching a console. That decides the shape: an outcome must be addressable from what the caller already holds, one event must be one line, and field names must be predictable enough to filter without sampling the format first.

## Decision

Every HTTP request and every MCP tool call produces one record.

`RequestDiagnosticsFilter` runs ahead of admission, origin and authentication filters, so a request refused before reaching a controller is still recorded. It names the method, route, status, duration, caller kind and subject, and the workspace when the route selects one. An asynchronous request completes on another thread, so the record is written from a completion listener using values captured on the request thread rather than thread-local context; if the dispatch has already finished, the record is written directly rather than letting a diagnostic throw out of a request that succeeded.

That outer position cannot read the caller. The security chain clears its context before returning, and a request refused during authorization never reaches the end of that chain at all, so an identity read after the fact is always anonymous even when the account or key was recognised. `RequestCaller` therefore remembers the kind and subject on the request at the moment the identity filter recognises it, before any refusal is written; the request attribute also survives an asynchronous dispatch. Recording a refused request as anonymous when it arrived with a valid session or key would send a reader looking for a leaked credential.

`McpToolOutcomes` wraps the single dispatch point every tool shares. It records the tool, the duration, and the outcome code read back from the result. Reading the code from the body rather than accepting it as a parameter also covers an operation that returns a refusal of its own without raising. A refusal built without a code is recorded as `UNREPORTED` rather than omitted, because that is a defect in the producer.

`ProblemResponses` records the status and title of every mapped failure at its one shared construction point.

`AuditRecords` covers the changes that decide who can do what. The request record already names who called which operation and with what status, so these add only what a route cannot say: whether a credential was actually valid, which member or key an operation acted on, and the capabilities a subject holds afterwards. Authentication outcomes, key issue and revocation, invitation issue, revocation and redemption, and membership changes are recorded. A membership record states the capabilities that actually apply, computed the same way authorization computes them, and a deactivation is recorded as a withdrawal rather than as a grant of the permissions that were requested: a record claiming an access the subject does not hold is worse than no record. Each record is written after its transaction commits, so a failed commit cannot leave behind an account of a key that was never issued or never revoked. They share the logger name `poketto.audit` so the security history can be selected without knowing which class wrote each line, and each carries an action naming what happened so a reader filters on that rather than on a message.

Subjects are named by identifier. A login name, password, token or invitation code never appears: a record of a failed sign-in carrying the attempted name would become a list of account names to try, and a record carrying a code would hand over the credential it describes. A refusal carries this service's own fixed reason, never the submitted value. Capabilities are sorted so two records can be compared directly.

A request identifier is generated per request, placed in the logging context so other lines on that thread carry it, and never returned to a caller. An identifier that only the operator can redeem gives an external client nothing, and an unexplained token invites a model to invent a use for it. Correlation with a reported failure therefore uses what both sides already hold: workspace, caller and time. That key loses precision as concurrent activity grows; the answer then is more recorded dimensions, not a returned identifier.

`spring.application.name` names the service in structured records. A deployment sets `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` to emit one JSON line per record; leaving it unset keeps the readable console format. Nothing depends on that variable being set, so an operator-owned installation that does not set it keeps working with plain text.

## What is never recorded

Request bodies carry repository tokens and account passwords. MCP tool arguments carry commands and document content. Query strings and repository paths name private material. None of them reach a record. An admin route is reduced to its stable shape and its workspace identifier moved to a separate field, so a route never varies per workspace. Container health probes are excluded entirely: they run continuously and report nothing a reader acts on.

An image URL carries its authorization in the path. `/api/public/assets/{token}` and the private equivalent name a grant of 32 random bytes that anyone holding it can redeem for that image until it expires, so a recorded route containing one would hand the image to any reader of the log. Route segments are therefore collapsed by shape rather than against a list of known routes: a UUID becomes `:id` and a run of 24 or more URL-safe characters becomes `:opaque`. A route added later is covered without revisiting this filter. What survives is named explicitly instead, because failing to keep a readable segment costs legibility while failing to collapse an opaque one leaks a capability. A public site slug is the one such segment today; it is authored, already public, and distinguishes one space from another.

Recording a path digest instead of a path, so that repeated failures on one file can still be recognised, is accepted but not implemented here. It belongs with the per-file operations in content and execution.

## Alternatives

Returning an event identifier to the caller is the usual way to make a reported error addressable. It was rejected: the MCP caller is an external product user rather than an operator, no support path exists for redeeming an identifier, and adding an opaque token to a stable protocol surface invites misuse by a model. Logging paths verbatim would make media, move and save failures directly diagnosable at the cost of putting a member's private file names in an operator log; a uniform digest recovers most of that without the names, and applying it uniformly avoids a classifier mistake leaking one.

Silencing the MCP client-initialize records was considered and rejected. They dominated recent log volume, but they are also what showed that one client reconnects on every call.

## Consequences and remaining work

Log volume rises by roughly one line per request and per tool call. Structured output is opt-in, so an existing installation is unaffected until it sets the variable.

Not covered here, in the order they matter: retention and delivery, meaning the journald log driver, journald limits and a gateway access log, none of which reach an operator-owned Compose installation through image delivery; workspace creation, repository credential rotation and the website switch, which live in `spaces` and `workspace` and would need the audit helper to become public API; OAuth consent and disconnection, which issue and withdraw their own keys through this service; path digests for per-file operations; the worker's own records, which today amount to two statements.

## Verification

`RequestDiagnosticsFilterTests` covers the recorded route, status, caller and workspace, an authenticated caller taken from the request rather than a cleared context, public and private image grants absent from the record, a long public slug surviving uncollapsed, a server failure recorded at warning, an excluded health probe, a malformed admin route, and that a query string carrying a private path reaches no record. `McpToolOutcomeTests` covers a refusal recorded with the code the caller received, a success recorded without its output, a refusal without a code, unreadable content, and that the result is returned unchanged. `RequestCallerTests` covers an account and an API key surviving the security context, an absent identity, and a request that reached no identity. `AuditRecordTests` covers a refusal naming only this service's own reason, a permission change naming actor, subject and resulting capabilities, a withdrawal that is not recorded as a grant, sorted capabilities, an authentication naming only the resolved identity, and an unauthenticated actor named rather than left blank.

These are unit checks on the record's shape. They do not establish behaviour under a real transport, and no deployed installation emits structured records until an operator sets the variable.
