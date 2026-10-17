# Concurrent Agent Copies

Date: 2026-10-18

## Problem

[Account working copies](2026-09-14-account-working-copies.md) gave each account one copy per reading scope and serialized its commands. Parallel agents of one account therefore queued behind each other, and got `EXECUTION_REFUSED BUSY` after five seconds: six writers of one account produced 47 such refusals on 2026-10-15. [Seed copies](2026-10-18-seed-copies.md) made a further copy at a known commit cheap to create.

## Decision

`expectedCopyId=new` opens the account's default copy of the grant's scope whenever no command holds that copy's journal. While one does, `new` opens a fork: a further copy that belongs to the caller from then on.

- A fork's owner carries its copy ID, so its journal address, lease key, worker identity and portable-export client all differ from the default copy's and from every other fork's. The default copy keeps its original journal address.
- A named `expectedCopyId` resolves to the default copy of a readable scope, or to the fork with that ID, and waits for its own journal as before. Disposal and artifact reads find forks the same way, and the expiry collector handles them like any copy.
- The site group bounds the copies an account holds in a workspace (`poketto.executor.account-copies.*`: 2, 4 and 16 for viewers, community members and creators). Administrators are bounded only by the copy store's site-wide limit. At its bound, `new` waits for the default copy as before.
- The result names the fork's `copyId`. An agent that keeps the ID its results return keeps its own files and shell state. One that sends `new` on every call keeps landing in the default copy whenever it is idle.

## Alternatives

- **Every `new` creates a private copy.** A client that sends `new` on every call, instead of keeping the returned ID, would fork on every call and strand its edits. Forking only while the default copy is busy keeps such clients working, and still separates agents that start in parallel.
- **An explicit `fork` value for `expectedCopyId`.** Agents would have to know when they run in parallel, and most clients start subagents without telling them. The busy default copy is the signal that is always available.

## Consequences

- Agents that start while the default copy is idle still share it, and their commands still serialize. Fork-on-busy separates bursts of parallel starts, not agents that start one at a time.
- Leases and command units now scale with active agents rather than with accounts, so the site-wide session bound and the worker's `maxLiveUnits` decide how many run at once.
- Saves from different copies meet at the remote branch, where per-file baselines and the exact-commit push decide conflicts, as they already did for browser and other client writes.

## Verification

`WorkerSocketTests.overlappingInitialCallsOpenSeparateCopiesUntilTheAccountsCopyQuota` pins that a busy default copy makes `new` fork and that a fork is addressable by its ID, and that the account's quota makes `new` wait instead.
