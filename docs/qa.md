# Creator questions

Open **许愿井** on the home navigation, or visit `/ask`. Current creators and
administrators can ask about public articles. Anonymous readers and other account
groups cannot call the question API. Model configuration enables normal operation;
there is no separate paid-access activation step.

`POKETTO_PLAZA_ENABLED` controls the MCP exploration entrance independently of web
QA. Disabling `POKETTO_QA_ENABLED` rejects question, continuation and allowance
requests; authenticated status reads and expiry/billing cleanup remain active so
previously reserved work can settle.

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
logically from its quotation. The model must qualify claims that exceed its search coverage.
A capacity refusal returns no sampled total or partial success.

When scope is unclear, the model can return two to four choices. Nothing is
preselected, and a reader may write a different clarification. Selecting or typing
does not call the model until **按这个范围继续** is submitted. At most two
clarifications belong to one question. The continuation expires after ten minutes;
waiting releases model concurrency, retaining only its remaining fee reservation.
Restart loses the transient conversation and ends it without replaying calls.

Each active HTTP request has the configured time limit, with at most three active
segments across two clarifications. The overall model and tool allowances never
reset on clarification. The first reached model, token or time limit ends the
active request; unused rounds do not extend its deadline. UTC rollover expires waiting questions and ends an active
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
usage and status without another model call or retained answer. The web interface polls read-only status while a request is active and offers
explicit retries after an uncertain result; it never automatically resends a paid
operation. A lost completed response cannot be reconstructed from server storage.

Before dispatch, the account transaction reserves the entire run's worst-case
cost and, for a wish, one candy. Every model call is durably marked before sending.
Known usage settles at the request's saved input/output prices. DeepSeek cache
and off-peak discounts are not assumed; Anthropic cache reads use the uncached
price and cache writes use twice that price conservatively. Missing usage, interrupted responses and uncertain
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
| `poketto.qa.enabled` | `true`; at least one provider key is required |
| `poketto.qa.prices-file` | Empty uses the bundled catalog; an explicit path must be valid at startup |
| `poketto.qa.default-provider` | `anthropic` |
| `poketto.qa.anthropic.api-key` | `ANTHROPIC_API_KEY`, otherwise unavailable |
| `poketto.qa.anthropic.base-url` | `https://api.anthropic.com` |
| `poketto.qa.anthropic.model` | `claude-haiku-5-5` |
| `poketto.qa.anthropic.monthly-usd` | `20`; shared by all accounts, UTC calendar month |
| `poketto.qa.deepseek.api-key` | `DEEPSEEK_API_KEY`, otherwise unavailable |
| `poketto.qa.deepseek.base-url` | `DEEPSEEK_BASE_URL`, otherwise `https://api.deepseek.com` |
| `poketto.qa.deepseek.model` | `deepseek-flash` |
| `poketto.qa.daily-questions` | `5` |
| `poketto.qa.daily-usd` | `2` |
| `poketto.qa.max-concurrency` | `2` |
| `poketto.qa.max-rounds` | `6` |
| `poketto.qa.max-output-tokens` | `8192`, including thinking; maximum `16384` |
| `poketto.qa.timeout-seconds` | `90` per active request; maximum `120` |
| `poketto.qa.personality` | Empty; at most 2000 characters, expression only |

The browser selects one server-configured model per provider. It defaults to
Haiku 5.5, shows unavailable credentials explicitly, and cannot supply arbitrary
model IDs, endpoints or prices. MCP wishes use the configured default provider.
A Claude question reserves its entire cost bound against both the daily and
monthly budgets. If the month cannot fit that reservation, admission selects
DeepSeek and the reply identifies the actual model and fallback reason. Monthly
budget exhaustion is the only automatic fallback trigger: missing keys and
provider failures do not silently switch models. Daily budget, account allowances
and concurrency still apply. Clarifications and repeated request IDs retain their
original selection and price snapshot; delayed responses bill the original month.
Unused reservations are released; changing configuration does not erase spending.

Anthropic `stop_reason: "refusal"` ends the request as `MODEL_REFUSED`, with its
reported usage settled and unused reservations released. It never executes tools
from that response or automatically changes providers. The page offers explicit
DeepSeek resubmission with a new request ID and allowance, or editing without a call.

