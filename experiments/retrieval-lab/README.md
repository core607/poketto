# Retrieval lab

An independent FiQA experiment comparing hybrid retrieval with neural reranking and a file-search agent. It does not change Poketto's production search or content authority. The [decision](../../notes/implemented/2026-09-24-retrieval-lab.md) owns scope and alternatives; [the calibration report](REPORT.md) records measured results and unverified work.

## Prepare the data

Use Node.js 24.19.0, npm 12.0.2, Python 3.10 or later, Git and curl. From this directory:

```sh
npm ci --ignore-scripts --no-audit --no-fund
python3 -m venv .venv
.venv/bin/pip install -r python/requirements.txt
.venv/bin/python python/prepare.py fetch --data data
.venv/bin/python python/prepare.py audit --data data
.venv/bin/python python/prepare.py organize --data data
.venv/bin/python python/prepare.py calibrate --data data
npm run build
```

On Windows use `.venv/Scripts/python` and `.venv/Scripts/pip`. Activate that environment before `npm run check`, which uses `python` from PATH. Data stays outside Git; an absolute data directory works on every command. A new preparation needs a new directory: organizing never overwrites an existing corpus snapshot. Downloads resume at the asset level, validate the pinned database SHA-256 and record every asset hash. Keep `dataset.json`, `downloads.json`, `audit.json`, `corpus-manifest.json`, `splits.json` and `calibration.json` with results.

The source SQLite file is opened with `mode=ro` and `query_only`. The pinned scrydb adapter uses its existing FTS5 and sqlite-vec indexes without index creation or vector conversion. Stored questions use original vectors; the full calibration compares all 648 test queries with the pinned author run. The upstream 57,600-document variant excludes 38 empty BEIR documents. One empty ID is judged relevant to a test question. Audit only permits verified empty omissions and retains all original labels and metric denominators. Nonempty omissions, changed text, incomplete vector mappings or unknown dimensions stop preparation.

Corpus-only TF-IDF and MiniBatchKMeans produce 32 topic folders. Each folder lists pages of at most 100 documents, with topic keywords and extractive previews. Original document text is byte-preserved; filenames retain original IDs. Topic membership is heuristic and can be uneven. Agents can search all folders rather than relying on a category assignment. The Git bundle contains originals and navigation only, without query vectors, evaluation questions, relevance labels, baseline rankings or service configuration. The manifest maps paths to IDs outside the sandbox and records preparation time, sizes, parameters and bundle hash. Labels are available only to the host evaluator after evidence selection.

## Install the independent worker

Agentic runs need a dedicated instance of [executor-service](../../executor-service/README.md) on Linux. Install matching worker sources from Git with LF endings, the pinned SRT toolchain, root-owned code/configuration, a separate unprivileged execution account and an application account. Follow the existing worker's cgroup, socket, signing-key and XFS project-quota requirements. No host-shell or Docker execution fallback exists.

Use a separate bounded pool; its export directory belongs to the application account. For this corpus allow at least 60,000 files plus Git metadata per copy; the tested inode budget is 150,000, bundle bound 256 MiB, copy bound 2 GiB and pool bound 8 GiB. The worker unit prefix must match `poketto-exec-[a-z0-9]+-`. Configure initialization for 120 seconds, commands for 30 seconds, one active session, command memory 512 MiB and an aggregate bounded slice. Validate these sizes on the actual host; the service never reduces the corpus to fit. The measured corpus bundle and file counts are in the preparation manifest.

The application signs leases using its private Ed25519 key; only the worker gets the public key. The adapter verifies the root-owned local socket and protocol, renews the lease, contains cancellation, closes and discards copies, and keeps unfinished cleanup receipts in its private state. Cleanup must finish before another agentic run starts. A fresh copy per question prevents mutations from contaminating later questions. Selected evidence always comes from original SQLite text, never a modified sandbox file.

## Run the service

Create a private environment file from [`.env.example`](.env.example); give only the service account access. Set the two API keys, paths, and current per-million-token prices. Keys are never returned to the browser. Empty prices and missing usage remain unknown instead of being displayed as zero. DeepSeek charges are recorded in USD and SiliconFlow in CNY; totals never combine currencies. The price-based amount is an estimate using reported usage and configured rates, not an invoice; cache discounts and provider billing rules can differ.

```sh
LAB_ENV_FILE=/path/to/private/lab.env node dist/src/server.js
```

The server listens only on `127.0.0.1:38470`. Use an SSH tunnel with the same local port for remote access; do not publish this operator interface to the internet. HTTP writes require the exact local Origin and JSON content type. Host checks reject DNS rebinding. The data directory and `runs.db` must be private to the application account. Run under a service manager with finite memory/disk limits. Host-specific provisioning, cleanup inventories, secrets and service units belong in private operator storage, never in this repository.

