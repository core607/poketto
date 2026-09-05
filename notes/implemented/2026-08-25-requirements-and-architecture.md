# Requirements and Architecture

Date: 2026-08-25

The [repository authoring foundations](2026-09-05-repository-authoring-foundations.md) implement arbitrary-path text reads, bounded public snapshots and search, atomic text patches, relational identity, and local managed storage. The source tree also contains browser authentication, the Next.js blog and administration, exact-version image delivery, and the Spring AI MCP entrance. The [isolated acceptance entrance](../../acceptance/README.md) and [actual client workflows](../../acceptance/clients/README.md) define the reproducible local checks separately from complete browser and five-tool client workflows and final HTTPS installation acceptance. Their presence does not establish readiness for daily use.

The [phase-one delivery proposal](../proposed/2026-09-05-phase-one-daily-use.md) defines the daily-use installation and its acceptance criteria. It includes the blog, administration, and five repository MCP tools; it excludes backups, restore drills, visitor Q&A, consumer provisioning, and serverless from this delivery. These exclusions are not deployment prerequisites, and proposed capabilities are not shipped behavior.

## Scope of this record

This record preserves the primary single-server baseline and its selected product boundaries. [Remote repository authority](2026-09-01-remote-repository-authority.md), the [HTTP entrance baseline](2026-09-03-http-entrance-baseline.md), and the [validated content snapshot](2026-09-04-validated-content-snapshot.md) record the original implementations. The newer foundations and phase-one records own their replacements; historical and deferred sections below are not claims of shipped behavior.

The broader [frontend](../proposed/2026-08-30-nextjs-frontend.md), [managed assets](../proposed/2026-09-01-repository-asset-blob-store.md), [publishing and images](../proposed/2026-09-01-repository-native-publishing-and-assets.md), and [retrieval and sandbox execution](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) proposals remain proposed until their complete acceptance criteria are met. [Consumer provisioning](../proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md) and the [optional serverless profile](../proposed/2026-09-01-optional-serverless-deployment-profile.md) remain outside phase one. None changes the workspace tenant boundary.

## Positioning

Poketto is a self-hosted personal knowledge base whose public face is a blog. The same Markdown content serves both public publishing and, over MCP, the long-term memory of trusted AI agents.
The phase-one public face includes server-rendered articles, tags, archives, bounded search, RSS, and sitemap. Budget-capped visitor Q&A remains a deferred product target.

## Design principles

- Open source: code and project documents under Apache-2.0; artwork and published creative content under CC BY-NC-SA 4.0.
- Single instance, no open registration. Users are workspace owners, people they trust, and their AI agents, each acting within an authorized workspace through an issued identity or API key.
- Designed for a resource-constrained single machine; production capacity and resource limits require measurements on the selected host.
- Used by cloning and self-hosting. The code repository and workspace content repositories are separate. The operator supplies a pre-provisioned private HTTPS repository through secrets for the default workspace; remote `main` is authoritative and local repository storage is disposable cache.

## Core architecture decisions

1. Files are the source of truth. Each workspace owns one git repository containing Markdown. Historical selection only, never implemented and no longer current; replaced by [stock PostgreSQL](2026-09-05-stock-postgresql.md) and [repository-native retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md): PostgreSQL would have held only a derived content projection (the search_documents table), rebuildable in full at any time. Each workspace projection would have recorded its last processed commit in a checkpoint and caught up by replay after a crash; projection changes and checkpoint advancement would have committed in one database transaction.
2. Write model: each workspace repository's remote `main` branch is the truth. MCP and administration share a bounded UTF-8 patch service, retain unchanged source, build a candidate commit with caller attribution, and advance the remote ref only from the expected base. A competing push is a conflict. A lost response requires reconciliation against remote `main`, never blind retry. Optional metadata errors and unsafe files produce file-level diagnostics; an invalid publication policy closes public service. Repository acknowledgement is distinct from snapshot installation.
3. Historical selection only, never implemented and no longer current; replaced by [stock PostgreSQL](2026-09-05-stock-postgresql.md) and [repository-native retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md): Retrieval was designed to be agentic by default. The server would have provided cheap retrieval primitives: full-text search (zhparser + tsvector + GIN + ts_rank_cd), tag and time filters, snippet-only responses; the calling AI would have iterated its own queries. Embeddings were reserved as a pluggable experiment (a separate side table without requiring pgvector), conditional on real-query evaluation.
4. Layered trust. A workspace owner may author through the private remote repository; Poketto observes the next remote `main` without treating cache edits as content. Member AIs use MCP with scoped API keys. Capabilities are READ_PRIVATE, WRITE_PRIVATE, PUBLISH, MANAGE_KEYS, and EXECUTE_REPOSITORY; AI keys lack the last three by default. Public search fixes its public scope internally. Members and keys must pass current workspace authorization before private reads or writes.
5. Workspace isolation. A workspace is the tenant, security, and data-destruction boundary. Module operations, PostgreSQL rows, content paths, blobs, caches, budgets, audit records, and background tasks carry an explicit `WorkspaceId`; entry points resolve an authorized workspace before invoking them. Missing and unauthorized objects do not reveal another workspace's existence. The default deployment creates one workspace and has no self-service additional-workspace entry point.