Prices follow [Haiku 5.5's short-context tier](https://platform.claude.com/docs/en/models/haiku-5-5/overview)
and [DeepSeek's peak uncached Flash rates](https://api-docs.deepseek.com/quick_start/pricing/),
checked on 2026-10-09. This loop's input cap stays below Haiku's 100k-token price
threshold. Verify prices and protocol support when changing models. Hard admission
assumes the configured upper prices and provider token limits remain valid. HTTPS
is required and redirects are disabled.

Claude uses adaptive thinking with `display: "summarized"`; DeepSeek enables
thinking and returns its complete `reasoning_content`. The adapters retain the
provider's reasoning and opaque signatures for subsequent tool turns. Only public
thinking text enters browser activity; empty thinking turns have no card or count.
Tool rows summarize the action and result, with original arguments, complete
results and errors under **查看详情**. Elapsed time stays aligned across rows.
Status polling
shows the active step; thinking text arrives when that model turn finishes, not as
a token stream. These traces stay in memory with the question and disappear on
completion or expiry; the current page keeps its received copy. Limits reject
oversized activity rather than silently truncating it. The loop uses automatic
tool choice because forced tool use suppresses or rejects thinking; an ordinary
text completion must continue through the bounded citation/answer tool.

Spring AI owns provider messages, tool schemas and signed thinking replay. Its
Anthropic conversation-history caching strategy places five-minute
`cache_control: {"type":"ephemeral"}` breakpoints on the prompt and final tool
result. Cache hits still depend on minimum length and an exact prefix match.
The application executes tools and records each HTTP dispatch; framework retries
and redirects are disabled. An SDK transport adapter retains these limits and
handles Spring AI 2.0.1's empty-content branch before it discards refusal/usage.

The [price catalog](../src/main/resources/qa/prices.json) has four USD-per-million
rates for every configured provider/model: `input`, `cacheRead`, `cacheWrite` and
`output`. At admission, each question snapshots all four rates and reserves the
maximum possible input/output cost. Each completed call settles its actual token
categories at those rates, rounding the sum up to one micro-dollar. Anthropic's
`input_tokens` excludes cache tokens; DeepSeek's `prompt_tokens` includes them.
A cache token is never also billed as ordinary input. The UI's estimate uses these
configured rates, not an invoice; missing/ambiguous usage retains the call bound.
DeepSeek defaults use peak rates; off-peak discounts are not inferred.

To hot-update prices, copy the catalog into the already mounted data directory,
for example `qa-pricing/prices.json`, and set `POKETTO_QA_PRICES_FILE` to its
container path `/var/lib/poketto/qa-pricing/prices.json`. Keep it operator-owned and
readable by the app. Atomically replace the file in that directory; binding only
one file would keep the old inode visible. Price checks occur at most every five
seconds on new questions or allowance reads. Valid files replace the whole
catalog; invalid, missing or oversized updates log an error and retain the last
valid prices. Existing questions and recorded spending never change. Restart
once to set the path; subsequent price edits require no restart. Changing model
IDs also requires matching catalog entries.

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
Six default calls reserve at most $0.076800 for Claude or $0.184320 for DeepSeek.
The input bound uses the highest of ordinary, cache-read and cache-write rates. Token reports outside the
configured bounds are treated as uncertain, never as free usage.

A question may execute 16 tools, retain 16 source pages and return 32 KiB of answer
text plus citations. Public activity is bounded to 40 entries and 256 KiB,
including space reserved for failure status. At most 64 questions may be running or awaiting clarification
across the instance; only running questions consume model concurrency. These
limits cover the complete result rather than silently shortening a source.

## Verification

`QaIntegrationIT` covers the real PostgreSQL and account boundary;
`AnthropicQaModelTests` and `DeepSeekQaModelTests` exercise bounded HTTP, malformed responses and no retries;
`QaConversationTests`, `PlazaQaSourcesTests` and the question component tests cover
tool turns, evidence, fixed retrieval cases and explicit user choices.
[Site QA acceptance](../acceptance/qa/README.md) owns the fixed corpus, paid
entrance and result report. It does not start the independent FiQA experiment.
