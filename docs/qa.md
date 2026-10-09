# Creator questions

Open **许愿井** on the home navigation, or visit `/ask`. Current creators and
administrators can ask about public articles. Anonymous readers and other account
groups cannot call the question API. Model configuration enables normal operation;
there is no separate paid-access activation step.

Questions and the public passages retrieved for them go to the configured model
provider. Finished questions, answers and tool transcripts are not stored. The
page retains its current answer until navigation. PostgreSQL retains account/request
identities, status, usage, conservative cost and error category for accounting and
deduplication; those rows have no automatic deletion policy.

## Reading, answers and clarification

QA uses the [plaza's current public search and reading boundary](plaza.md). It has
no shell, private repository, history, game, comment or spending tool. Article text
is untrusted. The Java loop executes multiple search/read calls in one model turn
sequentially, counting each toward the same question's tool limit. Tags and literal
keywords are available; this entrance does not use embeddings or reranking.

Answers contain plain text, clickable article links and exact quoted passages.
Before returning a citation, the platform rereads its current public page and
checks that the read text and quoted passage still match. Source changes require
another read; withdrawn or unavailable content cannot supply a citation. This
checks provenance and quote fidelity, not whether every model inference follows
logically from its quotation. Answers explicitly disclaim exhaustive site counts.
A capacity refusal returns no sampled total or partial success.

When scope is unclear, the model can return two to four choices. Nothing is
preselected, and a reader may write a different clarification. Selecting or typing
does not call the model until **按这个范围继续** is submitted. At most two
clarifications belong to one question. The continuation expires after ten minutes;
waiting releases model concurrency, retaining only its remaining fee reservation.
Restart loses the transient conversation and ends it without replaying calls.

Each active HTTP request has the configured time limit, with at most three active
segments across two clarifications. The overall model and tool allowances never
reset on clarification. UTC rollover expires waiting questions and ends an active
question after its already sent call returns or times out, preserving concurrency
and the original day's fee reservation until then. Text expiry runs independently of database
availability; pending billing settlement resumes when the database is reachable.

## Allowances, candy and uncertain requests

The default web allowance is five questions per account per UTC day. Wishes use
candy instead, with the same global model concurrency and daily fee budget. All
entrances recheck current creator eligibility. Machine wishes additionally require
the holder's `WISH` grant and current credential/workspace authorization.

| `wander` action | Result |
| --- | --- |
| `wish "<question>" <request-UUID>` | Reserve one candy and start a question |
| `wish --status <request-UUID>` | Read status or an outstanding clarification without a model call |
| `wish --answer <request-UUID> <revision> "<answer>"` | Continue that clarification without another candy or question allowance |

The request ID belongs to the account and entrance. Only the original machine
credential can continue its wish; another credential does not inherit its text.
Repeat an uncertain request with the original ID. A completed duplicate returns
usage and status without another model call or retained answer. The web interface
offers explicit status checks and retries; it never automatically resends a paid
operation. A lost completed response cannot be reconstructed from server storage.

Before dispatch, the account transaction reserves the entire run's worst-case
cost and, for a wish, one candy. Every model call is durably marked before sending.
Known usage settles at configured uncached input/output prices; cache and off-peak
discounts are not assumed. Missing usage, interrupted responses and uncertain
outcomes consume the whole conservative bound for that call. These amounts are
budget estimates, not a provider invoice. Unused reservations are released.

Failure refunds the wish's candy exactly once. Calls already sent, their fee
estimate and the web question allowance remain consumed. A failure before model
dispatch consumes neither a model call nor a web question. Expiry and restart
cannot make an unknown paid call eligible for replay. Account revocation prevents
further reads/calls and final answer delivery; an already sent request can still
finish at the provider and remains accounted for.

## Configuration and bounds

Secrets stay in the server environment. Configuration names below use Spring
properties; uppercase environment equivalents are accepted.

| Property | Default |
| --- | --- |
| `poketto.qa.enabled` | `true`; a nonempty key is also required |
| `poketto.qa.api-key` | `DEEPSEEK_API_KEY`, otherwise unavailable |
| `poketto.qa.base-url` | `DEEPSEEK_BASE_URL`, otherwise `https://api.deepseek.com` |
| `poketto.qa.model` | `deepseek-flash` |
| `poketto.qa.daily-questions` | `5` |
| `poketto.qa.daily-usd` | `2` |
| `poketto.qa.max-concurrency` | `2` |
| `poketto.qa.max-rounds` | `6` |
| `poketto.qa.max-output-tokens` | `2048` |
| `poketto.qa.timeout-seconds` | `90` per active request; maximum `120` |
| `poketto.qa.input-usd-per-million` | `0.30` |
| `poketto.qa.output-usd-per-million` | `1.20` |
| `poketto.qa.personality` | Empty; at most 2000 characters, expression only |

The default prices use the provider's [peak uncached Flash rates](https://api-docs.deepseek.com/quick_start/pricing/)
checked on 2026-10-09. Verify prices when changing the model/provider or after a
provider price change. Hard fee admission assumes those configured upper prices
and provider token limits remain valid. HTTPS is required and redirects are
disabled. Thinking is disabled and structured tools are required under the
[DeepSeek completion protocol](https://api-docs.deepseek.com/api/create-chat-completion/).

The standard Compose `.env` and existing-installation updater accept these `POKETTO_QA_`
settings and the plaza switches. Ordinary image updates retain them; the GitHub
identity-setting workflow does not copy its review credential into QA. Set the
provider key explicitly in protected host configuration. Existing installations
need the matching updater before passing the new settings.

HTTP question bodies are limited to 16 KiB before JSON parsing; questions allow
4000 UTF-8 bytes and clarification answers 2000. Each serialized upstream request,
including schemas, is at most 64 KiB. Its conservative input token bound adds 4096
tokens of framing allowance. Upstream bodies are capped at 128 KiB during receipt;
each call has a 45-second deadline within the active request's remaining time.
Six default calls reserve at most $0.140088. Input/output token reports outside the
configured bounds are treated as uncertain, never as free usage.

A question may execute 16 tools, retain 16 source pages and return 32 KiB of answer
text plus citations. At most 64 questions may be running or awaiting clarification
across the instance; only running questions consume model concurrency. These
limits cover the complete result rather than silently shortening a source.

## Verification

`QaIntegrationIT` covers the real PostgreSQL and account boundary;
`DeepSeekQaModelTests` exercise bounded HTTP, malformed responses and no retries;
`QaConversationTests`, `PlazaQaSourcesTests` and the question component tests cover
tool turns, evidence, fixed retrieval cases and explicit user choices.
[Site QA acceptance](../acceptance/qa/README.md) owns the fixed corpus, paid
entrance and result report. It does not start the independent FiQA experiment.
