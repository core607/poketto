# Remote Repository Authority

Date: 2026-09-01
Status: Implemented

## Problem

The original executable baseline acknowledged writes to a repository on the application server. That tied durable content to one filesystem, prevented replaceable request hosts, and would have given the primary single-server and optional serverless profiles different correctness rules.

Poketto needs one repository authority model that survives cache deletion and process replacement without making agents, browser callers, or sandbox jobs repository credential holders.

## Decision

### One authority model

Every production workspace has one private remote Git repository. Its `main` ref is the sole authority for Markdown, repository metadata, publishing policy, and repository-managed files. A write is acknowledged only after remote `main` contains its candidate commit.

The primary single-server profile and the proposed serverless profile use the same internal `RepositoryAuthority` port. Neither supports local Git authority or falls back to local disk. `<data-dir>/workspaces/<workspace-id>/content` is a disposable workspace-scoped cache selected only from a validated `WorkspaceId`.

Materialized reads fetch and resolve remote `main`, reset the cache to that exact commit, remove local untracked and ignored files, and read the pinned tree. An empty pre-provisioned remote remains on an unborn `main` until the first write. Direct owner pushes are therefore visible on the next authoritative read or write; local cache edits are not content. Public requests do not perform this read: the [validated content snapshot](2026-09-04-validated-content-snapshot.md) performs it on a refresh schedule and serves the last commit that passed validation.

### Writes and failure semantics

`RepositoryAuthority` gives the content layer a commit-pinned read snapshot or a candidate workspace plus an exact-ref advancer. Provider coordinates and credentials do not cross that port.

A document write builds and validates a commit against the resolved base, then pushes its objects and advances remote `main` only when the remote ref still equals that base. A competing update raises `RepositoryConflictException`; the caller re-reads instead of overwriting.

When the push response is lost, the adapter reads remote `main` once. The write succeeds only when that ref equals the candidate commit. A different ref reports either a conflict or a definite failed update. If the ref cannot be read, `RepositoryWriteAmbiguousException` tells the caller not to retry blindly. No path performs an unconditional or blind retry.

`DocumentWriteResult.committed` now observes this remote acknowledgement boundary. Document revisions remain exact-blob SHA-256 values inside the content module and opaque tokens to entry points. `ContentRepositoryStore.scan` supplies the live revision together with each document, so update, delete, and publish callers never manufacture one.

### Binding and credentials

The first production binding attaches the database-created default workspace to one operator-provisioned HTTPS repository through these secret-backed settings:

- `poketto.repository.remote-uri`
- `poketto.repository.username`
- `poketto.repository.password`
- `poketto.repository.cache-max-workspaces`, default `32`
- `poketto.repository.timeout-seconds`, default `30`
- `poketto.repository.refresh-seconds`, default `30`, and `poketto.repository.stale-after-seconds`, default `3600`, owned by the [validated content snapshot](2026-09-04-validated-content-snapshot.md)

All three binding values are required when the workspace catalog is enabled. The address must be HTTPS and may not embed credentials, a query, or a fragment. Production configuration has no local-authority or file-transport switch; integration tests replace the binding source inside the test composition to use disposable bare remotes.

The adapter does not persist a Git remote in cache configuration and fetches over a direct transport connection that never writes JGit's `FETCH_HEAD`, which would otherwise record the source URI. Binding and configuration string forms are redacted, and transport failures surface sanitized messages without their underlying address or credential. Consumer repository creation and per-workspace binding persistence remain owned by [consumer accounts and personal workspaces](../proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md).

### Cache boundary

The cache retains fetched Git objects, so a warm read negotiates current `main` but receives no unchanged image or history objects. `poketto.repository.cache-max-workspaces` bounds workspace caches. On first access beyond the bound, the least-recently-used idle cache with no live image protection is removed. If every cache is active or protected, admitting another workspace reports capacity unavailable. Cache deletion, eviction, dirty local state, and an empty-disk restart do not change authoritative content.

Exact image reads and image metadata scans use a synchronous `ObjectReader` operation over the existing cache. Opening the repository holds the workspace mutex briefly; the callback reads explicit commit and blob ids after releasing it, without fetching, checking out files, or changing refs. The cache remains active until both the reader and repository have closed, including callback failure and cancellation.

Before issuing or reusing a Git image grant, the asset service verifies the exact source commit, path, blob id and size. The authority retains its active-reader pin through validation and installation of a workspace protection expiry. Each workspace records the maximum expiry; a shorter request cannot undo another grant's protection. Protection lasts at most five minutes from admission. Public grants also end by their snapshot expiry. This permits reconstruction after derived-image cache deletion even when remote `main` has changed or become unborn.

