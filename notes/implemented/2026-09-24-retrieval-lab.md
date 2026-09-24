# Retrieval comparison laboratory

Date: 2026-09-24
Implemented: 2026-09-24

## Problem

[Repository-native retrieval](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) chooses repository tools for flexibility and ownership. It does not establish that agentic search retrieves better than a conventional embedding and reranking pipeline. A separate experiment must compare both on the same complete corpus before either becomes a product QA architecture.

## Decision

The independent [TypeScript service](../../experiments/retrieval-lab/README.md) provides an interactive comparison page and reproducible batch evaluation. Its SQLite state, corpus, credentials and Linux worker are separate from the application. Production content authority, caches, search and publication retain their existing contracts. Private evaluation questions, production integration, cross-space synchronization and concurrency scaling remain later work.

Use the FiQA database from [scrydb-eval](https://github.com/breuert/scrydb-eval), pinning the source and dataset revisions and verifying download hashes. Reuse its original documents and 4,096-dimensional float32 Qwen3-Embedding-8B document and query vectors. Validate integrity, identifier mappings, vector dimensions, test-label coverage and differences from standard BEIR before evaluation. Reproduce the author's float32 dense baseline across all 648 test queries before comparing systems. Precomputed embeddings avoid a new embedding bill; their original preparation cost is external, not zero.

The traditional route retrieves 100 FTS5 BM25 and 100 cosine candidates, combines them using reciprocal-rank fusion with constant 60, sends the first 50 to SiliconFlow `Qwen/Qwen3-Reranker-8B`, and selects at most ten documents. Neural reranking is distinct from the upstream database's higher-precision vector rescoring. Preserve document boundaries and original vectors. Stored queries use stored embeddings. Free queries use pure-text `Qwen/Qwen3-Embedding-8B` with the upstream financial-query instruction only after sample compatibility validation; a mismatch disables free queries without re-embedding the corpus. Reject oversized requests explicitly instead of silently truncating.

The agentic route receives the same complete original documents, organized into content-based folders with hierarchical navigation indexes. Corpus-only deterministic lexical clustering and extractive document previews provide navigation without API fees. Record preparation time, memory, storage and the exact configuration. Do not consult evaluation queries, relevance labels or baseline runs while organizing content. Retain stable original IDs and text. Each question gets a fresh credential-free Git bundle copy through an independent [worker/SRT instance](../../executor-service/README.md). The sandbox contains no query table, labels, vector database, baseline results or host configuration. It can search, read and run scripts, then returns at most ten ranked document IDs. Default bounds are twelve tool calls, thirty seconds per command and five minutes for investigation; reaching a bound remains visible.

Both routes use `deepseek-flash` and the same final answer prompt, evidence contract and context bounds. Record returned model identity, configuration, prompt revision, token usage, latency and prices. SiliconFlow reranking reports usage under `meta.tokens`; missing input usage remains unknown. DeepSeek can return multiple tool calls in one response. Execute them sequentially in the worker's persistent shell, count each executed call against the twelve-call bound, and stop remaining calls on cancellation or evidence submission. Requiring exactly one call would reject valid search responses. Reserve the final slot for a named `submit_evidence` tool choice: a prompt-only reservation lets continued searching consume the submission slot. Excess search requests receive tool errors, forced submission remains a visible limit stop, and only submitted IDs are scored.

Load the selected original evidence by ID outside the mutable sandbox, reject invented citations and display commands and evidence without hidden reasoning. A shared clarification step can call `ask_user` before an interactive pair; persist choices and free text, release resources while waiting, and resume both routes with the same clarified question. Benchmark questions never undergo clarification or rewriting.

Persist runs and events before external operations. Support creation, SSE events, clarification replies, cancellation, history and result export. After a restart, mark incomplete paid operations interrupted rather than replaying them. Explicit retries preserve history; batch continuation skips completed question/route pairs. Use one active route at a time on a small host.

## Evaluation and delivery

- Run full-corpus offline retrieval calibration over all 648 test questions. Distinguish cached-vector retrieval timing from online query embedding latency.
- Use twenty fixed-seed development questions for tuning, then freeze configuration before a fixed-seed 100-question test comparison. Label the latter a subset, not a full benchmark score.
- Measure nDCG@10 and Recall@10 on final ranked evidence, not every file read. Report failures, limit stops, tool counts, model usage, retrieval and answer latency, and per-provider/currency costs.
- Review the same twenty paired answers for citation support. FiQA relevance labels are not complete reference answers; an LLM judge must not be called objective answer accuracy.
- Validate label isolation, ID mapping, query formatting, neural-reranker positions, limits, cancellation, clarification, rate-limit failures and restart recovery. Exercise the real Linux sandbox and the actual page in Chrome.
- Publish reproducible commands, configuration, JSON/CSV results and an experiment report. Finding no winning route is a valid result.

The dedicated experiment host uses loopback HTTP through an SSH tunnel. Host-specific configuration and cleanup inventories remain in private operator storage. Cleanup may remove authorized obsolete applications and data but preserves the OS, SSH and networking. Provision the complete corpus, adequate inode quotas and a bounded worker pool; fail explicitly rather than downsampling if capacity is insufficient. Price a formal batch from a measured development sample and let the operator launch it explicitly. A displayed account balance is not a budget.

Every verified main commit still publishes application images under the [continuous-delivery contract](2026-09-03-continuous-delivery.md). Automatic production deployment is skipped when a push changes this experiment and only its decision record, the continuous-delivery record or the shared CI workflow alongside it. The lab is deployed separately. This file-level classification also defers delivery-only workflow edits bundled with experiment changes; operators can deploy the published revision explicitly. Changes to application code, dependencies or deployment scripts retain automatic delivery.

## Alternatives and consequences

Adding an index to the production application would couple an unresolved experiment to synchronization and permission contracts. This service keeps that decision reversible. PostgreSQL with pgvector would be representative of another deployment choice, but SQLite with FTS5 and sqlite-vec can reuse the available corpus without vector conversion or a resident database service. Index choice and retrieval quality are separate variables.

Flat numeric folders would remove useful navigation that the product already supplies. Each route instead gets its own corpus-only retrieval preparation, with its costs reported. Topic folders are a derived index, not new authority or ground truth. Public benchmark contamination and its financial domain limit how far results generalize; private questions remain a follow-up experiment.

## Verification and remaining runs

The [calibration report](../../experiments/retrieval-lab/REPORT.md) records all 648 identical top-ten rankings and the disclosed empty-document discrepancy. The TypeScript and Python checks cover mapping, metrics, clarification, restart accounting, cancellation, batch cancellation, citation validation, HTTP origin enforcement and reranker positions. Native acceptance on the complete corpus verifies host/network denial, label separation, navigation, output artifacts, fresh copies, timeout recovery and cleanup after application SIGKILL. The existing Java `ephemeral-lifecycle` native probe also passes with the installed worker sources.

Chrome fixture acceptance verifies selection, missing-configuration feedback, persisted clarification after reload, resumed paired results and original-evidence rendering. Its model and command replies are synthetic; the separate native probe supplies real SRT evidence. The calibration report records subsequent real-provider compatibility and smoke outcomes, including unsuccessful attempts. Development pairs, the operator-launched formal batch and human support review remain required before reporting comparative quality. Offline calibration and individual smoke runs do not establish that comparison.

## Related decisions

[Stock PostgreSQL](2026-09-05-stock-postgresql.md), [remote repository authority](2026-09-01-remote-repository-authority.md), [public site search](2026-09-14-public-site-search.md), [repository directory navigation](2026-09-08-repository-directory-navigation.md), and the [optional serverless profile](../proposed/2026-09-01-optional-serverless-deployment-profile.md) retain their scopes. This experiment neither reinstates production projections nor implements the future product QA module.
