# Remote Repository Authority

Date: 2026-09-01
Status: Implemented

## Problem

The original executable baseline acknowledged writes to a repository on the application server. That tied durable content to one filesystem, prevented replaceable request hosts, and would have given the primary single-server and optional serverless profiles different correctness rules.

Poketto needs one repository authority model that survives cache deletion and process replacement without making agents, browser callers, or sandbox jobs repository credential holders.

## Decision

### One authority model

Every production workspace has one private remote Git repository. Its `main` ref is the sole authority for Markdown, repository metadata, publishing policy, and repository-managed files. A write is acknowledged only after remote `main` contains its candidate commit.

Every deployment uses the internal `RepositoryAuthority` port, which supports no local Git authority and never falls back to local disk; the [serverless profile](../rejected/2026-09-01-optional-serverless-deployment-profile.md) would have used the same port. `<data-dir>/workspaces/<workspace-id>/content` is a disposable workspace-scoped cache selected only from a validated `WorkspaceId`.

Authoritative reads and writes first fetch and resolve remote `main` under the workspace lock. Reads and writes are object-only: they update the cache's local ref and read or build Git objects without checking out files, so local files in the cache are never content. An empty pre-provisioned remote remains on an unborn `main` until the first write. Direct owner pushes are therefore visible on the next authoritative read or write. Public requests do not perform this read: the public snapshot of the [repository authoring foundations](2026-09-05-repository-authoring-foundations.md#repository-and-publication) refreshes on a schedule and serves the last verified commit.

### Writes and failure semantics

`RepositoryAuthority` gives the content layer a commit-pinned snapshot of the cache's objects plus, for writes, an exact-ref advancer. Provider coordinates and credentials do not cross that port.

A write builds and validates a commit against the resolved base, then pushes its objects and advances remote `main` only when the remote ref still equals that base. A competing update raises `RepositoryConflictException`; the caller re-reads instead of overwriting.

When the push response is lost, the adapter reads remote `main` once. The write succeeds only when that ref equals the candidate commit. A different ref reports either a conflict or a definite failed update. If the ref cannot be read, `RepositoryWriteAmbiguousException` tells the caller not to retry blindly. No path performs an unconditional or blind retry.

A remote refusal is definite: `JGitRemoteGitTransport` maps a push status of `REJECTED_OTHER_REASON` or `REJECTED_NODELETE` to `RemoteGitRejectedException`, because the remote answered and `main` did not advance. The authority reads remote `main` once. A changed ref is a `RepositoryConflictException`, because a competing writer holding the ref lock produces the same refusal as a policy such as branch protection; an unchanged ref, or an unreadable remote, is a definite `ContentRepositoryException` stating that `main` did not advance. Only a transport interruption reaches the lost-response path that can end in `RepositoryWriteAmbiguousException`.

The write result's commit observes this remote acknowledgement boundary. File revisions are exact-blob SHA-256 values inside the content module and opaque tokens to entry points; reads supply them, so callers never manufacture one.

### Binding and credentials

The first production binding attaches the database-created default workspace to one operator-provisioned HTTPS repository through these secret-backed settings:

