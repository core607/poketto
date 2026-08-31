# Repository-Native Retrieval and Sandboxed Agent Execution

Date: 2026-09-01
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) put document search in a PostgreSQL `search_documents` projection and give AI agents a fixed set of retrieval tools. That design keeps queries predictable, but it makes PostgreSQL duplicate repository content, adds replay and checkpoint state, and limits an agent to query shapes implemented in advance.

Poketto instead needs trusted agents to inspect the repository as a repository. An agent should be able to combine Git history, exact text search, frontmatter inspection, and short programs without receiving access to the live content worktree or the host. Public pages and visitor Q&A still need bounded read interfaces that cannot turn a public request into arbitrary command execution.

Running model-produced code directly inside the Spring process is not a security boundary. The JDK [permanently disables the Security Manager from JDK 24](https://docs.oracle.com/en/java/javase/25/security/security-manager-is-permanently-disabled.html), and an embedded language that safely exposes only selected APIs would reproduce a fixed tool surface rather than provide ordinary repository tools.

## Proposal

### Content authority and retrieval

- PostgreSQL stores no document body, document metadata, search vector, content-search index, projection checkpoint, or index-lag state. It remains the store for relational application data such as workspaces, accounts, memberships, invitations, API-key metadata, audits, and budgets.
- All content reads resolve a workspace and a committed `main` revision before reading. A request observes that commit even if a later write advances `main` while the request is running.
- Spring exposes bounded repository-backed reads for public rendering, administration, and visitor Q&A. Public reads construct their visibility scope internally and accept no caller-selected private scope or regular expression; every caller-controlled pattern uses a matcher with a proven linear-time bound. An invalid managed document is excluded from structured public results and reported through workspace diagnostics; one malformed file must not expose private content or make raw repository access public.
- The first search implementation scans the selected committed tree and may keep a bounded in-memory cache keyed by workspace and commit. No durable search index is part of v1. A persistent non-PostgreSQL index requires a separate proposal with measured need, rebuild semantics, freshness behavior, and resource evidence.
- PostgreSQL full-text search, zhparser, `search_documents`, projection replay, and the `projection` and `search` application modules leave the target architecture. The repository remains usable when PostgreSQL content-search state does not exist.

### Trusted-agent execution

The trusted-agent MCP read surface is one generic operation, `repo_exec`. It replaces the fixed `search`, `get_doc`, `list_tags`, `list_recent`, and `history` tools. Structured document mutations and external clipping remain behind dedicated operations; command execution never writes back to the authoritative repository.

`repo_exec` receives an executable, argument vector, optional standard input, and bounded output preferences. The server supplies the workspace, working directory, environment, and resolved commit. The result contains the resolved commit, exit status, standard output, standard error, timeout or cancellation state, and explicit truncation indicators. The host does not interpolate the request through its own shell; an agent that needs shell syntax or a program body invokes the sandbox's declared shell or interpreter explicitly.

Repository execution requires a separately grantable `EXECUTE_REPOSITORY` capability in addition to `READ_PRIVATE`. It is available only to authenticated workspace members and their trusted agents. Public routes, visitor Q&A, browser-supplied scripts, and keys without that capability cannot reach the executor.

Each invocation receives a disposable local clone at the resolved commit, including reachable history needed by Git inspection. The clone may be writable inside the sandbox so tools can create locks, bytecode, and temporary results, but it has no remote credentials and no path back to the workspace repository. Every change is discarded after the invocation. A successful command therefore means only that execution completed; it never acknowledges a content write.

### Sandbox boundary

The first executor uses a pinned release of [Anthropic Sandbox Runtime](https://github.com/anthropic-experimental/sandbox-runtime) behind a Poketto-owned `SandboxExecutor` port. Business modules do not import its JavaScript library or depend on its configuration format. The adapter may invoke its CLI or a local executor process, but it must preserve these invariants:

- The process and every descendant run under a dedicated low-privilege identity with a minimal environment.
- Filesystem reads are denied except for the disposable clone, the declared toolchain and runtime libraries, and per-invocation temporary storage. The live workspace repository, other workspaces, blob storage, application configuration, credentials, user profiles, database files and sockets, container runtime sockets, and service control interfaces are inaccessible.
- Network access is denied. v1 has no domain allowlist, package installation, remote Git access, or proxy escape hatch inside repository execution.
- Wall time, CPU, memory, descendant-process count, temporary storage, and standard output and error are bounded outside the command. Cancellation terminates the complete process tree.
- Sandbox setup or policy failure rejects the invocation. There is no direct-process fallback.

The implementation must verify the boundary in the real production topology. It must not grant the Spring container a Docker socket, broad host mounts, or elevated capabilities merely to make sandboxing work. If Sandbox Runtime cannot preserve its boundary in that topology, implementation stops for a new decision between a dedicated same-host executor and an OCI or gVisor worker. Unsupported development platforms return an actionable unavailable result rather than executing without isolation.

### Lifecycle and resource boundary

Repository execution is on demand and has bounded instance-wide and per-workspace concurrency. The executor cleans up clones and temporary storage after success, failure, cancellation, and process restart. Cleanup never follows a caller-controlled path.

The implementation measures clone creation, exact search, Git history inspection, and a representative Python analysis on the project's low-spec target. It records steady-state and peak usage for every deployed process, including the frontend when present. A cache or retained execution worker is added only when the evidence shows that startup or clone cost is material and the cache remains commit-keyed, bounded, and unable to retain private output across workspaces.

Executable identity, a digest of the arguments and standard input, workspace, principal, resolved commit, duration, resource-limit outcome, and sanitized failure category are auditable. Raw arguments, standard input, standard output, standard error, repository contents, and program bodies are not copied into PostgreSQL audit rows by default because they may contain private content.

## Implementation scope and dependencies

The first implementation depends on the implemented [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md) and [content repository foundation](../implemented/2026-08-26-content-foundation.md). It includes committed-tree reads that tolerate per-document validation failures, bounded in-memory search, the execution capability and MCP contract, disposable clones, the sandbox adapter, resource enforcement, cleanup recovery, audits, and focused security and isolation tests.

The same change removes PostgreSQL content projection and zhparser from application code, schema, tests, CI, and deployment. It removes the `projection` and `search` modules, replaces their callers with repository-backed read contracts, and updates the English and Chinese requirements and README counterparts, the development baseline, agent review rules, command documentation, backup rules, and deployment artifacts to describe the implemented architecture.

This proposal excludes persistent full-text or vector indexes, embeddings, networked code execution, dependency installation, mutation through `repo_exec`, public arbitrary execution, remote sandbox workers, gVisor, microVMs, and compatibility shims for projection tables. The pre-release schema may remove derived projection state destructively.

## Alternatives considered

**Keep PostgreSQL full-text search as the default AI path.** It is efficient for repeated ranked queries and Chinese tokenization, but it duplicates content and metadata, requires replay and freshness contracts, and leaves repository structure and history outside the agent's normal read surface. PostgreSQL remains available for relational application state, not content search.

**Expose only fixed MCP retrieval tools.** A narrow tool set is easy to authorize and cheap to execute, but every new investigation pattern becomes server code. Bounded read APIs remain appropriate for public and browser paths; trusted agents receive the generic executor because their value comes from composing repository tools and code.

**Embed a restricted JVM language.** Starlark, Painless-style allowlists, or a Groovy sandbox can expose safe domain APIs with low process overhead. They cannot provide ordinary Git, ripgrep, shell, and Python behavior without recreating an operating-system capability surface inside the JVM.

**Run every command in an OCI container or gVisor sandbox.** These provide a stronger process boundary and remain the fallback for a multi-user or hostile-code service. Their image lifecycle, startup work, and resident runtime are not justified for the first single-host personal deployment unless the real-topology sandbox test rejects Sandbox Runtime.

**Execute against the live repository with read-only permissions.** This avoids clone cost, but tools may still create locks or caches, a policy error exposes authoritative state, and one long read races with advancing history. A disposable clone gives the command a coherent commit and makes all local mutations irrelevant.

## Acceptance

- PostgreSQL contains no content-search, document, projection, checkpoint, or index-lag state. Restoring the authoritative relational rows and workspace repositories restores all content reads without a projection or indexing job.
- Public rendering, administration reads, and visitor Q&A read a resolved committed tree through workspace-scoped contracts. Public callers cannot provide a visibility scope or reach `repo_exec`.
- `repo_exec` requires both workspace authorization and `EXECUTE_REPOSITORY`. Attempts to read another workspace, the live repository, configuration, credentials, database or container sockets, or user-profile files fail inside the real sandbox.
- A command can use the declared Git, exact-search, shell, and Python tools against a disposable clone with history. Changes inside the clone never change workspace `main`, and no executor path can invoke a content write operation implicitly.
- Direct network access, proxy-based access, package installation, and remote Git access fail. Sandbox initialization failure never falls back to an unsandboxed process.
- Time, CPU, memory, descendant-process, temporary-storage, and output limits terminate the complete process tree and return distinct actionable results. Cleanup removes abandoned execution state after ordinary completion and simulated crashes.
- Cross-workspace tests run concurrent commands and prove that files, outputs, temporary state, caches, resource accounting, and audits remain isolated.
- A production-like low-spec exercise covers representative repository reads and records steady-state and peak usage. Failure to preserve the sandbox boundary or deployment resource limit stops implementation for a new runtime or sizing decision.
- Requirements, current-state documents, skills, tests, and deployment artifacts contain no live claim that PostgreSQL or zhparser performs content search after implementation. Repository and application checks pass.

## Risks

Repository scanning has linear cost and exact search has weaker ranking and Chinese tokenization than the planned PostgreSQL path. Commit-keyed caches and agent-composed queries may be sufficient for a personal corpus; a persistent index needs evidence rather than assumption.

Sandbox Runtime is an early project and its platform behavior can change. Pinning it, testing the packaged runtime on the production host, failing closed, and keeping a Poketto-owned port limit dependency drift, but they do not make a same-kernel sandbox equivalent to a VM.

Model-produced code can be malicious because of prompt injection, dependency content, or ordinary mistakes. Filesystem and network isolation reduce impact, while resource and output limits bound denial of service; neither replaces patching the sandbox and host kernel.

Disposable clones add filesystem work and may become expensive as history grows. Reuse must not trade away commit consistency, workspace isolation, cleanup, or the guarantee that no execution mutation reaches authoritative history.
