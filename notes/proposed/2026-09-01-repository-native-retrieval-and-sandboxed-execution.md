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
- All content reads resolve a workspace and a committed `main` revision through the [repository authority](2026-09-01-consumer-multitenancy-stateless-application-and-remote-repository-authority.md) before reading. A request observes that commit even if a later write advances `main` while the request is running.
- Spring exposes bounded repository-backed reads for public rendering, administration, and visitor Q&A. Public reads construct their visibility scope internally and accept no caller-selected private scope or regular expression; every caller-controlled pattern uses a matcher with a proven linear-time bound. An invalid managed document is excluded from structured public results and reported through workspace diagnostics; one malformed file must not expose private content or make raw repository access public.
- The first search implementation scans the selected committed tree and may keep a bounded in-memory cache keyed by workspace and commit. No durable search index is part of v1. A persistent non-PostgreSQL index requires a separate proposal with measured need, rebuild semantics, freshness behavior, and resource evidence.
- PostgreSQL full-text search, zhparser, `search_documents`, projection replay, and the `projection` and `search` application modules leave the target architecture. The repository remains usable when PostgreSQL content-search state does not exist.

### Trusted-agent execution

The trusted-agent MCP read surface has two operations. `repo_exec` replaces the fixed `search`, `list_tags`, `list_recent`, and `history` tools for exploration. A minimal structured `get_doc` remains as the optimistic-concurrency handshake for mutations. Structured document mutations and external clipping remain behind dedicated operations; command execution never writes back to the authoritative repository.

`get_doc` accepts a document UUID and an optional resolved commit returned by `repo_exec`. The server verifies that a supplied commit belongs to the authorized workspace repository. It returns the exact structured document, path, visibility, opaque revision, and resolved commit from one committed snapshot. Omitting the commit resolves current `main`. An agent preparing `update`, `delete`, or `publish` obtains `expected_revision` from this response; it never computes or parses a revision from repository bytes. Passing the `repo_exec` commit binds exploratory reads and the mutation handshake to the same snapshot, so a later change to that document produces a conflict rather than granting a stale edit a fresh token.

`repo_exec` receives an executable, argument vector, optional standard input, bounded output preferences, and an optional resolved commit previously issued for the authorized workspace. The first call without a commit resolves current `main`; later calls without one reuse the session's pinned commit. The result contains the resolved commit, exit status, standard output, standard error, timeout or cancellation state, and explicit truncation indicators. Poketto supplies the immutable workspace snapshot, working directory, and environment and does not interpolate the request through its own shell; an agent that needs shell syntax or a program body invokes the sandbox's declared shell or interpreter explicitly.

Repository execution requires a separately grantable `EXECUTE_REPOSITORY` capability in addition to `READ_PRIVATE`. It is available only to authenticated workspace members and their trusted agents. Public routes, visitor Q&A, browser-supplied scripts, and keys without that capability cannot reach the executor.

The first invocation in an authenticated agent session creates an execution workspace pinned to the resolved commit, including reachable history needed by Git inspection. Later `repo_exec` calls in that session reuse the workspace when they omit the commit or supply the same one. A different verified commit creates a new workspace and retires the old lease. The workspace may be writable inside the sandbox so tools can create locks, bytecode, and temporary results; those changes remain visible only within that session, which is never shared with another principal, workspace, or session. The workspace has no remote credentials or path back to the authoritative repository and is discarded when its bounded lease ends. A successful command therefore means only that execution completed; it never acknowledges a content write.

### Sandbox boundary

