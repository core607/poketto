# Concurrent Account Journal Publication

Date: 2026-10-21

## Problem

Holding the global account index lock through file and directory synchronization
serializes independent copies behind another copy's durable write. A global
pending-file sweep also prevents moving those writes outside the lock: admission
could delete an active writer's unpublished file.

## Decision

[AccountCopyStore](../../src/main/java/io/github/core607/poketto/executor/internal/AccountCopyStore.java)
uses the owner lock for updates to an existing journal. Publication still forces
the staged file, atomically replaces the journal and forces the directory before
acknowledgement. Failure makes that lease uncertain. Referenced baselines become
durable before publication; their reads and removal hold the same owner lock.

The index lock covers owner admission, first publication, removal and inventory.
Replacing an existing journal changes neither its owner nor its copy ID, so
inventory readers can observe either complete version. Routing and collection
lock the selected owner and reread mutable state before acting. First publication
keeps the index lock throughout durability work to preserve admission visibility.
Owner admission stays nonblocking under the index lock, preserving
[bounded waiting and lock ordering](2026-09-15-workspace-synchronization.md).

Each owner reuses one pending path for sequential baseline and journal writes.
Only that owner's lock permits cleanup, including reclamation of an unused owner
lock. A newly created owner-lock entry is synchronized before staging begins, so
recovery can find its owner. The existing single-channel rule in
[RetainedFileLocks](../../src/main/java/io/github/core607/poketto/executor/internal/RetainedFileLocks.java)
preserves POSIX exclusion across processes.

## Alternatives

Global publication locking simplifies cleanup but retains cross-copy waiting.
Deferred synchronization loses acknowledged durability. Changing the supervisor's
language does not change Java journal serialization.

## Consequences and verification

Independent durable writes can overlap, increasing instantaneous I/O demand;
lower lock wait alone does not establish sustainable capacity. Account identity,
quotas, expiry and disposal remain owned by
[account working copies](2026-09-14-account-working-copies.md).

`AccountJournalConcurrencyProbe`, `AccountCopyStoreNativeProbe`,
`AccountFileBaselinesTests` and every `RetainedProcessNativeProbe` crash case pin
concurrency, ownership, durability and recovery. Protected HTTP/MCP comparisons
retain the same resource bounds; aborted ramps do not establish steady capacity.
