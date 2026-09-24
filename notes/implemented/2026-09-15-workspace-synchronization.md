# Whole-Workspace Synchronization

Date: 2026-09-15

## Problem and decision

Long-lived account copies need an explicit way to discover new remote files, remove remotely deleted files and reconcile changes without discarding unfinished work. A path argument requires the caller to know the remote change set first. `poketto sync` therefore reconciles the whole workspace against one authoritative remote commit; the former single-file command and its host plan are removed.

The host inventories retained baseline paths and the fixed remote tree before changing local files. It bounds paths, combined text bytes and traversal time. Missing text is not proof of absence: ordinary binary content participates through bounded Git blob inspection and transfer. Conflicting binary content, unsafe local entries and unsupported remote changes are preserved and reported. Text conflicts retain LOCAL/BASE/REMOTE versions. Untracked local-only files are outside the remote change set. Synchronization never publishes a remote commit.

The application reuses this inventory throughout reconciliation instead of resolving each text path again. Progress for paths that do not change local files is batched; it is published together with the next exact installation intent or at completion. Recovery inventories the retained target commit, even if remote main has since advanced.

## Installation and recovery

The account journal retains the fixed target revision, ordered paths, progress and exact next installation before the worker can change a file. Each installation checks the observed local digest under the command freeze. An interrupted acknowledgement can be replayed against the same desired bytes or completed deletion; newer local edits are not overwritten. The journal advances a file's remote baseline only after installation acknowledgement. The whole-copy commit advances only after all paths finish, including explicitly reported conflicts.

Status exposes pending synchronization and progress. `poketto recover` continues it; `--skip-local` releases remaining work without reverting installed files or advancing the whole-copy commit. Saves and moves wait until this pending operation is resolved. Conflict receipts bound their sample while preserving the total count. Public projections cannot enter this private-read synchronization path, and blob inspection and streaming require current private-read authorization.

Copy creation also waits up to five seconds for the shared journal index lock instead of failing immediately when background collection holds it briefly. Owner locks remain nonblocking while the index is held, preserving lock ordering and account-level exclusion.

## Alternatives and consequences

Keeping per-path synchronization misses unknown additions. Resetting the worktree loses unfinished edits. Automatically following remote main changes the user's working context without an explicit operation. Whole-workspace synchronization is explicit and recoverable, but can be partial on interruption and may require manual resolution of binary or file-type conflicts. Retained file baselines remain host-owned write preconditions; their runtime representation is consolidated by the linked mutable-copy decision.

## Evidence and related decisions

`WorkspaceSynchronizationTests` and `WorkspaceSyncInputsTests` pin inventory bounds, journal ordering and replay after a lost installation acknowledgement; the worker's `test_materialize.py` pins idempotent deletion and protection of a recreated file, and the [workspace sync evidence](../../acceptance/clients/evidence/2026-09-15-workspace-sync.json) records additions, deletions and conflicts through authenticated HTTP/MCP.

[Mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md) own the runtime baseline state and HEAD/index advancement. [Account working copies](2026-09-14-account-working-copies.md) own account identity, disk quotas and expiry. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns selected remote saves and publication boundaries.