Poketto owns a `SandboxExecutor` port so business modules do not import one runtime library or depend on its configuration format. The self-hosted single-operator profile may use a pinned release of [Anthropic Sandbox Runtime](https://github.com/anthropic-experimental/sandbox-runtime) in a local executor service under a dedicated account. The hosted multi-tenant profile uses separately schedulable OCI workers with a gVisor-equivalent boundary or stronger isolation. A same-kernel filesystem policy alone is not the hosted tenant boundary.

Repository authority exports a verified immutable snapshot or Git bundle for the authorized workspace and commit. The executor materializes its session workspace before launching the sandbox and receives no authority credential or live repository path. A local development adapter uses `git clone --no-local` or an equivalently verified full object copy and rejects alternates, symlinked object storage, or object files sharing an inode with its source. Agent commands never run as the application or repository-authority identity, and authoritative storage is never mounted into their sandbox.

The adapter must also preserve these invariants:

- The process and every descendant run under the worker's dedicated low-privilege identity with a minimal environment.
- Filesystem reads are denied except for the session workspace, the declared toolchain and runtime libraries, and per-invocation temporary storage. Repository authority, other workspaces, blob storage, application configuration, credentials, user profiles, database files and sockets, container runtime sockets, and service control interfaces are inaccessible.
- Network access is denied. v1 has no domain allowlist, package installation, remote Git access, or proxy escape hatch inside repository execution.
- Wall time, CPU, memory, descendant-process count, temporary storage, and standard output and error are bounded outside the command. Cancellation terminates the complete process tree.
- Sandbox setup or policy failure rejects the invocation. There is no direct-process fallback.

The implementation verifies each enabled profile in its real production topology. It must not grant the Spring container a Docker socket, broad host mounts, or elevated capabilities merely to make sandboxing work. A self-host runtime that cannot preserve its declared boundary is disabled; hosted execution cannot fall back from its OCI isolation to Sandbox Runtime or a direct process. Unsupported development platforms return an actionable unavailable result rather than executing without isolation.

### Lifecycle and resource boundary

Repository execution is on demand and has bounded deployment-wide and per-workspace concurrency. A session workspace lease has an idle timeout, maximum lifetime, and storage bound. Cleanup removes session workspaces and temporary storage after lease expiry, cancellation, failure, explicit session close, and worker restart. Cleanup never follows a caller-controlled path.

The implementation measures a representative member-agent session in each supported deployment profile: one initial snapshot followed by twenty small `repo_exec` calls combining exact search, frontmatter inspection, Git history, and Python analysis. It records total session latency, per-command latency after startup, CPU, memory, network transfer, and storage peaks for every deployed process. Session-level, commit-pinned workspace reuse is part of the first implementation. A cross-session snapshot cache or retained execution worker is added only when evidence shows further startup cost is material and the cache remains commit-keyed, bounded, immutable to sandboxed commands, and unable to retain private output across sessions or workspaces.

Executable identity, a digest of the arguments and standard input, workspace, principal, resolved commit, duration, resource-limit outcome, and sanitized failure category are auditable. Raw arguments, standard input, standard output, standard error, repository contents, and program bodies are not copied into PostgreSQL audit rows by default because they may contain private content.

## Implementation scope and dependencies

The first implementation depends on the implemented [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md), [content repository foundation](../implemented/2026-08-26-content-foundation.md), and the `RepositoryAuthority` boundary from the [consumer multitenancy and stateless application proposal](2026-09-01-consumer-multitenancy-stateless-application-and-remote-repository-authority.md). It includes committed-tree reads that tolerate per-document validation failures, bounded in-memory search, `get_doc`, the execution capability and MCP contract, session-scoped commit-pinned workspaces, profile-appropriate sandbox adapters, resource enforcement, cleanup recovery, audits, and focused security and isolation tests.

The same change removes PostgreSQL content projection and zhparser from application code, schema, tests, CI, and deployment. It removes the `projection` and `search` modules, replaces their callers with repository-backed read contracts, and updates the English and Chinese requirements and README counterparts, the development baseline, agent review rules, command documentation, backup rules, and deployment artifacts to describe the implemented architecture.

This proposal excludes persistent full-text or vector indexes, embeddings, network access from sandboxed commands, dependency installation, mutation through `repo_exec`, public arbitrary execution, runtime fallback that weakens an enabled profile, and compatibility shims for projection tables. The pre-release schema may remove derived projection state destructively.

## Alternatives considered

**Keep PostgreSQL full-text search as the default AI path.** It is efficient for repeated ranked queries and Chinese tokenization, but it duplicates content and metadata, requires replay and freshness contracts, and leaves repository structure and history outside the agent's normal read surface. PostgreSQL remains available for relational application state, not content search.

**Expose only fixed MCP retrieval tools.** A narrow tool set is easy to authorize and cheap to execute, but every new investigation pattern becomes server code. Bounded read APIs remain appropriate for public and browser paths; trusted agents receive the generic executor because their value comes from composing repository tools and code.

**Embed a restricted JVM language.** Starlark, Painless-style allowlists, or a Groovy sandbox can expose safe domain APIs with low process overhead. They cannot provide ordinary Git, ripgrep, shell, and Python behavior without recreating an operating-system capability surface inside the JVM.

**Use the same Sandbox Runtime profile for hosted tenants.** It has lower startup and packaging cost, but a hosted service must treat tenant-controlled commands as hostile to the operator and other tenants. The hosted profile therefore pays for an OCI worker and stronger isolation while self-hosting may retain the lighter dedicated-account executor.

**Execute against the live repository with read-only permissions.** This avoids clone cost, but tools may still create locks or caches, a policy error exposes authoritative state, and one long read races with advancing history. A session workspace gives commands a coherent commit and makes all local mutations irrelevant to authoritative history.

## Acceptance

- PostgreSQL contains no content-search, document, projection, checkpoint, or index-lag state. Restoring the authoritative relational rows and repository authority restores all content reads without a projection or indexing job.
- Public rendering, administration reads, and visitor Q&A read a resolved committed tree through workspace-scoped contracts. Public callers cannot provide a visibility scope or reach `repo_exec`.
- `get_doc` returns a document and its opaque revision from the same committed snapshot. A `repo_exec` result can supply that snapshot's resolved commit, and no agent-facing contract requires or permits callers to derive a revision from file bytes.
- `repo_exec` requires both workspace authorization and `EXECUTE_REPOSITORY`. Attempts to read another workspace, repository authority, configuration, credentials, database or container sockets, or user-profile files fail inside the real sandbox.
- A command can use the declared Git, exact-search, shell, and Python tools against a session workspace with history. Commands in one authenticated session and resolved commit reuse that workspace; a workspace is never reused by another session, principal, workspace, or commit. Local changes never change workspace `main`, and no executor path can invoke a content write operation implicitly.
- The executor runs under an identity distinct from the application and repository authority and receives a verified snapshot with no authority credential. For a local source adapter, it creates a full-copy clone with no alternates or shared object inodes; a security test modifies permissions and bytes under the clone's object database while the source remains byte-identical and `git fsck` succeeds.
- Direct network access, proxy-based access, package installation, and remote Git access fail. Sandbox initialization failure never falls back to an unsandboxed process.
- Time, CPU, memory, descendant-process, temporary-storage, and output limits terminate the complete process tree and return distinct actionable results. Cleanup removes abandoned execution state after ordinary completion and simulated crashes.
- Cross-workspace tests run concurrent commands and prove that files, outputs, temporary state, caches, resource accounting, and audits remain isolated.
- A production-like exercise for every enabled profile covers one initial snapshot and a twenty-command member-agent session, recording total and post-startup latency, transfer cost, and steady-state and peak resource usage. Failure to preserve the sandbox boundary or deployment resource limit stops implementation for a new runtime or sizing decision.
- Requirements, current-state documents, skills, tests, and deployment artifacts contain no live claim that PostgreSQL or zhparser performs content search after implementation. Repository and application checks pass.

## Risks

Repository scanning has linear cost and exact search has weaker ranking and Chinese tokenization than the planned PostgreSQL path. Commit-keyed caches and agent-composed queries may be sufficient for a personal corpus; a persistent index needs evidence rather than assumption.

Sandbox Runtime is an early project and its platform behavior can change. Pinning it, testing the packaged runtime on the self-hosted production profile, failing closed, and keeping a Poketto-owned port limit dependency drift. The adapter remains a single-operator convenience boundary rather than hosted tenant isolation.

Model-produced code can be malicious because of prompt injection, dependency content, or ordinary mistakes. Filesystem and network isolation reduce impact, while resource and output limits bound denial of service; neither replaces patching the sandbox and host kernel.

Session workspaces add filesystem work and may become expensive as history grows. Their bounded lease amortizes a multi-step agent interaction without sharing mutable state across sessions. Any later cross-session reuse must not trade away commit consistency, workspace isolation, cleanup, or the guarantee that no execution mutation reaches authoritative history.
