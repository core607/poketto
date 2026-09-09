# Repository-Aware PR Review

Date: 2026-09-09
Status: Implemented

## Problem

Complete diff coverage does not show every caller, configuration, or existing implementation. A reviewer that cannot retrieve surrounding code can mistake missing context for a missing implementation. The [complete review transport](2026-09-06-complete-pr-review.md) remains responsible for immutable commit identity, complete diff coverage, retained results, and explicit failure. This record replaces its single-response review stages and request budgets.

## Decision

Each diff part and the final cross-contract review use a tool-calling loop. The entire PR shares `min(30, 8 + 2 * changed_files)` model calls, including final responses. The runner reserves one response for each remaining part and the cross-contract summary. Before a stage's final permitted call, it appends an instruction to stop using tools and summarize observed findings, marking unverified claims. It keeps the tool definitions and sets `tool_choice: none`. A provider that nevertheless asks for tools fails explicitly. A diff requiring more mandatory responses than the PR budget remains incomplete.

The only model tool is `repository`, with `list`, `read`, and literal `search` actions. Its revision selector resolves to the captured base, head, or merge base. It reads a temporary bare Git repository that remains alive throughout the review. Exact tree entries supply blob identities; model strings never become shell commands, Git options, arbitrary revisions, local filesystem paths, or remote addresses. Regular executable files may be read as text but never executed. Symlinks and submodules are not followed. Trusted code and rules still come from `main`.

`read` accepts a zero-based line `offset` and a `limit` from 1 to 200. It returns numbered lines and `next_offset`. The result byte bound may shorten a page; an individually oversized line returns a marked UTF-8 prefix. `search` returns only matching paths and line numbers, with a resumable cursor and separate unsupported-file diagnostics. It does not return snippets. `list` also paginates. Missing pages, truncated lines, and unreadable files cannot establish absence.

## Budgets and caching

- Each model request has a 300,000 input-token ceiling. Before the first request, serialized UTF-8 bytes plus 4,096 framing units conservatively estimate tokens. Later requests start from the provider's actual prompt-token count and add an upper bound for appended messages. This is conservative admission accounting, not an exact provider tokenizer; recorded actual usage must also satisfy the ceiling. The initial diff partition cap is therefore 295,904 serialized bytes. Source text is never silently dropped to fit.
- The entire PR shares 150,000 generated tokens, including reasoning and visible output, across all parts and the final summary. Each request sets `max_tokens` to the remaining budget. Missing or invalid usage fails the run. Exhaustion cannot produce a complete-coverage claim.
- Transport limits are 4 MB per request and 2 MB per response. The complete diff remains bounded by 8 MB and 32 parts. Visible final text remains limited to 50,000 characters for GitHub posting.
- Each model response can request at most eight tools. A tool result is at most 24 KB. A regular UTF-8 blob is at most 1 MB; a Git tree is at most 16 MB and 100,000 entries. A read returns at most 200 lines, a list at most 200 entries, and a search scans at most 100 files and returns at most 200 matches per page. These bounds preserve explicit continuation or diagnostic output.
- The runner has a 60-minute wall deadline; the workflow allows 65 minutes for checkout and artifact retention. A provider request has a 15-minute socket timeout inside that wall deadline. Failures are not retried automatically.

The system rules, tool schemas, and PR identity form a stable prefix. Existing messages are append-only, and tool definitions remain present during finalization. DeepSeek requires previous `reasoning_content` in subsequent thinking-mode tool requests; the runner preserves it in memory without writing it to artifacts or logs. This follows the [provider tool-call contract](https://api-docs.deepseek.com/guides/thinking_mode/). Input, output, and supplied cache-hit/cache-miss token counts are retained with operation metadata. Cache hits are measured provider results, not a guarantee or a client-side cache-control flag.

## Evidence and consequences

Operation artifacts identify requested actions, arguments, result byte counts, and hashes, alongside per-turn usage. They contain neither raw provider envelopes nor reasoning text. Existing diff artifacts, final reviews, commit-bound comments, identity rechecks, and mention replacement remain unchanged. The model receives more repository source through read tools, but no GitHub or provider credential. It cannot run tests, alter code, approve, or merge.

Real Git fixtures verify base/head separation, bare-object reads, symlink refusal, binary and size diagnostics, read/search/list continuation, long-line truncation, and resistance to head scripts and external diff execution. Scripted provider responses exercise the real HTTP adapter through tool invocation and a final response, including reasoning replay, shared budgets, finalization, cache accounting, and stale-PR rejection. The suite runs with `python3 -m unittest discover -s .github/review -p 'test_*.py' -v`; Linux additionally exercises the wall-clock signal deadline. `repoCheck` and `git diff --check` cover repository form. Live tool use and cache hits require a production run after the trusted implementation reaches `main`.

## Alternatives

Sending the whole repository on every request wastes context on unrelated code and defeats incremental reading. A general shell could inspect code but would also expose execution and credential-bearing runner state. A fixed read-only Git-object interface supplies the missing evidence while preserving the existing trust boundary. The persona and configured model remain unchanged; grounded findings still require human or author-agent judgment.
