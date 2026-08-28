# Workspace and Tenant Boundaries

Date: 2026-08-27
Status: Proposed

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) limit Poketto to one instance and one tenant. That constraint reduces the first implementation surface, but it also allows content repositories, projections, caches, authorization, and background work to form without a tenant boundary. Adding independent knowledge spaces later would require isolation conditions to be threaded through every layer. Search, caches, and asynchronous work are the easiest paths to miss because they do not all appear in the primary request flow.

Poketto needs multiple workspaces in its core data model while retaining single-workspace self-hosting as the default topology. Open registration, billing, and SaaS operation are separate concerns.

Once implemented, this proposal replaces the requirements' single-tenant restriction while preserving the prohibition on open registration.

## Proposal

### Workspace model

- A `workspace` is the tenant, security, and data-destruction boundary. Each workspace has an immutable canonical lowercase UUID as its `workspace_id`; its name, public domain, and display slug are not identifiers.
- The application always uses the workspace model internally. A default deployment creates one workspace during owner initialization and does not show a workspace switcher.
- Self-service creation of additional workspaces is disabled by default. When the operator explicitly enables it, only an instance administrator may create them. This setting does not change the database, directory, or authorization model.
- An account may join multiple workspaces. A role belongs to the membership between an account and a workspace, not to the account globally. The [invitation-only membership proposal](2026-08-27-invitation-only-membership.md) owns the joining flow.

### Data isolation

- Each workspace owns a separate content repository at `<data-dir>/workspaces/<workspace-id>/content`. One repository serves one workspace, and the single-writer constraint applies independently to each repository.
- Every workspace-owned authoritative or derived PostgreSQL row carries `workspace_id` explicitly. Unique constraints, foreign keys, and queries include it. Projection checkpoints are identified by at least `(workspace_id, last_indexed_commit)`.
- Blobs use a workspace namespace. Even when two workspaces upload identical bytes, external paths, queries, and errors must not reveal that another workspace has the same hash. Physical deduplication is outside this proposal.
- API keys, member permissions, visitor-Q&A budgets, audit records, cache keys, and background tasks belong to a workspace. Cross-workspace administration uses a distinct instance-level authority; a workspace owner is not implicitly an instance administrator.
- Deleting a workspace destroys its content repository, blob namespace, authoritative database rows, and derived projection. No deletion operation may be implemented until a separate proposal defines its waiting period, backup boundary, and recovery behavior.

### Context propagation and authorization

- Workspace-owned operations at module boundaries accept `WorkspaceId` explicitly. They must not obtain it from global state, thread-local state, or an assumption that only one workspace exists.
- HTTP, MCP, and background entry points resolve an authorized workspace context before calling domain operations. A caller-provided path, document UUID, blob hash, or filter cannot substitute for the authorized `WorkspaceId`.
- Search, lists, history, errors, and counts must not expose another workspace's data or its existence. Missing and unauthorized objects cannot use distinguishable responses that allow cross-workspace enumeration.
- Asynchronous events and retry records persist `workspace_id` in their payload. Logs and metrics may include an opaque, non-reversible internal workspace identifier, but not a private name, content, or credential.

### Default topology

The default remains one instance, one workspace, one local content repository, local PostgreSQL, and a local blob directory. Multi-workspace isolation changes the data model; it does not require cloud services, Kubernetes, open registration, or multiple application replicas.

Cloud PostgreSQL uses the same JDBC contract and does not need a provider-specific driver abstraction. Kubernetes and object storage enter the repository only with a runnable implementation and automated verification; multi-workspace support does not depend on either.

## First implementation scope and order

This proposal is a serial prerequisite for content, authorization, and projection work. The first implementation introduces the `WorkspaceId` value type, workspace path resolution, a workspace catalog, default single-workspace initialization, cross-workspace isolation tests, and a mandatory `workspace_id` contract for later PostgreSQL tables.

The [content repository foundation](2026-08-26-content-foundation.md) and invitation-only membership may proceed in parallel after this boundary is implemented. Content writes, projection, search, MCP, and visitor Q&A must build on the workspace boundary rather than first implementing a single-workspace path and adding tenant fields later.

This implementation does not include an additional-workspace UI, open registration, billing, tenant migration, cross-workspace search, shared documents, or workspace deletion.

## Alternatives considered

**Remain single-tenant and change later.** The first implementation would be smaller, but tenant scope crosses content paths, database constraints, caches, events, and authorization. Those features do not exist yet, so the boundary is cheapest to establish now.

**Store every workspace in subdirectories of one Git repository.** This reduces repository count, but Git history, backup, recovery, and deletion would no longer be isolated per tenant. A repository per workspace aligns the Git boundary with the security boundary.

**Filter workspace access only at the HTTP layer.** This does not constrain background jobs, MCP, caches, or internal calls. Workspace scope belongs in module contracts and persistence keys, not only in route parameters.

**Add PostgreSQL Row-Level Security immediately.** RLS can provide another defense layer, but connection-pool transaction context, migration roles, and table-owner bypass need a separate design. The first implementation uses explicit scope, database constraints, and isolation tests. A later security review may propose RLS.

## Acceptance

- Both requirements documents describe multi-workspace isolation, the default single-workspace topology, and disabled self-service workspace creation. Multi-tenancy is removed from the v1 non-goals while open registration remains explicitly excluded.
- The default configuration creates and exposes one workspace and provides no open-registration or self-service additional-workspace entry point.
- Two workspaces may contain the same document UUID, relative path, and blob hash without sharing reads, search results, cache entries, errors, or audit data.
- Content path resolution accepts only a canonical `WorkspaceId`, keeps the result below `<data-dir>/workspaces/`, and rejects traversal and alias collisions.
- Module-boundary tests prove that every workspace-owned operation requires `WorkspaceId`; no hidden production path relies on a default tenant.
- Background-task and projection-checkpoint tests prove that two workspaces advance, fail, and rebuild independently.
- Spring Modulith verification, `./gradlew test`, `./gradlew integrationTest`, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

Explicit scope on all workspace-owned data adds parameters to keys, queries, and tests. This is the intended cost of isolation. A single-tenant shortcut would give the default and multi-workspace modes different security semantics.

A repository per workspace increases repository and background-worker counts. Workspaces have no cross-repository transaction dependency, so they can be sharded and processed in parallel while writes remain serialized within each repository. Distributed locks and message queues remain unnecessary until measured scale exceeds one process.
