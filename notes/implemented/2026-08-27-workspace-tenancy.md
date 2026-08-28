# Workspace and Tenant Boundaries

Date: 2026-08-27
Status: Implemented

## Problem

Without an explicit tenant boundary, content repositories, projections, caches, authorization, and background work could form around an implicit default tenant. Adding independent knowledge spaces after those features existed would require isolation conditions to be threaded through every layer. Search, caches, and asynchronous work are the easiest paths to miss because they do not all appear in the primary request flow.

Poketto needs multiple workspaces in its core data model while retaining single-workspace self-hosting as the default topology. Open registration, billing, and SaaS operation remain separate concerns.

## Decision

### Workspace model

- A `workspace` is the tenant, security, and data-destruction boundary. Each workspace has an immutable canonical lowercase UUID as its `workspace_id`; its name, public domain, and display slug are not identifiers.
- The application always uses the workspace model internally. A default deployment creates one workspace on the first successful database-backed start and exposes it through `WorkspaceCatalog`.
- `WorkspaceCatalog` supports lookup of the default workspace and lookup by `WorkspaceId`. It has no public creation or cross-workspace listing operation. Additional-workspace creation has no route, API, or configuration switch.
- An account may join multiple workspaces. A role belongs to the membership between an account and a workspace, not to the account globally. The [invitation-only membership proposal](../proposed/2026-08-27-invitation-only-membership.md) owns the joining flow and attaches the first owner to the existing default workspace.

### Data isolation

- Each workspace owns a separate content repository at `<data-dir>/workspaces/<workspace-id>/content`. `WorkspacePaths` derives that path only from an absolute data directory and a validated `WorkspaceId`; it does not accept workspace names, slugs, or caller-supplied path fragments.
- Every workspace-owned authoritative or derived PostgreSQL row carries `workspace_id` explicitly. Unique constraints, foreign keys, and queries include it. A projection checkpoint is keyed by its workspace, stores that workspace's last indexed commit, and is never shared across workspaces.
- Blobs use a workspace namespace. Even when two workspaces upload identical bytes, external paths, queries, and errors must not reveal that another workspace has the same hash. Physical deduplication is outside this decision.
- API keys, member permissions, visitor-Q&A budgets, audit records, cache keys, and background tasks belong to a workspace. Cross-workspace administration uses a distinct instance-level authority; a workspace owner is not implicitly an instance administrator.
- Deleting a workspace destroys its content repository, blob namespace, authoritative database rows, and derived projection. No deletion operation may be implemented until a separate proposal defines its waiting period, backup boundary, and recovery behavior.

### Context propagation and authorization

- `WorkspaceId` is the public module value type. Parsing accepts only canonical lowercase UUID text, so aliases, case variants, and path-like values never reach storage or path resolution.
- Workspace-owned operations at module boundaries accept `WorkspaceId` explicitly. They must not obtain it from global state, thread-local state, or an assumption that only one workspace exists.
- HTTP, MCP, and background entry points resolve an authorized workspace context before calling domain operations. A caller-provided path, document UUID, blob hash, or filter cannot substitute for the authorized `WorkspaceId`.
- Search, lists, history, errors, and counts must not expose another workspace's data or its existence. Missing and unauthorized objects cannot use distinguishable responses that allow cross-workspace enumeration.
- Asynchronous events and retry records persist `workspace_id` in their payload. Logs and metrics may include an opaque, non-reversible internal workspace identifier, but not a private name, content, or credential.

### Catalog and initialization

Flyway migration `V1__create_workspace_catalog.sql` creates the authoritative `workspaces` table. The primary key is `workspace_id`; a partial unique index permits at most one row marked as the default.

After migrations complete, an application runner opens a transaction, takes a table lock, and returns the existing default workspace or inserts one. The lock serializes concurrent first starts across application processes. A later start reuses the stored UUID instead of deriving a tenant from configuration or process state.

The `workspace` Spring Modulith module owns the public value types, catalog contract, and path resolver. JDBC and initialization classes remain below `workspace.internal`.

### Default topology and implementation scope

The default remains one instance, one workspace, one local content repository, local PostgreSQL, and a local blob directory. Multi-workspace isolation changes the data model; it does not require cloud services, Kubernetes, open registration, or multiple application replicas.

Cloud PostgreSQL uses the same JDBC contract and does not need a provider-specific driver abstraction. Kubernetes and object storage enter the repository only with a runnable implementation and automated verification; multi-workspace support does not depend on either.

The [content repository foundation](../proposed/2026-08-26-content-foundation.md) and invitation-only membership may proceed in parallel from this boundary. Content writes, projection, search, MCP, and visitor Q&A build on `WorkspaceId` instead of implementing a single-workspace path first.

This implementation does not include an additional-workspace UI, open registration, billing, tenant migration, cross-workspace search, shared documents, or workspace deletion.

## Alternatives considered

**Remain single-tenant and change later.** The first implementation would be smaller, but tenant scope crosses content paths, database constraints, caches, events, and authorization. Establishing the boundary before those features exist avoids a different security model for the default topology.

**Store every workspace in subdirectories of one Git repository.** This reduces repository count, but Git history, backup, recovery, and deletion would no longer be isolated per tenant. A repository per workspace aligns the Git boundary with the security boundary.

**Filter workspace access only at the HTTP layer.** This does not constrain background jobs, MCP, caches, or internal calls. Workspace scope belongs in module contracts and persistence keys, not only in route parameters.

**Add PostgreSQL Row-Level Security immediately.** RLS can provide another defense layer, but connection-pool transaction context, migration roles, and table-owner bypass need a separate design. The implementation uses explicit scope, database constraints, and isolation tests. A later security review may propose RLS.

## Consequences

Explicit scope on all workspace-owned data adds parameters to keys, queries, and tests. This is the intended cost of isolation. Features that introduce documents, blobs, caches, audit rows, background tasks, or projection checkpoints must prove cross-workspace isolation for that state.

A repository per workspace increases repository and background-worker counts. Workspaces have no cross-repository transaction dependency, so they can be sharded and processed in parallel while writes remain serialized within each repository. Distributed locks and message queues remain unnecessary until measured scale exceeds one process.

Application startup now requires a configured PostgreSQL data source. Flyway owns schema creation, and startup fails instead of running with an in-memory or process-local workspace identity when the database is unavailable.

## Verification

- `WorkspaceIdTests` covers canonical parsing and rejects case variants and path-like input.
- `WorkspacePathsTests` proves that two workspace IDs resolve to disjoint content directories below the configured absolute data directory.
- `ModularityTests` verifies the `workspace` module together with the existing application modules.
- `PostgresIntegrationIT` runs against the repository's PostgreSQL 17 + zhparser image. It verifies Flyway migration, one durable default workspace, catalog lookup, PostgreSQL 17, and Chinese token parsing.
- `./gradlew test`, `./gradlew integrationTest`, `./gradlew repoCheck`, and `git diff --check` pass.
