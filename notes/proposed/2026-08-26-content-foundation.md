# Content Repository and Document Foundation

Date: 2026-08-26
Status: Proposed

[Chinese](2026-08-26-content-foundation.zh.md)

## Problem

Poketto needs a durable content boundary before it can implement writes, projection, search, rendering, or MCP tools. The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) establish that a separate git repository is the source of truth, document identity is a repository-wide UUID, and revisions are content hashes. The [workspace boundary proposal](2026-08-27-workspace-tenancy.md) assigns one repository to each workspace. These decisions do not yet define the repository bootstrap contract, managed path layout, frontmatter schema, canonical machine-written form, or revision encoding.

If those details emerge independently inside later features, the same document will acquire incompatible representations across the content, projection, web, and MCP modules.

## Proposal

### Data directory and repository bootstrap

- Require an absolute `poketto.data-dir` configuration value. Do not default to a path inside the application checkout or container filesystem.
- Own each workspace content worktree at `<data-dir>/workspaces/<workspace-id>/content`. Resolve the path only from a validated `WorkspaceId`; workspace names, slugs, and caller-supplied paths never select a directory. Other durable workspace data may gain sibling directories later, but it does not belong inside the content repository unless a decision explicitly says so.
- When the content directory is absent or empty, create a non-bare repository whose initial branch is `main`. Leave it unborn: the first document write creates the root commit instead of adding a synthetic bootstrap commit.
- When the directory already contains a repository, accept only a non-bare worktree on `main`, including an unborn `main`. Preserve its remotes and configuration.
- Refuse to initialize a non-empty directory that is not already a git repository. The error must identify the path and tell the operator to choose an empty directory or initialize and commit the existing content explicitly.
- Fail startup on a bare repository, a worktree whose current branch is not `main`, or a repository whose metadata cannot be read. Repository repair and branch switching remain operator actions.

Repository validation and failure messages identify both the workspace and resolved path without disclosing another workspace's directory. Tests provide their own temporary absolute data directories. Implementing this proposal also updates the local run documentation so `bootRun` supplies or explains the required setting.

### Managed document layout

- Manage Markdown documents only below `documents/` in the content repository. Root files may describe or configure the repository without becoming user documents.
- Treat a path as location, not identity. A document may move anywhere below `documents/` without changing its UUID.
- Accept UTF-8 `.md` files at any depth. Reject absolute paths, traversal, non-Markdown extensions, and path collisions after Unicode NFC normalization and case folding so the same repository behaves consistently on Windows and Linux.

### Frontmatter and body

Every machine-written document uses YAML frontmatter followed by a Markdown body:

```markdown
---
id: 550e8400-e29b-41d4-a716-446655440000
title: Example document
visibility: private
tags:
  - example
created_at: 2026-08-26T09:00:00Z
updated_at: 2026-08-26T09:00:00Z
---

Markdown body.
```

- `id` is a canonical lowercase UUID and is immutable after creation. The content layer enforces uniqueness within its workspace repository; the same document UUID may exist independently in another workspace.
- `title` is required, trimmed, non-empty, and contains no control characters.
- `visibility` is exactly `private` or `public`.
- `tags` is an explicit YAML sequence. Values are trimmed, non-empty strings; duplicates after Unicode normalization and case folding are invalid while original display spelling is preserved.
- `created_at` and `updated_at` are required RFC 3339 UTC instants. Machine writes preserve `created_at` and advance `updated_at` whenever the serialized document changes. This layer's canonical serialization owns that transition rule; write entry points added later reuse it rather than restating it.
- `published_at` is optional. The first publish operation sets it; later edits or a visibility change back to private do not erase it.
- Unknown fields, duplicate YAML keys, aliases, custom tags, multiple YAML documents, malformed delimiters, invalid UTF-8, and a byte-order mark are invalid for machine writes.
- The body may be empty. This layer preserves it as text and does not render Markdown, sanitize HTML, fetch links, or interpret instructions.

