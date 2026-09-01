# Consumer Multitenancy, Stateless Application, and Remote Repository Authority

Date: 2026-09-01
Status: Proposed

## Problem

Poketto's executable baseline was deliberately narrowed to one self-hosted instance with one default workspace, application-owned local repositories, local blobs, and process-local coordination. That topology made the first implementation small, but it also made the request-serving application a stateful owner of every workspace it could serve. A workspace becomes tied to one data directory and one process, so adding replicas requires shared filesystems, sticky routing, or a separate writer.

The product direction is broader: an individual should be able to register, receive a private personal workspace, and later share or own additional workspaces without changing the security model. The hosted service must add or replace application replicas without moving authoritative files into each pod. Git must remain the content authority, while relational product state remains in PostgreSQL.

These requirements are one architectural decision. Consumer registration creates the tenant fleet; stateless application replicas require tenant state outside the application process; and remote repository authority makes Git content available to any authorized replica without a shared application filesystem.

## Proposal

### Account and workspace model

- An `account` is a human identity. A `workspace` is the tenant, authorization, repository, blob, quota, audit, backup, and data-destruction boundary. An account is never used as a storage scope.
- Consumer registration creates an account, a personal workspace, and an `OWNER` membership through one idempotent provisioning operation. The workspace is not active until its relational row, repository, and blob namespace are all ready.
- One account may own or join several workspaces. Invitations grant membership in an existing workspace; they do not create an account-wide role or expose other workspaces.
- Workspace names, public slugs, and custom domains are presentation and routing values. The immutable `WorkspaceId` remains the internal scope at every module and persistence boundary.
- Budgets, API keys, agent capabilities, audit records, background jobs, cache keys, and usage accounting carry `WorkspaceId`. Billing may aggregate several workspaces under one account or subscription later, but it cannot weaken tenant isolation.
- Registration, email verification, abuse prevention, account recovery, billing, workspace deletion, and ownership transfer require focused product and security contracts. This proposal fixes their architecture boundary without selecting every user-facing flow.

### Stateless request-serving application

- Spring API instances and frontend instances keep no authoritative repository, blob, session, job, or rate-limit state on local disk, and no correctness state exists only in process memory. Any healthy instance can serve any authorized workspace.
- Local disk is limited to bounded caches and per-request temporary data. A process may lose all of it at any time without losing an acknowledged write, registration, membership change, audit event, or budget reservation.
- PostgreSQL remains authoritative for relational application state, including accounts, workspaces, memberships, credentials, invitations, shared session state when used, audits, budgets, provisioning state, and durable job leases. It stores no document body or content-search projection under the repository-native retrieval proposal.
- Workspace blobs use a `BlobStore` port backed by storage independent of application instances. External names and authorization remain workspace-scoped even if a backend performs physical deduplication.
- Correctness cannot depend on an in-JVM lock, cache, scheduler, or token bucket. PostgreSQL constraints and transactions, repository-ref compare-and-swap, and durable leases provide shared coordination. Local mechanisms may reduce contention but are never the only guard.
- Liveness, readiness, shutdown, and retry behavior assume replicas can overlap. A rolling replacement does not require transferring a worktree or draining all work for a workspace to one successor.

### Remote repository authority

Poketto owns a provider-neutral `RepositoryAuthority` contract. "Remote" means authoritative Git storage is outside the request-serving application process and survives its replacement. The authority may be a separate service on the same operator-controlled machine, a dedicated cluster service, or a managed Git backend; business modules do not depend on GitHub-, GitLab-, or filesystem-specific APIs.

The contract provides at least these semantics:

- provision one isolated repository for a `WorkspaceId` and report idempotent readiness;
- resolve the authoritative `main` ref to an immutable commit;
- read or export a verified snapshot at an authorized commit, including bounded reachable history when requested;
- accept complete candidate objects and atomically advance `main` only when its current value equals an expected commit;
- distinguish a ref conflict, authorization failure, unavailable backend, invalid or incomplete object graph, and an ambiguous network outcome;
- expose backup and integrity operations without revealing provider credentials or another workspace's repository identity.

An application write resolves base commit `A`, reads and validates the target document from `A`, builds candidate commit `B` in temporary storage, and asks the authority to compare-and-swap `main` from `A` to `B`. The remote ref update is the acknowledgement point. If another writer advances `main`, the application resolves the new head and retries only when the target document's opaque revision and every operation precondition remain unchanged. A conflicting target returns the live revision instead of overwriting it. An ambiguous response is reconciled by reading the authoritative ref before the caller is told to retry.

