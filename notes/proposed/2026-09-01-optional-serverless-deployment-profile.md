# Optional Serverless Deployment Profile

Date: 2026-09-01
Status: Proposed

## Problem

Poketto's primary production topology is one operator-owned cloud server. It runs the application beside a local repository cache, authoritative local filesystem ManagedBlobStore, disposable repository-image cache, and local Sandbox Runtime executor while using remote Git as repository authority. This remains a supported product profile, not a temporary development adapter.

An operator may later want the request-serving application to run on a serverless or replicated platform without persistent application volumes. Remote Git removes repository authority from the request host, but replaceable instances still cannot own authoritative assets, sessions, leases, budgets, or the Linux namespaces required by SRT.

The serverless request environment, object storage, shared relational service, remote executor compute, credentials, and network boundaries are external resources. Poketto will implement and accept them as one coordinated optional profile only when a production-like target is available.

## Proposal

### Profile boundary

- Both profiles use [remote Git repository authority](2026-09-01-remote-repository-authority.md). There is no local Git authority profile.
- The primary single-server profile uses a local filesystem [ManagedBlobStore and disposable repository-image cache](2026-09-01-repository-asset-blob-store.md) plus a local SRT executor service under a dedicated low-privilege identity.
- The optional serverless profile runs Spring and the frontend without required persistent application volumes. It uses OSS-compatible authoritative managed storage and derived repository-image caching, shared PostgreSQL, and remote SRT workers outside replaceable request instances.
- Both profiles use the same application artifacts, domain modules, workspace model, authorization rules, content format, publishing policy, write preconditions, repository acknowledgement, and image-ownership semantics. Startup configuration selects explicit adapters; missing or invalid external configuration fails closed and never falls back to container disk, local Git authority, or direct command execution.
- Business modules depend on Poketto-owned ports and contain no provider API, repository URL, bucket name, filesystem path, transport credential, or deployment-specific retry rule.

### Repository content and assets

Request instances may keep bounded commit-keyed repository caches, but deleting an instance and its disk loses no acknowledged Markdown, publishing policy, repository history, or managed image because those authorities remain in remote Git, shared PostgreSQL, and authoritative OSS rather than ephemeral disk.

The serverless OSS-compatible [ManagedBlobStore](2026-09-01-repository-asset-blob-store.md) is the byte authority only for images uploaded through Poketto. Repository images remain exact remote Git files and use a separately identifiable derived OSS cache on demand. Source Markdown is never rewritten to a provider URL. Losing authoritative managed objects requires encrypted backup restoration; losing the repository-image cache causes rematerialization rather than data recovery.

### Sandbox execution

Both profiles use Anthropic Sandbox Runtime behind the `SandboxExecutor` contract from [repository-native retrieval and sandboxed execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). The single-server profile calls a local executor service. The serverless profile sends authenticated bounded jobs to a remote SRT worker running on ordinary Linux compute that supports the required sandbox primitives.

The remote worker receives only an authorized immutable repository snapshot, executable job metadata, and resource limits. It receives no repository-authority credential, object-store credential, PostgreSQL access, application secret, or caller-selected host path. Session files are disposable and never become content writes. `get_asset` remains an application-mediated multimodal read; SRT does not gain authority access or unrestricted network access to obtain images.

If the selected environment cannot preserve SRT's declared boundary, remote execution is unavailable and implementation stops for a new runtime decision. It never silently substitutes direct execution or another sandbox.

### Shared correctness state

Request instances keep no authoritative repository, required managed object, session, job, budget, rate-limit, provisioning, or lease state only on local disk or in process memory. PostgreSQL transactions and constraints, remote repository-ref compare-and-swap, and durable leases provide shared correctness. Local caches and locks may reduce work but never decide correctness.

Consumer registration and remote repository creation remain owned by [consumer accounts and personal workspaces](2026-09-01-consumer-accounts-and-personal-workspaces.md). Serverless does not invent a second repository-provisioning path. Its workspace setup adds provider-neutral managed-object scope, derived-cache scope, and remote executor routing with idempotent steps keyed by `WorkspaceId`.

### Deployment behavior

Any healthy request instance may serve any authorized workspace. Replacing or scaling instances transfers no worktree or authoritative file. Connection duration and cold-start targets remain deployment concerns, while the selected Git service, object store, database, and SRT worker supply their own availability model.

