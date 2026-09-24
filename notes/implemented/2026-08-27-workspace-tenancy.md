# Workspace and Tenant Boundaries

Date: 2026-08-27
Status: Implemented

## Problem

Without an explicit tenant boundary, content repositories, projections, caches, authorization, and background work could form around an implicit default tenant. Adding independent knowledge spaces after those features existed would require isolation conditions to be threaded through every layer. Search, caches, and asynchronous work are the easiest paths to miss because they do not all appear in the primary request flow.

Poketto needs multiple workspaces in its core data model while keeping single-workspace self-hosting possible. [Multi-user workspaces](2026-09-11-multiuser-workspaces-and-discovery.md), [consumer identity](2026-09-20-consumer-identity-and-site-policy.md) and [GitHub-authorized personal spaces](2026-09-21-github-authorized-personal-spaces.md) build registration and space creation on this boundary without making an account the tenant.

## Decision

### Workspace model

- A `workspace` is the tenant, security, and data-destruction boundary. Each workspace has an immutable canonical lowercase UUID as its `workspace_id`; its name, public domain, and display slug are not identifiers.
- The application always uses the workspace model internally. A default deployment creates one workspace on the first successful database-backed start and exposes it through `WorkspaceCatalog`.
- `WorkspaceCatalog` supports lookup of the default workspace and lookup by `WorkspaceId`. [Managed workspace connections](2026-09-11-managed-workspace-connections.md) add transactional creation through `WorkspaceRegistry` and account-level HTTP operations; catalog lookup remains separate from membership authorization.
- An account may join multiple workspaces. A role belongs to the membership between an account and a workspace, not to the account globally. [Invitation-only membership](2026-08-27-invitation-only-membership.md) owns the joining flow, and [operator administrator setup](2026-09-11-operator-administrator-setup.md) attaches the first owner to the default workspace.

### Data isolation

- Each workspace owns a separate private remote content repository. `<data-dir>/workspaces/<workspace-id>/content` is only its disposable cache. `WorkspacePaths` derives that cache path only from an absolute data directory and a validated `WorkspaceId`; it does not accept workspace names, slugs, repository coordinates, or caller-supplied path fragments. The authority adapter resolves the remote binding from the same authorized workspace scope.
- Every workspace-owned authoritative or derived PostgreSQL row carries `workspace_id` explicitly. Unique constraints, foreign keys, and queries include it.
- Blobs use a workspace namespace. Even when two workspaces upload identical bytes, external paths, queries, and errors must not reveal that another workspace has the same hash. Physical deduplication happens only within one workspace ([storage port](2026-09-05-repository-authoring-foundations.md#managed-originals-and-image-delivery)).
- API keys, member permissions, visitor-Q&A budgets, audit records, cache keys, and background tasks belong to a workspace. Cross-workspace administration uses a distinct instance-level authority; a workspace owner is not implicitly an instance administrator.
- Deleting a workspace will destroy its remote repository binding and provider resource, blob namespace, and authoritative database rows. No deletion operation may be implemented until a separate proposal defines its waiting period, ownership proof, backup boundary, and recovery behavior.

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

Setting `poketto.workspace.catalog.enabled=false` disables the catalog and its initializer. The switch exists only so the no-database application-context smoke test can start; it is not a supported deployment option.

### Topology and scope

One instance serves any number of workspaces. The database-created default workspace binds to the operator-provisioned repository, and each further space brings its own repository. Every workspace has one disposable local repository cache; the instance uses local PostgreSQL and a local managed-original store. Multi-workspace isolation is a data-model property; it requires no Kubernetes or multiple application replicas.

Cloud PostgreSQL uses the same JDBC contract and does not need a provider-specific driver abstraction. Kubernetes and object storage enter the repository only with a runnable implementation and automated verification; multi-workspace support does not depend on either.

Content writes, public snapshots, search, MCP, and execution take a `WorkspaceId` rather than a single-workspace path. [Remote repository authority](2026-09-01-remote-repository-authority.md) keeps repository truth off the request host, and managed originals and repository-image caches are workspace-scoped. Billing, tenant migration, private cross-workspace search, shared documents, and workspace deletion are not implemented.

## Alternatives considered

**Remain single-tenant and change later.** The first implementation would be smaller, but tenant scope crosses content paths, database constraints, caches, events, and authorization. Establishing the boundary before those features exist avoids a different security model for the default topology.

**Store every workspace in subdirectories of one Git repository.** This reduces repository count, but Git history, backup, recovery, and deletion would no longer be isolated per tenant. A repository per workspace aligns the Git boundary with the security boundary.

**Filter workspace access only at the HTTP layer.** This does not constrain background jobs, MCP, caches, or internal calls. Workspace scope belongs in module contracts and persistence keys, not only in route parameters.

**Add PostgreSQL Row-Level Security immediately.** RLS can provide another defense layer, but connection-pool transaction context, migration roles, and table-owner bypass need a separate design. The implementation uses explicit scope, database constraints, and isolation tests. A later security review may propose RLS.

## Consequences

Explicit scope on all workspace-owned data adds parameters to keys, queries, and tests. This is the intended cost of isolation. Features that introduce documents, blobs, caches, audit rows, or background tasks must prove cross-workspace isolation for that state.

A repository per workspace increases repository and background-worker counts. Workspaces have no cross-repository transaction dependency, so they can be sharded and processed in parallel while writes remain serialized within each repository. Distributed locks and message queues remain unnecessary until measured scale exceeds one process.

Application startup requires a configured PostgreSQL data source. Flyway owns schema creation, and startup fails instead of running with an in-memory or process-local workspace identity when the database is unavailable.

## Verification

`WorkspaceIdTests`, `WorkspacePathsTests`, `ModularityTests` and `PostgresIntegrationIT` pin canonical parsing, disjoint cache paths, module ownership, and creation and reuse of the default workspace.
