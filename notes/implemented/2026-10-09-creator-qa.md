# Account-scoped questions over public articles

Date: 2026-10-09

## Problem

The [public plaza](2026-10-09-plaza-foundation.md) exposes bounded search and reading,
but an ordinary website reader cannot ask it to gather supporting articles. A
model-driven entrance also introduces paid work, untrusted source instructions and
answers that can outlive the publication state they describe.

## Decision

The Java QA module owns a finite read-only model loop. The plaza supplies public
search/read and candy ports, preserving one reading boundary without a dependency
cycle. Current creators and administrators can use the browser entrance; machine
wishes also require the holder's personal consent. Personality changes expression,
not available tools or checks. The [QA reference](../../docs/qa.md) owns configuration,
wire behavior, limits and user-visible failure handling.

Account and shared-budget transactions reserve the complete run before any paid
call. Every dispatch is recorded first. Unknown outcomes consume that call's
conservative reservation rather than returning possibly spent money to admission.
Known token reports settle against configured peak uncached prices. Failed wishes
refund candy independently of money already spent. Account/request identities
remain in PostgreSQL so restart or deletion of transient text cannot allow replay.
Database transactions never span upstream or public-content I/O.

Native Anthropic and DeepSeek adapters share that ledger. Server-owned models,
prices and credentials keep browser selection from bypassing cost admission.
Anthropic additionally reserves a shared UTC monthly budget under the same lock.
An insufficient month selects DeepSeek before dispatch; errors after dispatch
never trigger fallback. Provider, model, price and original billing period belong
to the request, including clarification and duplicate delivery. This prevents
concurrent overspend and avoids paying two providers for one uncertain operation.

Provider thinking is enabled, with Claude's public summary and DeepSeek's returned
reasoning shown alongside tool activity. Opaque Claude signatures and full
provider turns stay only in transient replay state. Read-only progress polling
cannot dispatch a model. Text remains subject to the same expiry boundary;
ordinary model prose cannot bypass the citation tool.

Clarification is a structured tool result that releases model concurrency and
waits for an explicit, revision-bound answer. Its bounded text is held only in
memory until continuation, completion or expiry. Losing that state ends the
question; it never reconstructs a prompt and repeats a paid call. Completed text
is not retained, so an interrupted final response is recoverable only as status
and usage. That limitation is deliberate.

The model supplies paragraphs, source identities and exact quotes; the platform
supplies links after rereading the current public source. Quote fidelity does not
prove semantic entailment, so human evaluation remains necessary. Sources cannot
register tools or authorize writes. No shell or author game runtime participates.
The [plaza retrieval decision](2026-10-09-plaza-foundation.md) continues to own the
conditions for adding an index or embeddings; this feature establishes no ranking
over conventional RAG.

## Alternatives

Persisting conversation histories would recover responses after restart, but adds
private text retention and deletion duties without a current product need. Blindly
retrying a lost provider response risks a second paid operation. Reserving only an
average request price cannot bound a multi-turn question or missing usage. Waiting
inside a model execution slot for a user choice wastes scarce concurrency.

## Consequences and verification

Worst-case reservations temporarily reduce available daily and monthly budgets,
so a small Claude remainder can select DeepSeek. Peak prices
overestimate discounted calls. Metadata storage grows with completed requests;
removing those identities would require a separate replay policy. Transient
continuations can be lost, while their already dispatched work remains accounted
for. Site-wide retrieval still depends on complete, valid cached snapshots and
explicitly refuses excess capacity.

`QaIntegrationIT`, `QaConversationTests`, `AnthropicQaModelTests`, `DeepSeekQaModelTests`,
`PlazaQaSourcesTests` and the [site-corpus acceptance](../../acceptance/qa/README.md)
pin these boundaries. Chrome verifies DeepSeek selection, public thinking/tool disclosure and cited answers
through the real provider. Anthropic interoperability and delivery remain pending.
The plaza foundation, community, [game](2026-10-09-pocket-games.md), public delivery,
repository authority and independent retrieval records retain their respective
scopes. This decision partially supersedes the requirements'
deferred QA target; anonymous/general-user QA remains unavailable.
