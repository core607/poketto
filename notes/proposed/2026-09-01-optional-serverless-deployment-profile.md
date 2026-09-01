# Optional Serverless Deployment Profile

Date: 2026-09-01
Status: Proposed

## Problem

Poketto's primary production topology is one operator-owned cloud server. It may run the application, PostgreSQL, local workspace repositories, local blobs, and a dedicated Sandbox Runtime executor together. That topology is a supported product profile, not a temporary development adapter.

An operator may later want the request-serving application to run on a serverless or replicated platform without persistent application volumes. Ephemeral instances cannot own repositories or blobs, and they cannot be assumed to support the Linux namespaces required by Sandbox Runtime. Supporting that profile must not create a second business architecture or require code changes beyond selecting infrastructure adapters.

The remote repository service, object storage, remote executor compute, credentials, and real serverless environment are external resources. Poketto will implement and accept this profile as one coordinated capability only when those resources are selected and available; speculative adapters do not land independently.

## Proposal

### Profile boundary

- The single-server profile remains the default and keeps production-supported local repository, blob, and Sandbox Runtime implementations.
- The optional serverless profile runs Spring and the frontend without authoritative local state. PostgreSQL, repository authority, blob storage, and sandbox execution live outside replaceable request instances.
- Both profiles use the same application artifacts, domain modules, workspace model, authorization rules, content format, blob identity, write preconditions, and acknowledgement semantics. Startup configuration selects explicit adapters; missing or invalid external configuration fails closed and never falls back to a local path.
- Business modules depend on Poketto-owned ports and contain no provider API, bucket name, remote URL, filesystem path, or transport-specific retry rule.

### Repository authority

`RepositoryAuthority` has production local and remote adapters. The local adapter wraps the primary profile's per-workspace repositories. The remote adapter owns provider interaction without exposing provider repository identifiers to callers.

The shared contract can provision one repository for a `WorkspaceId`, resolve authoritative `main` to an immutable commit, export a verified snapshot with bounded reachable history, accept a complete candidate object graph, and atomically advance `main` only when its current value equals an expected commit. It distinguishes ref conflict, authorization failure, unavailable backend, invalid objects, and an ambiguous network outcome.

An application write resolves commit `A`, validates its document preconditions, builds candidate `B`, and asks the authority to compare-and-swap `main` from `A` to `B`. The authority update is the acknowledgement point in both profiles. A conflict rebuilds from the new head only while the target revision and every operation precondition remain unchanged. An ambiguous response is reconciled against the authoritative ref and object graph before a caller is told to retry.

The local profile may retain its operator-only worktree as a break-glass entrance. The remote profile exposes no provider credential or direct push entrance to consumers, application clients, or sandbox workers.

The optional remote mirror from [Git replication and write acknowledgement](2026-08-27-git-durability-modes.md) remains an output of the local authority. Configuring mirrored acknowledgement does not turn that mirror into remote repository authority or make it a second write entrance.

### Blob storage

`BlobStore` retains the workspace-scoped, immutable SHA-256 contract from [local blob storage](2026-09-01-local-content-addressed-blob-storage.md). The single-server profile uses its local filesystem adapter. The serverless profile adds one object-storage adapter backed by the selected real service. Callers never provide a bucket, object key, or provider URL, and physical deduplication cannot alter cross-workspace authorization or error behavior.

### Sandbox execution

Both profiles use Anthropic Sandbox Runtime behind the existing `SandboxExecutor` contract from [repository-native retrieval and sandboxed execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). The single-server profile calls a local executor service under a dedicated low-privilege identity. The serverless profile sends authenticated bounded jobs to a remote SRT worker running on ordinary Linux compute that supports the required sandbox primitives.

The remote worker receives only an authorized immutable repository snapshot, executable job metadata, and resource limits. It receives no repository-authority credential, BlobStore credential, PostgreSQL access, application secret, or caller-selected host path. Session files are disposable and never become content writes. If the selected environment cannot preserve SRT's declared boundary, remote execution is unavailable and implementation stops for a new runtime decision; it never silently substitutes direct execution or another sandbox.

### Shared correctness state

Request instances keep no authoritative repository, blob, session, job, budget, rate-limit, provisioning, or lease state on local disk or only in process memory. PostgreSQL transactions and constraints, repository-ref compare-and-swap, and durable leases provide shared correctness. Local caches and locks may reduce work but never decide correctness.

A workspace is routable only after PostgreSQL, repository authority, and blob storage agree that its recorded resources are ready. Provisioning steps are idempotent and keyed by `WorkspaceId`; consumer registration remains owned by the separate [consumer accounts proposal](2026-09-01-consumer-accounts-and-personal-workspaces.md).

### Deployment behavior