An in-JVM workspace lock may reduce rejected candidates inside one replica, but it does not participate in correctness. No distributed filesystem lock is required. Hosted clients receive no credential that can update authoritative `main` directly; a future Git import or human-edit entrance must validate content and use the same ref precondition rather than creating a second writer.

### Reads and sandbox execution

- Public, administrative, and MCP reads first resolve one authoritative commit. A request stays pinned to that commit, and caches are keyed by workspace plus commit.
- Structured reads obtain opaque document revisions from the same committed snapshot. Agents never derive revision tokens from repository bytes.
- `repo_exec` receives an immutable snapshot or Git bundle produced for the authorized workspace and commit. The worker receives no repository-authority credential, application secret, or path to live storage.
- A sandbox session may materialize a writable private clone and reuse it across commands pinned to the same commit. Session files are disposable and never become a content write.
- The single-operator self-hosted profile may use a dedicated low-privilege Sandbox Runtime executor. A hosted multi-tenant service treats tenant-controlled commands as hostile and uses separately schedulable workers with an OCI sandbox using a gVisor-equivalent boundary or stronger isolation. Both profiles implement the same `SandboxExecutor` contract and fail closed.

### Provisioning and failure ownership

Workspace provisioning is a durable state machine because PostgreSQL, repository authority, and blob storage cannot share one transaction. Every step is idempotent and keyed by `WorkspaceId`. A retry resumes from recorded state; duplicate repository or namespace creation is success only when it refers to the same workspace. A workspace is routable only after all required stores report ready.

Failed provisioning remains invisible to other consumers and cannot leak provider identifiers. Cleanup is bounded to resources created for that `WorkspaceId`. Destructive workspace deletion remains unavailable until a separate decision defines retention, recovery, billing effects, and cancellation across all authoritative stores.

Repository authority, PostgreSQL, and blob storage have independent health and backup status. The application reports which dependency prevents a read or write without returning credentials, internal repository names, or another tenant's existence. Backup is not replaced by provider durability or Git replication.

### Deployment profiles

The hosted profile runs request-serving application replicas without persistent application volumes. It may use long-lived containers or serverless compute because replacement and scale-to-zero do not move authoritative workspace state; connection duration and cold-start targets remain deployment concerns. Repository authority, PostgreSQL, blob storage, and sandbox workers scale and fail independently behind their owned contracts.

Self-hosting remains supported. A self-hosted deployment may co-locate components and use one Compose project, but authoritative repositories and blobs belong to their storage services rather than the Spring container's writable filesystem. A local filesystem `RepositoryAuthority` adapter may exist for migration, tests, and a development profile; it does not satisfy hosted stateless acceptance and must not leak path semantics into business modules.

## Decision relationships

This proposal retains the implemented [workspace and tenant boundary](../implemented/2026-08-27-workspace-tenancy.md): `WorkspaceId`, one repository per workspace, explicit scope propagation, and cross-workspace non-disclosure remain foundational. It reverses that note's default deployment and provisioning limits without rewriting the implemented record.

The implemented [content repository foundation](../implemented/2026-08-26-content-foundation.md) and [document write operations](../implemented/2026-08-29-document-write-operations.md) remain the executable local adapters until remote authority is implemented. Their path ownership, in-JVM locking, and local-commit acknowledgement are not the hosted target.

The [repository-native retrieval and sandboxed execution proposal](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) depends on immutable snapshots from `RepositoryAuthority`. The [invitation-only membership proposal](2026-08-27-invitation-only-membership.md) continues to own joining an existing workspace, while consumer registration owns creation of a personal workspace.

The rejected [local-authority replication proposal](../rejected/2026-08-27-git-durability-modes.md) remains a record of the offline-first alternative. Its candidate-commit, ref-precondition, ambiguous-outcome, and failure-classification analysis informs the remote compare-and-swap adapter, but local `main` is not an authority or acknowledgement mode in the hosted architecture.

## Implementation sequence

1. Introduce `RepositoryAuthority` and `BlobStore` ports. Wrap the current local worktree and blob directory as development adapters without changing observable behavior.
2. Implement a remote repository adapter, candidate-object transfer, ref compare-and-swap, conflict reconciliation, provisioning, integrity checks, and backup export. Move structured reads and writes to the port.
3. Remove application-owned authoritative repository and blob paths from the hosted profile. Move correctness-critical sessions, budgets, rate limits, and background leases to shared stores, then prove two application replicas can serve the same workspaces without shared application disk.
4. Supply repository snapshots to isolated execution workers and update repository-native retrieval to reuse commit-pinned session workspaces without access to live authority.
5. Add consumer registration and personal-workspace provisioning after tenant isolation, quotas, abuse controls, recovery, and deletion prerequisites have their own accepted contracts.