Source validation runs outside the global image-grant monitor and does not authorize publication. Grant admission rechecks time and rejects a clock rollback or an elapsed candidate expiry. A hit in the derived image cache cannot replace source validation. If source validation at grant admission fails, rendering omits that image authorization and preserves the article body. Failed grant-capacity admission may conservatively retain the verified source until the candidate expiry. Expired zero-user lifecycle entries are reclaimed on repository access. Protection is process-local, like the grant table, and holds no long-lived repository handles or per-token leases.

A remote ref update, including a force push or an unborn `main`, preserves already fetched objects. Transitioning to unborn `main` removes the local ref, index entries, and materialized files without deleting the object database. Existing immutable readers can finish while the new empty snapshot takes effect. Callers choose and authorize the exact commit before reading; public image grants are signed under a final snapshot installation lock after payload preparation. [Repository authoring foundations](2026-09-05-repository-authoring-foundations.md) owns these rendering and authorization boundaries.

Unreachable fetched objects can accumulate until safe whole-cache eviction; the authority does not run concurrent Git garbage collection.

SRT does not receive this cache or the authority binding. [Repository-native retrieval and sandboxed execution](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) owns full-copy, commit-pinned execution workspaces.

## Alternatives considered

**Keep local authority and mirror it remotely.** A successful local commit could still disappear before replication, and request replicas could not agree on one acknowledgement point.

**Support local and remote authority profiles.** This would double recovery, locking, and failure semantics. A disposable persistent cache keeps the single-server performance benefit without creating a second truth.

**Let SRT clone or push authority directly.** That would give an untrusted execution boundary credentials and network access. The application remains the only holder of the binding and the only component that advances the ref.

**Persist provider coordinates in each cache's Git configuration.** Standard Git tooling would make this convenient, but cache files, diagnostics, or sandbox copying could leak private repository identity. The adapter opens transports from the secret binding without writing it to the repository.

## Consequences

Remote availability participates in authoritative reads and writes. An already populated cache reduces object transfer but does not become an offline authority: current `main` must still be resolved before a read or write is reported as current.

The configured first milestone can serve only the existing default workspace. Open registration still needs provider-backed repository creation and durable workspace binding. This is a provisioning gap, not a second authority mode.

The application owns disposable cache directories completely. Direct authoring happens by pushing to the private remote; edits made inside `<data-dir>/workspaces/.../content` are overwritten on the next operation.

## Verification

- `ContentRepositoryBootstrapTests` covers missing binding, empty remote materialization, cache deletion plus process replacement, direct owner pushes, local-cache discard, cache eviction, address and credential redaction, and the absence of the remote address from cache metadata.
- `ImmutableRepositoryReadTests` blocks an exact blob payload read while withdrawing publication or removing remote `main`, verifies active-reader eviction exclusion, and checks pin cleanup after opening failure, callback failure, and cancellation. The required Linux storage replay runs these tests on a native Linux filesystem.
- `GitImageGrantRetentionTests` verifies grant renewal, exact expiry, eviction refusal, derived-cache reconstruction, unborn and force-pushed refs, continuous validation pins, bounded lifecycle cleanup, missing sources after cache hits, and grant-monitor and clock boundaries through real local Git objects. It also runs in the required native Linux replay.
- `DocumentWriteRecoveryTests` uses real disposable bare remotes to cover lost-success response reconciliation, an unverifiable ambiguous result, residue of a write interrupted before the root commit staying out of the next candidate, and two independent application caches advancing the same base with one success and one conflict.
- `ContentRepositoryScanTests` and `DocumentWriteServiceTests` run the existing read, revision, validation, attribution, and workspace-isolation contract through the remote-authority port.
- A representative nested Markdown plus 256 KiB image fixture records cold and warm latency, object bytes, cache bytes, and heap deltas. The local reference run recorded 264073 cold object bytes and zero additional warm object bytes; timing and heap observations remain environment-specific test output rather than product thresholds.
- `PostgresIntegrationIT` replaces only the binding source with a disposable file-transport remote and exercises default-workspace initialization through the remaining production Spring composition. The production properties accept HTTPS only.

## Risks

Provider exact-ref behavior and failure responses can vary. The JGit adapter implements expected-old-object updates and tests the state machine against a real Git transport; a production provider still needs a pre-release smoke test with its narrowly scoped credential.

Large binary histories increase cold-transfer and cache cost. The bounded persistent cache avoids re-receiving unchanged objects. Filtered fetch or a different cache lifecycle requires measurements from representative production repositories before adding more policy.
