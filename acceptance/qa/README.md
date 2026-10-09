# Site QA acceptance

The fixed [corpus](corpus.json) contains five invented public articles and five
questions: tag discovery, rephrasing, cross-article synthesis, missing evidence and
an adversarial paper. It is a product regression fixture, not a commercial corpus
or a general RAG benchmark. Existing acceptance articles remain present as unrelated
content. No FiQA data or batch is loaded.

`PlazaQaSourcesTests` runs the corpus with fixed query plans, without a model or
network calls. It verifies required hits, readable evidence and empty results;
the supplied reformulations do not measure whether an agent can discover them.
`QaConversationTests` separately verifies citations and withdrawal. Run these
through the ordinary Gradle test entrance.

For explicit paid acceptance, follow the [isolated application entrance](../README.md)
and add a model key as `POKETTO_QA_DEEPSEEK_API_KEY` in ignored `acceptance/.env`. Use a fresh
fixture project and include the QA override:

```sh
./gradlew stageAcceptanceRuntime
docker compose -p poketto-qa --env-file acceptance/.env -f acceptance/compose.yaml -f acceptance/qa/compose.yaml up --build -d
python acceptance/qa/run.py --base http://127.0.0.1:38180 --env-file acceptance/.env --report .gradle/qa-site.json
python acceptance/qa/wish.py --base http://127.0.0.1:38180 --env-file acceptance/.env --report .gradle/qa-wish.json
```

The override defaults to DeepSeek and limits the shared daily budget to $0.50.
For Claude, configure `POKETTO_QA_ANTHROPIC_API_KEY`, set
`POKETTO_QA_DEFAULT_PROVIDER=anthropic` for wishes, and pass `--provider anthropic`
to `run.py`. Both choices use the same admission ledger and bounded thinking loop. Both scripts call
the product's real authentication, PostgreSQL and QA budget entrance. `run.py`
asks five web questions, rereads every cited public article independently, and
checks completed duplicates without repeating paid work. The response records
include the selected model, public thinking and tool activity. `wish.py` checks holder
consent, one-candy spending, clarification across fresh MCP sessions, passive
status reads and revocation. Its synthetic machine key is revoked on completion.
Neither script is browser acceptance.

Reports record a request ID before dispatch. An existing report is refused, so a
lost response cannot silently restart paid work. Inspect that request's status
before any further experiment. `--cases` selects comma-separated fixture IDs for
`run.py`; it does not bypass daily allowances. Once finished, dispose only of this
fixture project's volumes using the isolated entrance's cleanup procedure.

The [DeepSeek report](2026-10-09-results.json) records the earlier run and source
hashes before thinking and provider selection; it does not verify the new adapters.
The [Anthropic report](2026-10-09-anthropic-results.json) records native Haiku 5.5
thinking-enabled HTTP/MCP acceptance, including two initial failures and explicit
reruns after tool-contract fixes. Its revision fields distinguish those runs.
Required source hits and exact-quote checks passed after correction;
they do not establish general recall, semantic entailment accuracy or an advantage
over embedding retrieval. Plain-text rendering and actual browser controls require
their separate Chrome acceptance.

The [Spring AI report](2026-10-10-spring-ai-results.json) records classified cache
usage through the framework-backed DeepSeek HTTP and Claude MCP paths. Provider
refusal remains a deterministic protocol/database test, not a forced paid probe.
