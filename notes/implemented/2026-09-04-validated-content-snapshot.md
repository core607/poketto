# Validated Content Snapshot

Date: 2026-09-04
Status: Implemented

## Problem

The [HTTP entrance baseline](2026-09-03-http-entrance-baseline.md) resolved remote `main` on every public request: fetch, reset the cache, read and parse every managed document under the workspace lock, then filter to public documents. Remote latency and outages reached every page view, concurrent requests to one workspace queued behind a single fetch, and one document that failed validation anywhere in the repository, private documents included, turned the whole public API into a 503. No bound existed on document size, frontmatter size, tag count, path length, document count, or total bytes, so one push could exhaust memory. `/actuator/health` proved only that the process and the database answered; startup proved only that the remote could be reached. The remote's own refusal of a push was reported as a lost response, and an update read the host clock before taking the lock and failed when that clock was not ahead of the stored `updated_at`.

## Decision

### One validated snapshot per workspace

`ContentRepositoryStore` serves a `ContentSnapshot`: every managed document of one workspace at one remote `main` commit, validated as a whole, with the time it passed validation. Readers never contact the remote; `PublicDocuments` lists and finds documents in the snapshot. The snapshot changes only through:

- `refresh`, which resolves remote `main`, validates the whole commit, and installs it. A commit that fails validation leaves the snapshot in service untouched and reports the failure.
- an acknowledged write. `JGitDocumentWriteService` installs the candidate commit as the snapshot while it still holds the workspace lock, so a write is visible on the next request. The write is acknowledged even when that install fails; the failure is logged and the next refresh installs the commit or keeps reporting why it cannot.

Startup calls `ensureReady`, which refreshes and, when the remote cannot be resolved or its commit fails validation, falls back to the last validated commit recorded in the cache. Startup fails when neither exists, so a deployment cannot report healthy without content it can serve.

`ContentSnapshotRefresher` refreshes each served workspace on a fixed delay of `poketto.repository.refresh-seconds`, default 30. A refresh that finds the served commit unchanged only renews its validation time. The freshness contract is therefore: a write through Poketto is visible immediately; a valid direct owner push is visible one refresh interval plus one fetch and scan after it lands. A failed refresh keeps the current snapshot and is logged once per outage.

### Last validated commit

After each validation the store writes `poketto-validated-main` (commit id and validation time) into the cache's Git directory. The file survives the worktree resets that materialize fetched commits and disappears with the cache. A replacement process whose remote is unreachable scans that commit from the cache's objects and serves it, logging that it does so; the marker's time becomes the snapshot's validation time, so staleness reflects reality rather than the restart. A recorded commit that is no longer in the cache is refused.

`RepositoryAuthority.readCache` reads the cache without contacting the remote and never creates one. The marker is replaced atomically, so an interrupted write leaves the previous record readable.

### Whole-commit validity

A commit is valid only when every managed document is valid. There is no per-document isolation: remote `main` is the authority, and serving a subset of it would present a tree the owner never committed. The consequence is deliberate: an invalid push keeps the previous validated commit in service until the repository is repaired, and the failure is logged with the offending path.

### Content bounds

`ContentLimits` fixes the format's resource bounds as constants; a document or workspace beyond them is invalid content, not a configuration matter.

| Bound | Value |
|---|---|
| Document bytes (exact blob) | 1 MiB |
| Frontmatter bytes (UTF-8, between the delimiters) | 16 KiB |
| Title length (code points, after trimming) | 200 |
| Tags per document | 32 |
| Tag length (code points, after trimming) | 64 |
| Managed path length (characters) | 255 |
| Managed documents per workspace | 10 000 |
| Managed document bytes per workspace | 256 MiB |

Titles and tags reject control characters. The scan reads each blob's size from its object header before inflating it, so an oversized document is rejected before its bytes are loaded, and it stops at the document-count and total-byte bounds. `CanonicalDocumentCodec` applies the document and frontmatter bounds when parsing and the document bound when serializing, and a write refuses a commit that would push the workspace past its document or byte bound, so the remote never receives a tree the scan would reject. The byte check does not credit replaced bytes back, so it can refuse a replacement at the very edge of the bound.

### Readiness

