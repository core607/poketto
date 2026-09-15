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
remain separate. Base64 remains available for programmatic callers, not as the
recommended model-generated input.

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

Third-party staging storage adds an unnecessary copy and dependency. Requiring
only local paths cannot cross client/server filesystems. Separate business CRUD
tools do not address binary transfer. OpenAI metadata is a client extension, not
a promise that every MCP client can expose attachments or generated images.

## Related decisions

Retain [account working copies](2026-09-14-account-working-copies.md), including
its exact-match editing and synchronization contracts.

Retain [CodeAct media](2026-09-09-codeact-content-and-media.md),
[indexed media delivery](2026-09-09-indexed-media-delivery.md),
[authoring foundations](2026-09-05-repository-authoring-foundations.md),
and the [MCP entrance](2026-09-10-codeact-mcp-entrance.md): their storage,
publication, authorization and tool ownership remain applicable. Session artifacts
remain an output mechanism and do not become upload authority. No same-topic
proposal is superseded.

## Verification

The [HTTP/MCP receipt](../../acceptance/evidence/2026-09-15-image-transfers.json)
records raw PUT and public HTTPS import, exact managed-image readback, identical
retries, changed-byte conflicts, invalid images, private-source refusal, key
revocation, and admission release after a stalled upload. The probe uses real
Spring, PostgreSQL, immutable originals and Caddy with an explicitly trusted
fixture certificate. It verifies platform file-object input, not automatic
ChatGPT file forwarding or a Claude app upload.

`McpProtocolIntegrationIT` verifies the real tool catalog/file metadata,
HTTP security boundary, denial and revocation. Focused unit checks cover expiry,
body bounds, per-account capacity after completed uploads, expiry reclamation and
private/special-use network destinations. Redirect destinations
use the same URI and connection-time DNS validation; a live redirect fixture is
not part of this receipt. `executorServiceTests` passes 78 Linux checks, including
large Chinese text with quotes, preserved final newlines and pre-send rejection
of oversized input through the actual CLI/FIFO bridge. Java style, module and
repository-document checks pass. Production deployment and external-client
acceptance are separate from these fixtures.

Upload bodies use Servlet nonblocking reads with a 30-second collection deadline,
and release image admission on timeout or disconnect. A reverse proxy can delay
an early timeout response while the sender leaves its request body incomplete;
clients should bound their own request and GET the upload URL to check the receipt.
A timeout does not invalidate the grant or authorize replaying different bytes.

## Text input and bootstrap scope

The CLI provides stdin and UTF-8 input-file options to the existing create and exact-edit CLI
commands. It preserves local absence, original-match and compare-and-replace checks,
final newlines and existing command/frame bounds. It rejects malformed or oversized
inputs before sending a mutation. The CLI help and template guide give the shortest
complete routes for text, client-held images, media linking, save and remote
readback. These refine the existing CLI contract rather than adding MCP CRUD tools.
