# Retrieval comparison laboratory

Date: 2026-09-24
Implemented: 2026-09-24

## Problem

[Repository-native retrieval](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) chooses repository tools for flexibility and ownership. It does not establish that agentic search retrieves better than a conventional embedding and reranking pipeline. A separate experiment must compare both on the same complete corpus before either becomes a product QA architecture.

## Decision

The independent [TypeScript service](../../experiments/retrieval-lab/README.md) provides an interactive comparison page and reproducible batch evaluation; its README owns the dataset pins, route parameters, bounds, metrics and commands. Its SQLite state, corpus, credentials and Linux worker are separate from the application. Production content authority, caches, search and publication retain their existing contracts. Private evaluation questions, production integration, cross-space synchronization and concurrency scaling remain later work.

The corpus is the FiQA database from [scrydb-eval](https://github.com/breuert/scrydb-eval) with its original documents and precomputed Qwen3-Embedding-8B vectors, pinned and hash-verified. The author's float32 dense baseline is reproduced across all 648 test queries before systems are compared. Precomputed embeddings avoid a new embedding bill; their original preparation cost is external, not zero.

The traditional route fuses BM25 and cosine candidates by reciprocal rank and reranks them with a neural reranker. The agentic route receives the same complete original documents, organized into content-based folders with navigation indexes built from the corpus alone; evaluation queries, relevance labels and baseline runs are never consulted while organizing content. Each question runs in a fresh credential-free Git bundle copy through an independent [worker/SRT instance](../../executor-service/README.md) whose sandbox contains no query table, labels, vector database, baseline results or host configuration. Both routes share the answer model, prompt, evidence contract and context bounds; evidence is loaded by ID outside the sandbox, and invented citations are rejected. Oversized requests are rejected instead of silently truncated. Benchmark questions never undergo clarification or rewriting.

Runs and events are persisted before external operations. After a restart, incomplete paid operations are marked interrupted rather than replayed.

## Evaluation and delivery

Tuning uses a fixed development sample before configuration is frozen for a fixed-seed test subset, which is labelled a subset rather than a full benchmark score. Metrics use the final ranked evidence, not every file read. FiQA relevance labels are not complete reference answers, so an LLM judge must not be called objective answer accuracy. Finding no winning route is a valid result.

The dedicated experiment host uses loopback HTTP through an SSH tunnel. Host-specific configuration and cleanup inventories remain in private operator storage. Cleanup may remove authorized obsolete applications and data but preserves the OS, SSH and networking. Provision the complete corpus, adequate inode quotas and a bounded worker pool; fail explicitly rather than downsampling if capacity is insufficient. Price a formal batch from a measured development sample and let the operator launch it explicitly. A displayed account balance is not a budget.

Every verified main commit still publishes application images under the [continuous-delivery contract](2026-09-03-continuous-delivery.md). Automatic production deployment is skipped when a push changes this experiment and only its decision record, the continuous-delivery record or the shared CI workflow alongside it. The lab is deployed separately. This file-level classification also defers delivery-only workflow edits bundled with experiment changes; operators can deploy the published revision explicitly. Changes to application code, dependencies or deployment scripts retain automatic delivery.

## Alternatives and consequences

Adding an index to the production application would couple an unresolved experiment to synchronization and permission contracts. This service keeps that decision reversible. PostgreSQL with pgvector would be representative of another deployment choice, but SQLite with FTS5 and sqlite-vec can reuse the available corpus without vector conversion or a resident database service. Index choice and retrieval quality are separate variables.

Flat numeric folders would remove useful navigation that the product already supplies. Each route instead gets its own corpus-only retrieval preparation, with its costs reported. Topic folders are a derived index, not new authority or ground truth. Public benchmark contamination and its financial domain limit how far results generalize; private questions remain a follow-up experiment.

## Verification and remaining runs

The [calibration report](../../experiments/retrieval-lab/REPORT.md) records all 648 identical top-ten rankings and the disclosed empty-document discrepancy, and later real-provider compatibility and smoke outcomes, including unsuccessful attempts. The experiment's `npm run check`, which the CI `retrieval-lab` lane runs, covers its TypeScript and Python checks; native acceptance on the complete corpus covers host and network denial, label separation and cleanup. Development pairs, the operator-launched formal batch and human support review remain required before reporting comparative quality; offline calibration and individual smoke runs do not establish that comparison.

## Related decisions

[Stock PostgreSQL](2026-09-05-stock-postgresql.md), [remote repository authority](2026-09-01-remote-repository-authority.md), [public site search](2026-09-14-public-site-search.md), and [repository directory navigation](2026-09-08-repository-directory-navigation.md) retain their scopes, and the rejected [optional serverless profile](../rejected/2026-09-01-optional-serverless-deployment-profile.md) keeps its reasons. This experiment neither reinstates production projections nor implements the future product QA module.
