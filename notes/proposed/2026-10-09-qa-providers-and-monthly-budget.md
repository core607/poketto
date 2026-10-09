# Configurable question models and monthly Claude spending

Date: 2026-10-09

## Problem

The [creator question loop](../implemented/2026-10-09-creator-qa.md) has one
DeepSeek adapter and a shared daily budget. Readers cannot select a configured
model, and that budget cannot cap a more expensive provider across a month.

## Proposal

Add the native Anthropic Messages protocol alongside DeepSeek. The browser offers
the server-configured model from each provider, with `claude-haiku-5-5` selected by
default. Credentials, endpoints, prices and allowed model IDs remain server-owned.
The Claude API key is configured independently from the DeepSeek key.

All accounts and entrances share a USD 10 Anthropic budget per UTC calendar month.
Reserve the selected question's complete bound in the same transaction as its
existing daily reservation. If the Claude month cannot admit it, choose DeepSeek
before dispatch and expose the actual model and reason. Unknown models and missing
credentials fail explicitly. Existing eligibility, web/candy allowances, global
daily spending and concurrency checks still apply to fallback.

Persist the selected provider, model and price bounds on the request. A question,
its clarification and duplicate requests keep that selection. Unknown upstream
outcomes consume their reservation and are never replayed through another provider.
Release unused month reservations on completion, failure or expiry; bill delayed
responses against their original month. Configuration changes cannot erase spending.

## Alternatives and consequences

Checking the provider dashboard after dispatch cannot prevent concurrent overspend.
Switching providers after a timeout risks paying twice. Reserving the complete run
may select DeepSeek while a small Claude balance remains, but keeps the hard limit.

## Verification and ownership

Protocol tests cover native tool turns and bounded HTTP. PostgreSQL integration
tests cover concurrent admission, fallback, replay, expiry, month rollover and
price snapshots. Browser acceptance covers selection and visible fallback.
Keep the existing QA rationale owner and consolidate this proposal into it when
implemented; plaza, games and the independent retrieval experiment retain scope.