Any healthy request instance may serve any authorized workspace. Replacing or scaling request instances transfers no worktree or authoritative file. Connection duration and cold-start targets remain deployment concerns, while repositories, blobs, PostgreSQL, and SRT workers scale and fail behind their owned contracts.

The profile is hybrid rather than claiming that every component is serverless. The request layer may scale to zero; the selected repository service, object store, database, and SRT worker supply their own availability model.

## Implementation scope and dependencies

This proposal intentionally groups the remote repository, object-storage, remote SRT, shared-state, and deployment adapters because a partial set does not produce a usable serverless profile. Implementation begins only when the operator has selected the real external services and a production-like serverless target.

The start gate requires a non-production remote Git authority, object-store namespace, ordinary Linux environment for the SRT worker, serverless request environment, and scoped credentials and network paths connecting them. These resources must be disposable or isolated from production data. Missing any one of them leaves this proposal intact rather than producing a partial adapter task.

The implementation introduces the ports where they do not already exist, preserves the production local adapters, adds the selected remote adapters, refactors callers without changing content or authorization semantics, supplies configuration validation, and verifies both profiles from the same built artifacts. It updates requirements, README counterparts, deployment documentation, backup behavior, and tests to describe what actually shipped.

It does not implement consumer registration, billing, object-store selection without a real target, a different sandbox runtime, multi-region failover, or provider-specific behavior in business modules.

## Alternatives considered

**Make the serverless profile the default.** This would force external services and credentials on the primary single-server use case and make the simplest supported deployment depend on optional infrastructure.

**Implement each remote adapter before a serverless deployment exists.** Contract tests can exercise fakes, but only a real combined topology proves identity mapping, ambiguous failures, isolation, cold starts, and credentials. Early adapters would create maintenance surfaces without a usable profile.

**Mount one shared filesystem into every request instance.** This can move repositories and blobs outside an individual container, but it preserves filesystem coordination, path-level tenant risk, and a stateful platform dependency instead of exercising replaceable authority contracts.

**Use PostgreSQL as document or blob authority.** Horizontal application access becomes simpler, but Git stops being the content source of truth and large immutable bytes enlarge relational backup and replication.

**Replace SRT only for serverless.** Two execution runtimes would create different security and resource semantics. A different hosted sandbox requires an explicit decision rather than entering as an adapter detail.

## Acceptance

- The same application artifacts start in the production single-server profile and the optional serverless profile through configuration only. Business modules contain no profile branch, provider API, bucket name, remote repository identifier, or adapter path.
- The single-server profile remains fully supported with local repositories, local blobs, and a local SRT executor; implementing serverless does not turn them into development-only adapters.
- Two request-serving replicas with no shared application filesystem concurrently read and write two workspaces. Either replica may serve the next request, and deleting either replica and its local disk loses no acknowledged state.
- Local and remote repository adapters pass the same provisioning, snapshot, concurrent-write, stale-revision, incomplete-object, ambiguous-response, restart, and integrity contract tests.
- Local and object-storage blob adapters pass the same immutable-byte, idempotency, bounds, workspace-isolation, restart, and backup-export contract tests.
- A remote SRT worker reuses one commit-pinned session workspace for a representative twenty-command agent session and cannot reach authority credentials, PostgreSQL, BlobStore, another workspace, the container runtime, or unrestricted network access.
- Configuration errors and dependency failure return distinct actionable unavailability. No path falls back from remote to local authority, from object storage to container disk, or from SRT to an unsandboxed process.
- Backup and restore rebuild PostgreSQL, repository authority, and blob storage into an empty deployment and prove workspace ownership before traffic resumes.
- A production-like run records cold start, request latency, repository and blob transfer, SRT session latency, and steady-state and peak resource use for every deployed component.
- Requirements, README counterparts, deployment documentation, relevant automated tests, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

The optional profile is a coordinated infrastructure change and will remain proposed until real services are available. That is deliberate: completing only its abstractions would not prove configuration-only portability.

Remote reads and writes add latency and ambiguous failures. Commit-keyed caches, idempotent provisioning, ref reconciliation, and explicit dependency health are required because retries alone cannot determine whether a mutation happened.

A remote SRT worker means the serverless profile still needs ordinary Linux compute for agent execution. Keeping it outside request instances preserves their portability but makes the deployment hybrid and adds one operational component.

SRT is a same-kernel boundary, while model-produced code must be treated as hostile in a consumer service. Configuration review alone cannot establish commercial multi-tenant isolation: the production-like topology must exercise cross-tenant filesystem, process, credential, and network attacks, and implementation stops for a new runtime decision if that evidence fails.

Supporting two production profiles can create divergent behavior. Shared ports, adapter contract tests, the same acknowledgement model, and a combined release exercise are the guard against that split.