- `poketto.repository.remote-uri`
- `poketto.repository.username`
- `poketto.repository.password`
- `poketto.repository.cache-max-workspaces`, default `32`
- `poketto.repository.timeout-seconds`, default `30`
- `poketto.repository.refresh-seconds`, default `30`, and `poketto.repository.stale-after-seconds`, default `3600`, owned by the [public snapshot](2026-09-05-repository-authoring-foundations.md#repository-and-publication)

The default workspace uses this binding unless a GitHub App binding or a managed connection binds it; the deployment configuration requires all three values. The address must be HTTPS and may not embed credentials, a query, or a fragment. Production configuration has no local-authority or file-transport switch; integration tests replace the binding source inside the test composition to use disposable bare remotes.

The adapter does not persist a Git remote in cache configuration and fetches over a direct transport connection that never writes JGit's `FETCH_HEAD`, which would otherwise record the source URI. Binding and configuration string forms are redacted, and transport failures surface sanitized messages without their underlying address or credential. Other workspaces bind through persisted per-workspace connections: [managed workspace connections](2026-09-11-managed-workspace-connections.md) for an existing repository and [GitHub-authorized personal spaces](2026-09-21-github-authorized-personal-spaces.md) for an App-created one, within the space creation of [multi-user workspaces](2026-09-11-multiuser-workspaces-and-discovery.md).

### Cache boundary

The cache retains fetched Git objects, so a warm read negotiates current `main` but receives no unchanged image or history objects. `poketto.repository.cache-max-workspaces` bounds workspace caches. On first access beyond the bound, the least-recently-used idle cache with no live image protection is removed. If every cache is active or protected, admitting another workspace reports capacity unavailable. Cache deletion, eviction, dirty local state, and an empty-disk restart do not change authoritative content.

Exact image reads and image metadata scans use a synchronous `ObjectReader` operation over the existing cache. They never acquire the workspace mutex, so an in-progress fetch cannot delay already selected objects. Opening the repository holds only the short cache-lifecycle lock; the callback reads explicit commit and blob ids after releasing it, without fetching, checking out files, or changing refs. Fetches append immutable objects and do not prune existing ones. The cache remains active until both the reader and repository have closed, including callback failure and cancellation.

Before issuing or reusing a Git image grant, the asset service verifies the exact source commit, path, blob id and size. The authority retains its active-reader pin through validation and installation of a workspace protection expiry. Each workspace records the maximum expiry; a shorter request cannot undo another grant's protection. The protection deadline is at most five minutes after admission according to the trusted UTC clock. Public grants also end by their snapshot expiry, and each replay requires the currently served snapshot to hold the grant's commit and page ([website delivery boundary](2026-09-14-workspace-public-delivery.md)). Within those limits, a valid grant's source survives derived-image cache deletion even when remote `main` has changed or become unborn. It does not reconstruct a missing source object database: protection never fetches, and the remote need not retain objects removed by a force push.

Source validation runs outside the global image-grant monitor and does not authorize publication. Grant admission rechecks time and rejects a clock rollback or an elapsed candidate expiry. A hit in the derived image cache cannot replace source validation. If source validation at grant admission fails, rendering omits that image authorization and preserves the article body. Failure after successful source protection, including capacity rejection or clock rollback, may conservatively retain the source until its existing UTC deadline. A clock rollback can therefore lengthen elapsed cache retention without issuing a new grant. Failed admission must not shorten the shared deadline because another live grant may depend on it. Expired zero-user lifecycle entries are reclaimed on repository access. Protection is process-local, like the grant table, and holds no long-lived repository handles or per-token leases.

A remote ref update, including a force push or an unborn `main`, preserves already fetched objects. Transitioning to unborn `main` removes the local ref without deleting the object database. Existing immutable readers can finish while the new empty snapshot takes effect. Callers choose and authorize the exact commit before reading; public image grants are signed under a final snapshot installation lock after payload preparation. [Repository authoring foundations](2026-09-05-repository-authoring-foundations.md) owns these rendering and authorization boundaries.

Unreachable fetched objects can accumulate until safe whole-cache eviction; the authority does not run concurrent Git garbage collection.

SRT does not receive this cache or the authority binding. [Repository-native retrieval and sandboxed execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) owns full-copy, commit-pinned execution workspaces.

## Alternatives considered

**Keep local authority and mirror it remotely.** A successful local commit could still disappear before replication, and request replicas could not agree on one acknowledgement point.

**Support local and remote authority profiles.** This would double recovery, locking, and failure semantics. A disposable persistent cache keeps the single-server performance benefit without creating a second truth.

**Let SRT clone or push authority directly.** That would give an untrusted execution boundary credentials and network access. The application remains the only holder of the binding and the only component that advances the ref.

**Persist provider coordinates in each cache's Git configuration.** Standard Git tooling would make this convenient, but cache files, diagnostics, or sandbox copying could leak private repository identity. The adapter opens transports from the secret binding without writing it to the repository.

## Consequences

Remote availability participates in authoritative reads and writes. An already populated cache reduces object transfer but does not become an offline authority: current `main` must still be resolved before a read or write is reported as current.

Per-workspace connections add provisioning, not a second authority mode.

The application owns disposable cache directories completely. Direct authoring happens by pushing to the private remote; edits made inside `<data-dir>/workspaces/.../content` are never read as content.

## Verification

`ContentRepositoryBootstrapTests`, `ImmutableRepositoryReadTests`, `GitImageGrantRetentionTests`, `PublicCacheConcurrencyTests` and `RepositoryPatchServiceTests` pin the binding, redaction, cache, immutable-read, grant-retention and exact-ref write contracts against real Git transports; the required native Linux replay runs the storage suites among them. `PostgresIntegrationIT` replaces only the binding source with a disposable file-transport remote; production properties accept HTTPS only.

## Risks

Provider exact-ref behavior and failure responses can vary. The JGit adapter implements expected-old-object updates and tests the state machine against a real Git transport; a production provider still needs a pre-release smoke test with its narrowly scoped credential.

Large binary histories increase cold-transfer and cache cost. The bounded persistent cache avoids re-receiving unchanged objects. Filtered fetch or a different cache lifecycle requires measurements from representative production repositories before adding more policy.
