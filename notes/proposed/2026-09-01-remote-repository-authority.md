# Remote Repository Authority

Date: 2026-09-01
Status: Proposed

## Problem

The implemented baseline keeps each workspace's authoritative Git repository on the application server. That is simple for early development, but it ties acknowledged content to one server's filesystem, makes horizontal request replicas unsafe, and gives the single-server and optional serverless deployments different authority semantics.

Poketto's target product is consumer-oriented and repository-native. A workspace must therefore remain attached to the same durable repository when the application process, local cache, or whole request host is replaced. The simplest supported deployment should not require local Git authority as a second product mode.

## Proposal

### One authority model

- Every production workspace has one private remote Git repository whose `main` ref is the sole repository authority. Markdown, publishing policy, repository metadata, and optional asset source files are acknowledged as repository writes only when the remote ref contains them.
- The primary single-server profile and the optional serverless profile use the same `RepositoryAuthority` contract and remote acknowledgement semantics. Neither profile supports a local-authority configuration or silently falls back to local disk.
- A local bare repository, object database, or worktree is a disposable cache or candidate workspace. Deleting it and restarting must not lose acknowledged content or change the resolved authoritative commit.
- Direct owner pushes are break-glass repository changes. Poketto observes the new remote `main`, validates it, and fails closed for invalid public content rather than rewriting owner commits. A changed Git image is an asset-source change; [asset synchronization](2026-09-01-repository-asset-blob-store.md) decides whether it can advance the managed BlobStore version.

### Authority contract

`RepositoryAuthority` resolves the current `main`, materializes a commit-pinned readable snapshot, reads paths and opaque blob revisions, and advances `main` with an exact expected-ref compare-and-swap. Callers never calculate a revision token from file contents.

A machine write builds and validates a candidate commit away from authority, pushes the required objects, and advances the remote ref only if it still equals the resolved base. A ref mismatch is a conflict. A timeout or disconnected response is an ambiguous result: the adapter re-reads the remote ref and reports committed only when it equals the candidate commit. It never retries a blind ref update.

The domain contract contains no provider URL, repository identifier, token type, or provider retry rule. A Git transport adapter owns object and ref exchange. Automatic consumer-repository creation is a separate provisioning concern and does not enter this authority port.

### Binding and provisioning

The first remote-authority milestone may bind a pre-provisioned private repository through operator secrets. No repository address or credential is committed to Poketto, copied into diagnostics, exposed through APIs, or passed to SRT. Tests and committed examples use disposable local test remotes or unmistakably fictitious identifiers.

The authority contract accepts a workspace-scoped binding created by consumer provisioning, but it does not create that repository. Before open registration ships, [consumer accounts and personal workspaces](2026-09-01-consumer-accounts-and-personal-workspaces.md) must add a provider adapter that creates one private repository per personal workspace and records the binding. Repository creation is product provisioning, not a serverless concern: a consumer workspace uses remote authority even on the single-server profile.

Credentials are operator-managed secrets with the minimum repository scope. Consumer credentials, model prompts, sandbox jobs, browser responses, logs, and audit payloads never receive the authority credential.

### Caching and transfer

The application keeps a bounded commit-keyed repository cache. The first access fetches the objects needed for the resolved commit; later accesses fetch only missing objects and refs. Unchanged repository binaries and history are not transferred again merely because another request or session reads them.

Cache entries are workspace-scoped and never selected by caller-supplied repository coordinates. Cache deletion, process replacement, and an empty-disk restart are correctness-neutral. SRT receives a full-copy execution workspace derived from an authorized snapshot under the separate sandbox contract; it never mounts the cache object database or authority repository directly.

## Implementation scope and dependencies

The first implementation replaces the production local authority with the provider-neutral `RepositoryAuthority` port, a remote Git adapter, secret-backed binding for an operator-provisioned private repository, bounded local caching, exact-ref writes, ambiguous-result reconciliation, and failure-injection and isolation tests. It updates existing repository reads and writes to use the port and removes local authority as a supported production configuration.

The implementation updates the requirements counterparts, README counterparts, deployment configuration, write acknowledgement, backup, retrieval, and publishing notes to describe what shipped. It does not add a repository browser, expose Git credentials to users or agents, choose an object-storage provider, or implement the optional serverless runtime.

## Alternatives considered

**Keep local authority on one server and add a remote mirror.** This preserves the simplest current write path, but a successful local commit can still be lost before replication and request replicas cannot agree on one authoritative ref. It also creates two acknowledgement modes for the same product.

**Support both local and remote authority profiles.** This appears flexible but doubles recovery, locking, acknowledgement, and deployment semantics. A disposable local cache gives the single-server profile the performance benefit without making local disk authoritative.

**Make remote authority part of serverless only.** Remote authority already improves replacement, backup independence, and consumer provisioning on one server. Deferring it would make serverless a migration between authority models instead of a configuration change.

**Let SRT clone or push the remote repository directly.** The sandbox would need authority credentials and network access, collapsing the read and write boundary. The application materializes snapshots and performs validated compare-and-swap writes instead.

## Acceptance

- Both production profiles require remote Git authority. Configuration with no remote authority fails closed and never creates a local authoritative repository.
- After an acknowledged repository write, deleting every application-side repository cache and restarting resolves the same remote `main` and exact repository tree. Asset BlobStore recovery follows its separate authority and backup contract.
- Concurrent writers advancing the same base produce one success and one conflict. Lost-response tests reconcile the remote ref without duplicate or blind writes.
- A cold fetch and subsequent warm fetch of a representative nested Markdown-and-image repository record network bytes, local bytes, latency, memory, and cache size. Warm reads do not refetch unchanged image objects.
- SRT, browser responses, diagnostics, logs, committed examples, and tests expose no remote repository address, provider identity, or credential.
- Existing repository read and write tests exercise the port, `./gradlew repoCheck` passes, and `git diff --check` passes.

## Risks

Remote availability now participates in authoritative reads and writes. Commit-keyed caches keep already fetched reads available where policy permits, while writes fail clearly when the current remote ref cannot be verified.

Provider ref updates and failure responses vary. The adapter must prove exact expected-ref behavior and ambiguous-result reconciliation against the selected provider before it can own production writes.

Repositories with large binary history make cold starts expensive. Incremental object transfer and a persistent bounded cache protect the normal single-server path; measurements decide whether later filtered fetch or lifecycle work is justified.
