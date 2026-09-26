# Content Repository and Document Foundation

Date: 2026-08-26
Status: Implemented

[Chinese](2026-08-26-content-foundation.zh.md)

## Problem

Poketto needs a durable content boundary before it can implement writes, projection, search, rendering, or MCP tools. At the time, the [requirements](2026-08-25-requirements-and-architecture.md) made a separate git repository the source of truth, document identity a repository-wide UUID, and revisions content hashes. The implemented [workspace boundary](2026-08-27-workspace-tenancy.md) assigns one repository to each workspace. This decision defines the repository bootstrap contract, managed path layout, frontmatter schema, canonical machine-written form, and revision encoding.

If those details emerge independently inside later features, the same document will acquire incompatible representations across the content, projection, web, and MCP modules.

[Remote repository authority](2026-09-01-remote-repository-authority.md) supersedes this note's original local-bootstrap boundary and owns current cache and acknowledgement behavior. The revision decisions below remain in force.

[Repository authoring foundations](2026-09-05-repository-authoring-foundations.md) implement arbitrary-path reads and atomic patches without the `documents/` layout or frontmatter identifiers, and record why the UUID writer, canonical serializer and `documents/` scan described below were removed. Normalized collision detection, the canonical UUID form of the optional article `id` and exact-blob revisions remain in force.

## Decision

### Data directory and repository cache

- Require an absolute `poketto.data-dir` configuration value. Do not default to a path inside the application checkout or container filesystem.
- Own each workspace's disposable content cache at `<data-dir>/workspaces/<workspace-id>/content`. Resolve the path only from a validated `WorkspaceId`; workspace names, slugs, repository coordinates, and caller-supplied paths never select a directory. Other workspace data may gain sibling directories later, but it does not belong inside the repository cache unless a decision explicitly says so.
- Require a secret-backed remote binding. An absent or invalid binding fails closed before a local cache can be mistaken for authority.
- When the cache is absent or empty, create a non-bare repository on `main`, fetch remote `main`, and record that exact commit as local `main` without checking files out. A pre-provisioned empty remote stays unborn; the first exact-ref write creates its root commit.
- Treat every cache file as machine-owned and disposable. Reads and writes use Git objects at the resolved commit and never read worktree files. Direct authoring happens through the private remote, never in the cache.
- Refuse a non-directory, a non-empty path that is not the expected worktree, or unreadable repository metadata. A configured workspace bound limits resident caches and evicts only idle entries.

Repository and transport failures identify the workspace without exposing repository coordinates or credentials. Tests provide their own temporary absolute data directories and disposable bare remotes. The local run documentation explains the required settings.

### Managed document layout

- Manage Markdown documents only below `documents/` in the content repository. Root files may describe or configure the repository without becoming user documents.
- Treat a path as location, not identity. A document may move anywhere below `documents/` without changing its UUID.
- Accept UTF-8 `.md` files at any depth. Reject absolute paths, traversal, non-Markdown extensions, names Windows cannot store (reserved device names, `<>:"|?*` and control characters, segments ending in a dot or space, an empty name before `.md`), and path collisions after Unicode NFC normalization and case folding so the same repository behaves consistently on Windows and Linux.

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
- `created_at` and `updated_at` are required RFC 3339 UTC instants. The removed UUID writer preserved `created_at` and advanced `updated_at` whenever the serialized document changed. The live repository writer does not maintain these fields: it commits the submitted bytes, and on read authored date fields take precedence while Git history supplies missing dates ([phase-one delivery](2026-09-05-phase-one-daily-use.md)).
- `published_at` is optional. The first publish operation sets it; later edits or a visibility change back to private do not erase it.
- Unknown fields, duplicate YAML keys, aliases, custom tags, multiple YAML documents, malformed delimiters, invalid UTF-8, and a byte-order mark are invalid for machine writes.
- The body may be empty. This layer preserves it as text and does not render Markdown, sanitize HTML, fetch links, or interpret instructions.