The `contentSnapshot` health indicator reports `DOWN` without a validated snapshot for the default workspace, `OUT_OF_SERVICE` when the snapshot has not been re-validated within `poketto.repository.stale-after-seconds` (default 3600, never shorter than the refresh interval), and `UP` otherwise. It never contacts the remote. It participates in the aggregate `/actuator/health` that the Compose health check and the deployment entrance read, so a deployment passes only when content is served. A failed validation does not renew the validation time: a remote that stays unreachable, or an invalid push that stays unrepaired, past the stale bound makes the process stop reporting healthy while it keeps serving the last validated snapshot, and a deployment during that time fails until the cause is repaired. Health details remain hidden.

### Definite rejections

`JGitRemoteGitTransport` maps a push status of `REJECTED_OTHER_REASON` or `REJECTED_NODELETE` to `RemoteGitRejectedException`: the remote answered and `main` did not advance. The authority reconciles it by reading remote `main` once. A changed ref is a `RepositoryConflictException`, because a competing writer holding the ref lock produces the same refusal as a policy; an unchanged ref, or an unreadable remote, is a definite `ContentRepositoryException` stating that `main` did not advance. Only a transport interruption still reaches the lost-response path that can end in `RepositoryWriteAmbiguousException`.

### Change time

A write reads the clock inside the workspace lock. For an existing document the change time is the later of the clock and the stored `updated_at` plus one millisecond, so a stepped-back host clock or a direct push stamped in the future cannot make a legitimate write fail, and `updated_at` strictly advances on every committed change.

## Alternatives

**Revalidate the remote ref on each request.** A ref advertisement is cheaper than a fetch, but it still puts a remote round trip and every outage on the request path.

**Isolate invalid documents and serve the rest.** This serves a tree the owner never committed and hides the failure behind a partially working blog. Keeping the last valid commit whole and logging the failure is simpler and honest.

**Load one blob per request instead of holding parsed documents.** With the bounds above the resident snapshot is bounded and single-document lookup is a map read. A blob-per-request design can return if the bounds grow.

**Refresh from a provider webhook.** It needs a public endpoint and a shared secret before the first release. The fixed delay serves the freshness contract now; a webhook can shorten it later without changing the contract's shape.

**Persist the parsed snapshot.** The cache already holds the objects; recording the validated commit id is enough to rebuild the snapshot and avoids a second serialized format.

## Consequences

Public reads no longer depend on remote availability. A direct push lags by at most one refresh interval; a push that fails validation keeps the previous content in service until it is repaired. Memory for served content is bounded by the workspace byte bound plus parsed overhead.

A cold start with an unreachable remote and no cache fails; a restart with a cache serves the last validated commit and logs it. The refresh thread and writes share the workspace lock, so a slow fetch can delay a write by up to the transport timeout.

The bounds are content-format rules: raising one is a format decision that needs its own note, and a repository already beyond a bound is invalid until reduced.

Public reads do not perform the per-request resolution that the [HTTP entrance baseline](2026-09-03-http-entrance-baseline.md) and the [remote repository authority](2026-09-01-remote-repository-authority.md) describe for the authority; `scan` remains the live read used by refresh and by tests.

## Verification

- `ContentSnapshotTests` covers serving without the remote, an invalid commit leaving the served snapshot in place, a direct push becoming visible on refresh, a replacement process serving the recorded commit rather than a newer invalid cache head while the remote is unreachable, failing closed without a recorded commit, refusing a recorded commit that left the cache, and rejecting an oversized document without serving it.
- `ContentSnapshotHealthIndicatorTests` and `ContentSnapshotRefresherTests` cover the three health states and a failing workspace not stopping the others.
- `DocumentWriteRecoveryTests` covers a definite refusal leaving `main` untouched and a refusal during a competing advance reporting a conflict; `DocumentWriteServiceTests` covers a clock behind the stored update time and the acknowledged write appearing in the snapshot.
- `DocumentValueTests`, `CanonicalDocumentCodecTests`, and `DocumentPathRulesTests` cover each bound at and beyond its limit; `PublicDocumentControllerTests` covers a missing snapshot as a sanitized 503.
- `./gradlew test`, `./gradlew repoCheck`, and `git diff --check` pass; `PostgresIntegrationIT` exercises startup, health, and the public entrance through the real composition in CI.

## Risks

Staleness during a long remote outage is visible only in health and logs; a reader of the blog sees the last validated content without an indication of its age.

The document-count and byte bounds are chosen for a personal knowledge base on one server and have not been measured against a repository near the limits; the write-side capacity check and the acknowledged-but-not-served path have no automated test because reaching them needs a repository at those bounds.