## Current MCP contract

The phase-one contract supersedes the original UUID-based document tool selection. `/mcp` uses Streamable HTTP and workspace Bearer API keys independently of browser sessions. Its tools are `get_file`, `get_asset`, `put_asset`, and `repo_patch`, with `repo_exec` registered only when the isolated execution adapter is enabled. The [local worker](../../executor-service/README.md) is a separate Linux service; enabling the adapter does not substitute for verifying the real process boundary.

Files use repository-relative paths without mandatory frontmatter IDs. `get_file` returns authoritative UTF-8 bytes as text, the resolved commit, a service-issued revision, diagnostics, and explicit expected absence. `repo_patch` checks the base commit and each changed path's revision or absence. Images have exact Git or immutable managed versions; uploading neither writes Git nor publishes. Execution sessions remain pinned to their resolved commit, and commands cannot alter what authoritative reads return. The phase-one record owns the complete tool, cancellation, permission, and acceptance contracts.

## Deferred visitor Q&A design

The upstream LLM key exists only in server-side environment variables. The daily budget reserves per agent run: the worst case (context growing per round + output cap × round cap) is deducted up front; if the reservation fails, the run does not start; actual usage settles afterwards. Per-IP token bucket (in-JVM). Price tables are configuration.
clip_url SSRF protections: http/https only; block private, loopback, link-local, and cloud metadata addresses after DNS resolution; re-validate every redirect hop; timeout, size, and content-type limits. Fetched content is untrusted data; instruction-like text inside it is never executed as instructions.
Rendering pipeline: raw HTML disabled, URLs sanitized, an output HTML sanitizer, and CSP headers.

## Deferred backup design

The following describes the broader backup target, not a supplied backup service. Backups and restore drills are excluded from phase-one implementation and are not deployment prerequisites.

Each workspace's document text and history ride its content repository's git remote; image blobs and non-derived database tables (workspace catalog, keys, audit, budget) each have off-host scheduled backups; no content projection exists to back up.

## Images

The phase-one asset contract supersedes the original hash-only reference and image-index selection. Local managed originals live outside Git under workspace namespaces and use immutable asset-identity/revision references. Git images remain read-only and materialize into a disposable cache. Public grants bind the page snapshot and exact image version for at most five minutes, bounded by snapshot expiry; private reads recheck current authorization. All acknowledged managed originals are retained. Image transformations, pHash, descriptions, and persistent image indexes are outside this delivery.

## Technology stack

The build requires JDK 26 and pins Spring Boot 4.1.1, Spring AI 2.0.1, and the frontend dependencies and lockfile. Spring Modulith defines application module boundaries; JGit owns repository access, commonmark-java and Jackson YAML parse content, and [official PostgreSQL 17](2026-09-05-stock-postgresql.md) stores relational application state. Next.js with Tailwind replaces the original JTE + htmx selection; its broader [frontend proposal](../proposed/2026-08-30-nextjs-frontend.md) remains subject to complete acceptance.
CI: GitHub Actions + Testcontainers; images publish to GHCR. A docker-save-over-SSH deployment script is provided for networks with restricted registry access. GraalVM Native Image and JDK structured concurrency (preview) stay on the experimental track.
The MCP protocol version is pinned to the verified version of the SDK in use; static API keys in v1 are a deliberate simplification, with no claim to the MCP standard OAuth flow; Streamable HTTP validates an Origin allowlist.

## Non-goals (v1)

Open registration and self-service workspace creation, OAuth, comments/likes/social features, microservices/K8s/message queues, knowledge graphs, heavy RAG pipelines (chunking + reranking + multi-path recall), rich-text editors, image CDN, mobile apps, UI internationalization, visitor conversation history, Redis (single-instance: budget counting in PostgreSQL, rate limiting in the JVM, caching in Caffeine).