Each step leaves one authority for every datum. Compatibility shims are unnecessary during development; transitional local adapters remain only while an executable path still depends on them.

## Alternatives considered

**Keep local repositories authoritative and mirror them asynchronously.** This preserves offline writes, but it binds a workspace to the application node and makes remote state secondary. Shared volumes or sticky writers would still be required, so the application would not become stateless.

**Mount one shared filesystem into every application replica.** This moves the disk without removing filesystem coordination, Git worktree locking, correlated failure, or path-level tenant risk. An authority contract exposes the operations Poketto needs and permits independent storage implementations.

**Use PostgreSQL as the document authority.** Transactions and horizontal application access become simpler, but Git would become an export or projection rather than the source of truth. That contradicts repository-native editing, history, and agent inspection.

**Make each account the tenant.** This works only while every person owns exactly one knowledge space and never collaborates. Separating identity from workspace preserves personal signup without baking that temporary product rule into every key and authorization check.

**Give each application replica Git-provider credentials and let it push directly.** Provider-specific push behavior does not own workspace provisioning, content validation, authorization, ambiguous outcomes, integrity, or backup. A Poketto contract centralizes those semantics while adapters use provider primitives internally.

**Run all components in one stateful application pod.** A persistent volume can keep a small installation simple, but replacement, scaling, and tenant placement remain coupled to that pod. Co-location remains a self-host deployment choice, not the hosted application boundary.

## Acceptance

- Two request-serving replicas with no shared application filesystem can concurrently read and write two workspaces. Either replica may serve the next request, and terminating one loses no acknowledged state.
- Registration creates exactly one account, personal workspace, owner membership, repository, and blob namespace under duplicate delivery and injected failures. No route exposes the workspace before provisioning completes.
- Repository writes acknowledge only an authoritative ref advance. Tests cover concurrent unrelated and same-document changes, stale document revisions, incomplete candidate objects, backend timeouts, and crash or disconnect after the authority may have accepted a ref update.
- A ref conflict retries only while document revision and operation preconditions remain valid. No path force-pushes, silently overwrites, or relies on an in-JVM or filesystem lock for correctness.
- Public and private reads remain pinned to one verified commit. Cache, error, log, metric, backup, and worker behavior cannot reveal another workspace or accept a caller-selected storage identifier.
- Deleting every application pod and its local disk preserves accounts, workspaces, repository history, blobs, audits, budgets, provisioning progress, and acknowledged writes.
- A hosted `repo_exec` worker receives only the authorized immutable snapshot and bounded job metadata. It cannot reach repository authority, application credentials, PostgreSQL, blob storage, another tenant, the container runtime, or unrestricted network access.
- A self-hosted deployment exercises the same application contracts. Any local adapter is clearly marked as non-hosted, and business modules contain no filesystem path or provider-specific dependency.
- Backup and restore can rebuild PostgreSQL, repository authority, and blob storage into an empty deployment and prove workspace-to-repository ownership before traffic resumes.
- Requirements, README counterparts, deployment documentation, and tests distinguish the executable local baseline from the proposed hosted target until each implementation stage ships. Repository checks and the relevant automated tests pass.

## Risks

A repository per workspace creates a large fleet of small Git repositories. Provisioning, object maintenance, integrity scans, backup, and inactive-tenant retention need bounded scheduling and fleet-level measurements.

Remote reads and writes add network latency and ambiguous failures. Commit-keyed caches reduce repeated reads; ref reconciliation and idempotent provisioning are mandatory because retries alone cannot determine whether a mutation happened.

Git serializes one workspace's `main` ref even when two writes touch unrelated documents. Bounded optimistic retries are sufficient only while contention remains modest. Measured hot workspaces may later justify queued integration or finer-grained authoring branches without changing the authoritative ref contract.

Multi-tenant execution has a stronger threat model than a personal host. Worker isolation, kernel patching, resource limits, credential absence, audit metadata, and abuse controls all become production security responsibilities.

Supporting hosted and self-hosted deployment can create two architectures if adapters leak into domain code. Shared ports, contract tests, and one acknowledgement model are the guard against that split.
