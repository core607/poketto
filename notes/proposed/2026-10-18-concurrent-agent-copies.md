# Concurrent Agent Copies

Date: 2026-10-18

## Problem

[Account working copies](../implemented/2026-09-14-account-working-copies.md) give each account one copy per reading scope and serialize its commands. Parallel agents of one account therefore queue, and get `EXECUTION_REFUSED BUSY` after five seconds: six writers of one account produced 47 such refusals on 2026-10-15. [Seed copies](../implemented/2026-10-18-seed-copies.md) made a further copy at a known commit cheap to create. The target is several hundred agents working at once.

## Proposal

1. **Several copies per account.** `expectedCopyId=new` keeps opening the account's default copy while no command runs in it. While one does, `new` opens a new copy for the caller instead of refusing with `BUSY`. The result says so, and the agent keeps that copy's ID from then on. Explicit IDs keep their current meaning. The site group bounds how many copies an account holds, and only the pool bounds administrators. At the bound, `new` refuses with a reason that says so.
2. **Sandboxes sized by activity.** A live command unit costs 35–50 MB. The idle window shrinks from 30 minutes, and when the number of live units reaches a configured bound, the least recently used idle unit stops first. The next command in that copy then reports `freshSandbox=true`.

## Acceptance

- Parallel `new` calls of one account run in separate copies, without `BUSY`, until the account's bound.
- Account copies keep every existing guarantee: identity checks before execution, retention across reconnects, explicit disposal and expiry.

## Alternatives

- **Every `new` creates a private copy.** A client that sends `new` on every call, instead of keeping the returned ID, would fork on every call and strand its edits. Forking only while the default copy is busy keeps such clients working, and still separates agents that start in parallel.
