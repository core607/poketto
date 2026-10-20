# Concurrent Account Journal Publication

Date: 2026-10-21
Status: Proposed

## Problem

[AccountCopyStore](../../src/main/java/io/github/core607/poketto/executor/internal/AccountCopyStore.java)
holds one index lock across each journal's file synchronization, atomic replacement
and directory synchronization. Independent copies therefore wait for another
copy's durable write. Every acquisition also scans the whole directory for pending
files. Removing the write lock alone would let that cleanup delete another
writer's unpublished file.

## Proposal

Keep the existing per-owner writer lock across command transitions and durable
publication. Move independent copies' record serialization and synchronization
outside the global index critical section. Reserve the index lock for operations
that require a consistent inventory or serialize owner admission and removal.
Keep owner admission nonblocking while holding the index lock, as required by
[workspace synchronization](../implemented/2026-09-15-workspace-synchronization.md).

Give unpublished journal files an owner-identifiable name. Recover or remove a
pending file only while holding that owner's lock; an active writer's staging
must survive another copy's admission, collection and publication. Preserve
[RetainedFileLocks](../../src/main/java/io/github/core607/poketto/executor/internal/RetainedFileLocks.java)'s
single-channel rule so closing a competing descriptor cannot release another
operation's POSIX lock.

Keep file synchronization, atomic publication and directory synchronization before
acknowledgement. A failed or interrupted publication remains uncertain until
recovery. Referenced baselines cannot disappear while a valid reader or writer
uses them. Preserve default and fork identity, capacity bounds, expiry, disposal,
public refresh and cross-process writer handoff. Existing acknowledged copies
and their files must survive delivery.

### Acceptance

Extend the account-store concurrency and recovery tests so a stalled publication
for one owner does not stop another owner's durable write, while same-owner
exclusion and active staging survive. Run `linuxStorageTest`,
`AccountCopyStoreNativeProbe` and every `retained-process` case in the
[native probe](../../executor-native/README.md), including publication interruption
and acknowledged-work recovery. Compare protected HTTP/MCP load with the same
resource limits, reporting lock wait, publication time, CPU, success and tail
latency. Aborted ramps do not establish steady capacity.

## Alternatives

Global locking simplifies recovery but serializes independent copies. Deferred
synchronization loses durability; changing supervisor language leaves the Java
journal's locking and synchronization unchanged.

## Consequences

Cleanup needs explicit staging ownership rather than a global pending-file sweep.
Parallel writes may increase instantaneous I/O and executor demand; lower lock
wait alone does not prove higher sustainable capacity.

[Account working copies](../implemented/2026-09-14-account-working-copies.md) remains
the owner of identity and retention; the synchronization record owns bounded
waiting and lock ordering.