Machine writes serialize frontmatter in the field order shown above, add `published_at` after `updated_at` when present, use UTF-8 and LF line endings, place one blank line before the body, and end the file with one newline. Human commits need not use the canonical layout; the repository reader reports invalid files as per-file diagnostics.

### Identity and revision types

- Require `WorkspaceId` on content-module operations and expose immutable value types for document ID and revision. Keep JGit and YAML implementation classes below `content.internal`.
- Calculate a revision as SHA-256 over the exact blob bytes at the selected git tree. Encode it as `sha256:<lowercase-hex>` and treat the whole value as opaque outside the content module.
- Do not derive revisions from parsed fields or commit SHAs. Formatting and line-ending changes are edits and therefore produce new revisions.
- Detect duplicate document UUIDs while scanning a tree. Return a repository-integrity error naming every conflicting path; never choose one document implicitly.

### Implemented scope

The content module binds the data directory, resolves per-workspace remote authority into disposable caches, exposes the document ID and revision types, and reads commit-pinned `main` trees. HTTP and MCP writes use the patch service of the repository authoring foundations; the UUID writer and canonical serializer that first implemented this note are removed.

[Repository-native publishing and images](../rejected/2026-09-01-repository-native-publishing-and-assets.md) proposes replacing the target `documents/`, UUID, per-file visibility, and hash-only image-reference requirements with arbitrary nested Markdown, repository publishing policy, immutable managed references, and read-only sibling-image galleries. The [repository authoring foundations](2026-09-05-repository-authoring-foundations.md) implement that replacement; this note records the removed UUID layout and the rules that outlived it.

## Alternatives

Defaulting the data directory to `./data` would make a first run easier, but it can silently place durable content inside a source checkout or an ephemeral container layer. An explicit absolute path makes persistence an operator decision.

Adopting an existing local directory would ease imports, but it would silently restore local authority and create ambiguous acknowledgement. Existing content must be committed and pushed to the configured private remote before Poketto adopts it.

Treating every Markdown file in the repository as a document would avoid one directory level, but it prevents repository-local instructions and metadata from coexisting safely. `documents/` is the single managed subtree.

Keeping one shared content repository and placing workspaces below separate subdirectories would reduce repository count, but it would couple history, backup, recovery, and destructive operations across security boundaries. One repository per workspace keeps repository-wide operations inside one tenant.

Using paths or slugs as identity would simplify lookup, but renames would become delete-and-create operations and break stable MCP references. UUID frontmatter keeps identity independent from organization and public URLs.

Hashing parsed content would ignore harmless formatting changes, but it requires a semantic canonicalization contract and can hide edits from optimistic concurrency. Hashing exact git blob bytes matches what was committed and remains language-independent.

Allowing arbitrary frontmatter fields would make extensions easy, but misspellings would become durable data and downstream modules would infer different schemas. Schema evolution should be explicit while the project has no compatibility obligation.

## Verification

`ContentRepositoryBootstrapTests`, `DocumentValueTests`, `DocumentPathRulesTests` and `ModularityTests` pin the rules still in force. The integration suite checks that workspace catalog initialization and repository bootstrap complete together against PostgreSQL.

## Risks

Strict frontmatter means future fields require an intentional schema change and tests before agents can write them. This is acceptable before the first release and prevents accidental schema growth.

Exact-byte revisions make manual line-ending or formatting changes visible as conflicts. Machine output is canonical, and treating manual byte changes as real revisions is safer than silently overwriting them.

Repository-wide scanning is linear in document count. It is the simplest correct foundation; later work may add an in-memory catalog or derived index without changing git's authority.

Per-workspace repositories increase the number of Git handles, caches, and scans. Repository resources open only for the scoped operation and close deterministically; the configured cache bound prevents unbounded cache growth.

The removed `documents/` scan failed the whole repository when any managed file was invalid, so one malformed break-glass commit blocked every document on the UUID path. The repository reader of the [phase-one delivery](2026-09-05-phase-one-daily-use.md) instead reports per-file diagnostics and excludes only the affected files.
