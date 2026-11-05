# Concurrent Agent Copies

Date: 2026-10-18

## Problem

[Account working copies](2026-09-14-account-working-copies.md) gave each account one copy per reading scope and serialized its commands. Parallel agents of one account therefore queued behind each other, and got `EXECUTION_REFUSED BUSY` after five seconds: six writers of one account produced 47 such refusals on 2026-10-15. [Seed copies](../archived/2026-10-18-seed-copies.md) made a further copy at a known commit cheap to create.

## Decision

`expectedCopyId=new` opens a copy no other agent is working in. That is the account's default copy of the grant's scope when no command holds its journal and none ran in it within the last two minutes, the agent claim. Otherwise it is an idle fork outside its claim, and only then a new fork: a further copy that belongs to the caller from then on.

- A fork's owner carries its copy ID, so its journal address, lease key, worker identity and portable-export client all differ from the default copy's and from every other fork's. The default copy keeps its original journal address.
- A named `expectedCopyId` resolves to the default copy of a readable scope, or to the fork with that ID, and waits for its own journal as before. Disposal and artifact reads find forks the same way, and the expiry collector handles them like any copy.
- The site group bounds the copies an account holds in a workspace (`poketto.executor.account-copies.*`: 2, 4 and 16 for viewers, community members and creators). Administrators are bounded only by the copy store's site-wide limit. At its bound, `new` waits for the default copy.
- The result names the fork's `copyId`. An agent that keeps the ID its results return keeps its own files. Template-backed commands start fresh shells; an imported volume can reuse its live lease. One that sends `new` again within a claim lands in another copy, without its edits.

## Alternatives

- **Every `new` creates a private copy.** Copies would grow with calls rather than with agents; reusing copies outside their claim keeps them as many as the agents working at once.
- **Forking only while a command holds the default copy**, as first shipped. Agents think between commands, so most found it idle and converged on it: the 2026-10-18 load test opened 7 to 36 copies for 50 agents, and 213 of 510 calls were refused `BUSY`. It did keep working a client that sends `new` on every call, which the claim gives up.
- **An explicit `fork` value for `expectedCopyId`.** Agents would have to know when they run in parallel, and most clients start subagents without telling them. A copy's recent command is the signal that is always available.

## Consequences

- Agents of one account that work at the same time get a copy each, whether they start together or one at a time. A copy is shared again only after two minutes without a command, so an agent that pauses longer may find another agent in it.
- Live native shells scale with active commands. Application admission and the native profile bound concurrency independently of the retained-copy count.
- Saves from different copies meet at the remote branch, where per-file baselines and the exact-commit push decide conflicts, as they already did for browser and other client writes.

## Verification

`NativeRepositoryCopyClaimsTests` pins busy and recently used copy claims, fork addressing, account quotas and reuse of unclaimed forks.
