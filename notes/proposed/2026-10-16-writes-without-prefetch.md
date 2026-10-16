# Writes Without a Prefetch

Date: 2026-10-16
Status: Proposed

## Problem

Under [remote repository authority](../implemented/2026-09-01-remote-repository-authority.md), every authoritative write first fetches and resolves remote `main` under the workspace lock. It then pushes with an expected-old-object update that advances `main` only while the remote still equals the write's base. The push is already the safety check. For a write that names its base, the prefetch mostly confirms in advance what that compare-and-swap verifies anyway.

On 2026-10-16 production saves to the GitHub-backed space were profiled with JFR, with the application host about 3 ms from GitHub. Request threads waited about 1.5 s per save on the fetch and about 1.7 s on the push. The fetch was over a quarter of a save's six seconds.

## Proposal

- The authority records, per workspace cache, the remote `main` it last fetched.
- When a write's declared base equals that recorded commit and the cache holds the base, the write builds its commit against the cache. It pushes with expected old object equal to the base, without fetching first.
- If the remote has moved, it refuses the push. The authority then fetches once and raises the same `RepositoryConflictException` a prefetched write raises today, and the caller re-reads as it does now.
- A lost push response keeps today's single read of remote `main` and today's success, conflict and ambiguity rules.
- Writes that declare no base, and every read, keep fetching first. Only the write path changes.

## Acceptance criteria

- An uncontended save performs no fetch. A counting transport in `RepositoryPatchServiceTests` asserts this, and production `repo_exec stages` bridge time drops by the fetch share.
- A remote advanced by another writer between the cache's last fetch and the push produces the same conflict outcome and leaves the same local state as today.
- Lost-response reconciliation and definite remote refusals map exactly as the authority note specifies.
- The publication checks a write performs see the same base tree as before, because they read the base commit's objects.

## Alternatives

- **Replace the fetch with a refs-only check (`ls-refs`).** This removes object transfer, but still pays a round trip before every write, while the push already performs that check.
- **Keep caches warm with a background fetch.** This shortens the prefetch but cannot remove it, and adds remote load proportional to the number of workspaces.

## Consequences

An uncontended write costs one round trip instead of two. Under contention, a stale cache costs one rejected push and one fetch, the same as today's path plus the rejected attempt. The change relies on the provider honouring expected-old-object updates, which the authority already requires.
