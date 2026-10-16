# Workspace Cache Packing

Date: 2026-10-16
Status: Proposed

## Problem

[Remote repository authority](../implemented/2026-09-01-remote-repository-authority.md) keeps a disposable Git cache per workspace and never runs concurrent garbage collection. Unreachable objects may accumulate until the whole cache is evicted. Nothing ever packs the objects that fetches and writes leave loose, either. Each saved commit adds loose commit, tree and blob objects. Every later reader then opens and inflates those files one by one, and each fetch adds another pack.

On 2026-10-16 the production space's cache held 1704 loose objects in 12 packs, 339 MB in all. In JFR samples of four saves, opening loose objects (`ObjectDirectory.openObjectWithoutRestoring` under `WindowCursor.open`) made up most of the export preflight's CPU. It also made up most of the publishing-policy reads that game validation repeated. The commit-keyed policy cache and the incremental preflight remove those two repeat readers. Every other object read still pays the same per-file cost.

## Proposal

- A maintenance task visits a workspace cache when its loose objects exceed 256 or its packs exceed 8. It runs under the workspace authority lock, so it never overlaps a fetch or a write.
- It writes the loose objects and small packs into one pack, and makes that pack durable before removing anything.
- It removes a loose file or a superseded pack only after a grace period longer than the longest immutable reader and image-grant pin, which the authority already tracks. A reader that opened the old files keeps working.
- It deletes no unreachable object. Pruning remains the job of whole-cache eviction, so the authority note's guarantee that already-fetched objects survive ref updates is unchanged.

## Acceptance criteria

- Under steady writes, loose objects and packs per cache stay below the thresholds.
- A reader that holds an `ObjectReader` across a packing run reads the same objects without error. The test runs concurrently with `ImmutableRepositoryReadTests` and `GitImageGrantRetentionTests`.
- No reachable or pinned object becomes unreadable, and the cache uses no more than its pre-packing size plus one pack while packing.
- Production object-read cost drops, measured with the same JFR sampling as above.

## Alternatives

- **Evict and refetch the cache.** This needs no new code, but transfers the whole history again (339 MB for the measured space) and makes the next request cold.
- **Run JGit's full garbage collection with pruning.** That would delete unreachable objects that immutable readers or grants may still hold. The authority note rejects this explicitly.

## Consequences

Object reads stay cheap as history grows, at the cost of periodic packing I/O under the workspace lock. That lock delays writes to the workspace being packed. The thresholds and grace period are the knobs to measure.