The profile is hybrid rather than claiming that every component is serverless. The request layer may scale to zero; SRT still runs on compatible Linux compute, and all external services retain their own lifecycle and cost.

## Implementation scope and dependencies

This proposal intentionally groups the authoritative OSS adapter, derived repository-image cache, remote SRT, shared-state leases, and serverless deployment configuration because a partial set does not produce a usable profile. Work that does not need those external resources lands first through remote repository authority, repository-native publishing, the local filesystem ManagedBlobStore, and local SRT.

The start gate requires a non-production serverless request environment, an isolated OSS-compatible namespace, shared PostgreSQL, ordinary Linux compute for the SRT worker, scoped credentials, and production-like network paths connecting them. These resources must be disposable or isolated from production data. Missing any one leaves this proposal intact instead of producing speculative adapters.

The implementation adds external adapters and deployment configuration, validates fail-closed startup, moves remaining correctness state to shared owners, and verifies both profiles from the same built artifacts. It updates requirements, README counterparts, deployment documentation, backup behavior, and tests to describe what shipped.

It does not implement consumer product flows, billing, a new sandbox runtime, a second repository authority, multi-region failover, CDN behavior, or provider-specific behavior in business modules.

## Alternatives considered

**Make serverless the default.** This would force object storage, shared database, remote compute, and credentials on the primary single-server use case. The single-server profile already has remote content durability while keeping its operational footprint small.

**Retain local Git authority on the single server.** That would make deployment selection change repository acknowledgement and recovery semantics. A disposable repository cache supplies local performance while remote Git remains the only repository authority.

**Run the local ManagedBlobStore on ephemeral serverless disk.** A replaced instance would lose authoritative managed images and concurrent instances could not resolve immutable managed revisions. OSS supplies the shared durable store required by this profile.

**Use OSS only as a cache of repository images.** This would preserve repository delivery but exclude uploads and generated images that have no Git path. A distinct authoritative managed namespace keeps the structured upload path independent from repository layout.

**Mount one shared filesystem into every request instance.** This can host caches and sessions, but it restores filesystem coordination and a stateful platform dependency instead of exercising replaceable authority and lease contracts.

**Replace SRT only for serverless.** Two execution runtimes would create different security and resource semantics. A different hosted sandbox requires an explicit decision rather than entering as an adapter detail.

## Acceptance

- The same application artifacts start in the production single-server profile and optional serverless profile through configuration only. Business modules contain no profile branch or provider coordinate.
- Both profiles resolve and write the same remote Git authority semantics. Neither starts with local Git authority or falls back to it when remote configuration is unavailable.
- The single-server profile stores managed images in the authoritative local filesystem ManagedBlobStore, materializes repository images into a disposable cache, and uses local SRT. It remains fully supported after serverless ships.
- The serverless profile stores managed images in a real isolated authoritative OSS-compatible namespace and repository images in a separately identifiable derived cache. Deleting request-instance disks changes neither authority; deleting a managed object exercises restore, while deleting a cached repository image exercises rematerialization.
- Replacing or concurrently running request instances preserves session, authorization, provisioning, lease, budget, and rate-limit correctness through shared owners.
- The remote SRT worker enforces the same command, filesystem, identity, network, timeout, output, process, and resource limits as the local executor. It receives no authority, object-store, or database credential.
- Missing Git, OSS, PostgreSQL, SRT, credential, or lease configuration fails startup or the affected capability closed. No fallback writes to ephemeral disk or executes commands in the request process.
- Production-like evidence covers cold and warm repository reads, cold and warm image delivery, a remote SRT twenty-command reused session, a compare-and-swap write, instance replacement, concurrency, and external-service failure without identifying private infrastructure.
- Requirements, README counterparts, deployment and backup documentation, focused integration tests, `./gradlew check`, and `git diff --check` pass before the profile is described as implemented.

## Risks

The serverless profile has more network boundaries and independent failure modes than one server. Explicit timeouts, bounded retries, idempotent provisioning, ambiguous-write reconciliation, and fail-closed startup are required before convenience tuning.

Remote SRT still needs ordinary Linux compute, so the request layer can scale independently but the entire system is not function-only. This is an honest hybrid boundary rather than a hidden host dependency.

The managed object namespace is authoritative production data. Capacity, egress, lifecycle, encrypted backup, restore, and service availability must be measured against the selected provider. Repository-image cache loss has different semantics and must never be reported as managed-data loss or included in recovery requirements.