Machine writes serialize frontmatter in the field order shown above, add `published_at` after `updated_at` when present, use UTF-8 and LF line endings, place one blank line before the body, and end the file with one newline. Human commits need not use the canonical layout; projection will lint invalid files as required by the architecture.

### Identity and revision types

- Require `WorkspaceId` on content-module operations and expose immutable value types for document ID, revision, visibility, metadata, and document content. Keep JGit and YAML implementation classes below `content.internal`.
- Calculate a revision as SHA-256 over the exact blob bytes at the selected git tree. Encode it as `sha256:<lowercase-hex>` and treat the whole value as opaque outside the content module.
- Do not derive revisions from parsed fields or commit SHAs. Formatting and line-ending changes are edits and therefore produce new revisions.
- Detect duplicate document UUIDs while scanning a tree. Return a repository-integrity error naming every conflicting path; never choose one document implicitly.

### Scope of the first implementation

The [workspace boundary](2026-08-27-workspace-tenancy.md) is a serial prerequisite. After it is implemented, this first implementation adds configuration binding, per-workspace repository bootstrap and validation, document parsing and canonical serialization, value types, tree scanning, and focused tests. It does not add create, update, delete, publish, projection, HTTP, or MCP entry points. Those operations will build on this boundary in later short-lived changes.

## Alternatives

Defaulting the data directory to `./data` would make a first run easier, but it can silently place durable content inside a source checkout or an ephemeral container layer. An explicit absolute path makes persistence an operator decision.

Initializing any existing directory would ease imports, but it could silently adopt unreviewed files and create ambiguous first-commit ownership. Existing content must be initialized and committed explicitly before Poketto adopts it.

Treating every Markdown file in the repository as a document would avoid one directory level, but it prevents repository-local instructions and metadata from coexisting safely. `documents/` is the single managed subtree.

Keeping one shared content repository and placing workspaces below separate subdirectories would reduce repository count, but it would couple history, backup, recovery, and destructive operations across security boundaries. One repository per workspace keeps repository-wide operations inside one tenant.

Using paths or slugs as identity would simplify lookup, but renames would become delete-and-create operations and break stable MCP references. UUID frontmatter keeps identity independent from organization and public URLs.

Hashing parsed content would ignore harmless formatting changes, but it requires a semantic canonicalization contract and can hide edits from optimistic concurrency. Hashing exact git blob bytes matches what was committed and remains language-independent.

Allowing arbitrary frontmatter fields would make extensions easy, but misspellings would become durable data and downstream modules would infer different schemas. Schema evolution should be explicit while the project has no compatibility obligation.

## Acceptance

- Repository tests cover absent, empty, valid existing, non-empty non-repository, bare, unreadable, wrong-branch, and unborn-`main` cases without using the developer's real data directory. Two workspaces can use identical relative paths and document UUIDs without sharing repositories or scan results.
- Document tests cover every field invariant, YAML restriction, canonical byte output, empty and Unicode bodies, path validation, tag normalization, timestamp transition rules, and optional `published_at` round trips.
- Revision tests pin exact byte hashing and prove that content, metadata, formatting, and line-ending edits change the token while identical blobs do not.
- Tree-scan tests detect duplicate UUIDs and cross-platform path collisions with errors that name every conflicting repository path.
- Spring Modulith verification continues to pass; content contracts require `WorkspaceId` and do not expose JGit or YAML implementation types.
- `./gradlew test`, `./gradlew repoCheck`, and `git diff --check` pass. The implementation does not require Docker because it does not touch PostgreSQL.

## Risks

Strict frontmatter means future fields require an intentional schema change and tests before agents can write them. This is acceptable before the first release and prevents accidental schema growth.

Exact-byte revisions make manual line-ending or formatting changes visible as conflicts. Machine output is canonical, and treating manual byte changes as real revisions is safer than silently overwriting them.

Repository-wide scanning is linear in document count. It is the simplest correct foundation; later work may add an in-memory catalog or derived index without changing git's authority.

Per-workspace repositories increase the number of Git handles and scans. Repository resources must be opened for the scoped operation and closed deterministically; the first implementation does not keep an unbounded cache of open repositories.
