# Requirements and Architecture

Date: 2026-08-25

## Scope of this record

This implemented note records the primary single-server baseline and the product contracts selected for it. The proposed [remote repository authority](../proposed/2026-09-01-remote-repository-authority.md), [managed assets and repository image materialization](../proposed/2026-09-01-repository-asset-blob-store.md), [repository-native publishing and images](../proposed/2026-09-01-repository-native-publishing-and-assets.md), [repository-native retrieval and sandboxed execution](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), and [consumer accounts and personal workspaces](../proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md) define accepted target changes without changing the workspace tenant boundary. The [optional serverless profile](../proposed/2026-09-01-optional-serverless-deployment-profile.md) keeps the single-server profile primary while selecting OSS, shared state, and remote SRT only when real infrastructure is available. Until those proposals are implemented, the mechanics below describe the executable baseline.

## Positioning

Poketto is a self-hosted personal knowledge base whose public face is a blog. The same Markdown content serves both public publishing and, over MCP, the long-term memory of trusted AI agents.
The public face includes: article rendering (server-side), tag and archive pages, RSS and sitemap, and a budget-capped visitor Q&A.

## Design principles

- Open source: code and project documents under Apache-2.0; artwork and published creative content under CC BY-NC-SA 4.0.
- Single instance, no open registration. Users are workspace owners, people they trust, and their AI agents, each acting within an authorized workspace through an issued identity or API key.
- Designed for a low-spec single machine (2 cores / 4 GB runs the full stack); components are chosen to conserve resources.
- Used by cloning and self-hosting. The code repository and workspace content repositories are separate: the service git-inits each content repository when its workspace is provisioned; a backup remote is optional.

## Core architecture decisions

1. Files are the source of truth. Each workspace owns one git repository containing Markdown; PostgreSQL holds only a derived content projection (the search_documents table), rebuildable in full at any time. Each workspace projection records its last processed commit in a checkpoint and catches up by replay after a crash; projection changes and checkpoint advance commit in one database transaction.
2. Write model: each workspace repository's main branch is the truth. Machine entrances (MCP, admin UI) validate frontmatter strictly, serialize writes per repository, and commit on the caller's behalf, recording the caller's identity; manual git push is guarded by git's native non-fast-forward check, and the projection lints rather than rejects its content. A successful git commit is the acknowledgment point; when the index lags, the response reports committed and indexed separately so a retrying caller does not create duplicates.
3. Retrieval is agentic by default. The server provides cheap retrieval primitives: full-text search (zhparser + tsvector + GIN + ts_rank_cd), tag and time filters, snippet-only responses; the calling AI iterates its own queries. Embeddings are a pluggable experiment (a separate side table; the base schema does not require pgvector), admitted only if evaluation on real queries proves their value.
4. Layered trust. A workspace owner may operate directly on that workspace's files (break-glass; reindex explicitly afterwards). Member AIs use MCP with scoped API keys; capabilities are READ_PRIVATE, WRITE_PRIVATE, PUBLISH, and MANAGE_KEYS, and AI keys lack the last two by default. Visitors read only the rendered public pages; the visitor Q&A service is constructed with a public-content-only search dependency — no scope parameter exists in its interface.
5. Workspace isolation. A workspace is the tenant, security, and data-destruction boundary. Module operations, PostgreSQL rows, content paths, blobs, caches, budgets, audit records, and background tasks carry an explicit `WorkspaceId`; entry points resolve an authorized workspace before invoking them. Missing and unauthorized objects do not reveal another workspace's existence. The default deployment creates one workspace and has no self-service additional-workspace entry point.

## MCP tools

search, get_doc, list_tags, list_recent, create_doc, update_doc, delete_doc, clip_url, publish, history.
Document identity is a UUID in frontmatter: assigned at creation, stable across renames, and unique within its workspace repository; another workspace may use the same UUID independently. revision is a hash of the document content (an opaque token externally); commit SHAs are audit-only. update and delete carry expected_revision and return a conflict rather than overwrite on mismatch. publish flips a document's visibility to public and commits; publication to the internet is irreversible in practice, and the admin UI must say so. Error messages are written for AI callers and include actionable corrections.

## Visitor Q&A guardrails

The upstream LLM key exists only in server-side environment variables. The daily budget reserves per agent run: the worst case (context growing per round + output cap × round cap) is deducted up front; if the reservation fails, the run does not start; actual usage settles afterwards. Per-IP token bucket (in-JVM). Price tables are configuration.
clip_url SSRF protections: http/https only; block private, loopback, link-local, and cloud metadata addresses after DNS resolution; re-validate every redirect hop; timeout, size, and content-type limits. Fetched content is untrusted data; instruction-like text inside it is never executed as instructions.
Rendering pipeline: raw HTML disabled, URLs sanitized, an output HTML sanitizer, and CSP headers.

## Backups

Each workspace's document text and history ride its content repository's git remote; image blobs and non-derived database tables (workspace catalog, keys, audit, budget) each have off-host scheduled backups; the projection table is not backed up — it rebuilds.

## Images

Stored content-addressed by SHA-256 inside a workspace namespace in the data directory, outside git; documents reference hashes, not paths; v1 never deletes physically. Retrieval starts with filenames and sidecar text in the index, plus pHash near-duplicate detection; VLM descriptions and multimodal embeddings are later-stage evaluations.

## Technology stack

JDK 26 (fallback: 25 LTS), Spring Boot 4, Spring Modulith (modules: workspace / content / projection / search / web / qa / mcp / auth), Spring AI (MCP server and tool calling), JGit, commonmark-java + Jackson YAML, PostgreSQL 17 + zhparser (requires a custom image, not the stock postgres image), Caffeine, JTE + htmx + Tailwind.
CI: GitHub Actions + Testcontainers; images publish to GHCR. A docker-save-over-SSH deployment script is provided for networks with restricted registry access. GraalVM Native Image and JDK structured concurrency (preview) stay on the experimental track.
The MCP protocol version is pinned to the verified version of the SDK in use; static API keys in v1 are a deliberate simplification, with no claim to the MCP standard OAuth flow; Streamable HTTP validates an Origin allowlist.

## Non-goals (v1)

Open registration and self-service workspace creation, OAuth, comments/likes/social features, microservices/K8s/message queues, knowledge graphs, heavy RAG pipelines (chunking + reranking + multi-path recall), rich-text editors, image CDN, mobile apps, UI internationalization, visitor conversation history, Redis (single-instance: budget counting in PostgreSQL, rate limiting in the JVM, caching in Caffeine).