The page supports questions, route selection, paired sequential runs, evidence, tool output, cancellation, explicit retries, history, and JSON/CSV downloads. Interactive questions can use `ask_user` before retrieval. Waiting releases the active queue slot and allocates no sandbox. Both routes use the same clarified question. Benchmark questions keep their original text and bypass clarification. Reloading the page reconnects to durable events; reopening a waiting run allows a reply. Restarted in-progress and queued runs become interrupted, without automatically repeating possibly charged requests.

Query compatibility makes three development-query embedding calls and compares 4,096-dimensional vectors and top-ten overlap with the stored vectors. Free-query RAG stays unavailable until all samples reach cosine similarity 0.99 and overlap 0.8. A failure does not re-embed the corpus or block stored benchmark queries. The exact instruction format is owned by `queryInput` in [config.ts](src/config.ts), matching the pinned upstream script, not a FiQA requirement.

## Evaluation

The defaults in [config.ts](src/config.ts) are part of each run's fingerprint. Prices are recorded separately: changing rates preserves compatibility and completed development samples. Formal estimates reprice recorded usage at the current configured rates without rewriting historical charges:

- RAG: 100 lexical and 100 float32 cosine candidates, RRF constant 60, neural reranking of 50 candidates, ten selected documents. Upstream BM25 options are FTS column weights, not tunable BM25 b/k1. The experiment uses the upstream defaults.
- Agentic: shell/search/Python, captured-output reads, and ranked ID submission; twelve tools, thirty seconds per command and five minutes for investigation. Multiple calls in one model response execute sequentially in a persistent shell; working directory and environment changes survive between calls. Each executed call consumes the tool budget. The final slot is reserved for evidence submission through a named tool choice. Excess search calls receive tool errors without executing; forced submission is marked as a limit stop. Cancellation and evidence submission stop remaining calls. Only submitted IDs become retrieved evidence.
- Both: `deepseek-flash`, thinking disabled, temperature zero, 4,096 output tokens, one shared answer prompt and a 160,000-byte evidence bound. Reranker pairs have a conservative 30,000-byte input bound. Bounds reject instead of silently shortening documents. Returned model identity and reported usage are recorded. Hidden model reasoning is not persisted or exposed.

Use the twenty-question fixed development sample to check behavior and estimate the formal cost. The formal button requires twenty completed, priced development pairs under the same configuration; it explicitly starts a 100-question fixed test subset. Repeating batch launch skips completed pairs and explicitly retries stopped pairs under new run IDs. Completed routes in a retried pair are retained; preceding attempts and their charges remain available. Changing configuration creates another fingerprint, not a silent continuation. The fixed-seed twenty-question human-review subset is included in `splits.json`.

Metrics use the final ranked evidence list: nDCG@10 with linear gains and Recall@10. JSON preserves complete per-question outcomes, citations, usage, configuration and events accessible through the API. CSV has route outcomes, timings, known costs and empty human-review columns. Unknown charges, failures and limit stops must be included in the report. A cancelled run can have already incurred model charges. Formal results describe a test subset, not the full leaderboard. Cached query vectors exclude embedding latency; precomputed corpus vectors carry external preparation costs. FiQA supplies relevance judgments, not complete reference answers. Verbatim quote validation does not prove that a quote supports the generated claim; human review remains necessary.

HTTP endpoints: `GET /api/status`, `GET/POST /api/runs`, `GET /api/runs/:id`, `GET /api/runs/:id/events`, `POST /api/runs/:id/reply`, `POST /api/runs/:id/cancel`, `POST /api/runs/:id/retry`, `POST /api/compatibility`, `POST /api/batches` with `split`, and `GET /api/export?format=json|csv`. Batch creation is an explicit paid action, never part of startup or data preparation.

## Verification

```sh
npm run check
LAB_ENV_FILE=/path/to/private/lab.env node dist/src/native-acceptance.js
```

The first command covers Python mapping/metric checks and TypeScript lifecycle, HTTP origin, provider error and citation tests. The second requires the prepared corpus and real independent worker; it verifies full document coverage, navigation, no labels/database in the sandbox, host/network denial, output artifacts, fresh copies, timeout recovery and cleanup after application SIGKILL. It makes no model API calls.

For isolated browser acceptance, set `LAB_DATA` to the prepared corpus and run `node dist/test/browser-fixture.js`, then open `http://127.0.0.1:38471` in Chrome. This fixture uses separate temporary run state and synthetic model/worker replies. Exercise clarification, refresh/history recovery, a reply, paired evidence rendering and cancellation. Its marked results do not establish real-provider compatibility or retrieval quality.

`POST /api/cancel-all` stops active and queued experiments, including a batch being assembled. JSON exports include durable events and aggregate development/test reports. Aggregate retrieval means retain the whole fixed cohort as denominator, assigning zero when retrieval produced no metrics; pending cohorts are labelled provisional. Generation failures keep their already-measured retrieval scores. All retry costs remain in totals. Fingerprints include implementation and provider-configuration hashes so changed code or API endpoints cannot silently reuse a compatibility decision or development estimate.
