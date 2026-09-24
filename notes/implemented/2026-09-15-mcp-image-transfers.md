# MCP Image Transfers

Date: 2026-09-15

## Problem

An image in a client's filesystem is not visible to a remote MCP server. Requiring
the model to transcribe Base64 makes otherwise small uploads slow and unreliable.

## Decision

Keep `put_asset` as the image ingestion primitive. Accept a public HTTPS image URL
or a platform-supplied file object. OpenAI file parameter metadata marks the file
object; other clients may supply a URL directly. Never interpret local paths or
client-only links as remote download addresses.

Clients holding bytes may instead request a temporary upload URL through the same
tool and send a raw HTTP PUT from their own execution environment. The grant binds
the original authenticated principal, workspace and operation key. It grants no
read, publication or repository-write capability. Recheck current authorization
when using the grant. Do not give the client a long-lived API key.

Both routes use the existing bounded image validator and durable, workspace-scoped
upload ledger. Return the same immutable asset receipt; `media link` and `save`
remain separate. The original programmatic Base64 input is withdrawn by
[MCP request admission removal](2026-09-15-mcp-request-admission-removal.md);
callers holding bytes use a raw upload grant.

## Boundaries and alternatives

Download only public HTTPS destinations, validate all DNS answers at the actual
connection boundary, and repeat validation on redirects. Bound bytes, redirects,
time and concurrent work. Do not forward OAuth credentials or cookies to sources.
Temporary upload grants are bounded, expire after 15 minutes, and do not survive
application restart; the durable upload ledger still allows identical retries
with the same operation key after obtaining a new grant.
The instance retains at most 512 grants. An account may retain at most 64,
including completed receipts, with at most 8 unfinished uploads. Completed grants
still support receipt recovery and identical retries; the account total prevents
one account's completed uploads from exhausting the shared grant table. Expired,
inactive grants are reclaimed before allocating another grant.
Raw upload collection admits four accounts at a time, one request per account.
Each collector reserves 32 MiB for its bounded 16 MiB buffer and completion copy,
leaving a browser share in the default 256 MiB image budget. After collection,
validation and storage reserve another 128 MiB for their actual work. Grant-only
MCP requests need no image reservation and remain callable during collection.
Busy uploads fail promptly with `TRANSFER_BUSY`; collection slots and buffer
reservations release together after response completion and producer exit.

Third-party staging storage adds an unnecessary copy and dependency. Requiring
only local paths cannot cross client/server filesystems. Separate business CRUD
tools do not address binary transfer. OpenAI metadata is a client extension, not
a promise that every MCP client can expose attachments or generated images.

Upload bodies use Servlet nonblocking reads with a 30-second collection deadline,
and release image admission on timeout or disconnect. A reverse proxy can delay
an early timeout response while the sender leaves its request body incomplete;
clients should bound their own request and GET the upload URL to check the receipt.
A timeout does not invalidate the grant or authorize replaying different bytes.

## Related decisions

[CodeAct media](2026-09-09-codeact-content-and-media.md),
[indexed media delivery](2026-09-09-indexed-media-delivery.md),
[authoring foundations](2026-09-05-repository-authoring-foundations.md)
and the [MCP entrance](2026-09-10-codeact-mcp-entrance.md) keep their storage,
publication, authorization and tool ownership. Session artifacts remain an output
mechanism and never become upload authority.

## Verification

`McpProtocolIntegrationIT`, `McpPutAssetInputTests` and `ImageTransfersTests`
pin the tool metadata, denial, revocation, grant capacity, expiry and
network-destination checks. The [HTTP/MCP receipt](../../acceptance/evidence/2026-09-15-image-transfers.json)
records raw PUT and public HTTPS import through real Spring, PostgreSQL and Caddy.
It covers platform file-object input, not automatic ChatGPT file forwarding or a
Claude app upload, and includes no live redirect fixture.

## Text input and bootstrap scope

The CLI's stdin and UTF-8 input-file options for `create` and exact `edit` keep
their absence, original-match and compare-and-replace checks and the existing
command and frame bounds, and reject malformed or oversized input before sending
a mutation. They refine the CLI contract rather than adding MCP CRUD tools.
