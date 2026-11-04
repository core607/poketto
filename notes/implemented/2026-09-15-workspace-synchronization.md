# Whole-Workspace Synchronization

Date: 2026-09-15

Partially superseded by: [Native repository consumer](2026-11-05-native-repository-consumer.md).

## Problem and decision

Long-lived account copies need an explicit way to discover new remote files, remove remotely deleted files and reconcile changes without discarding unfinished work. A path argument requires the caller to know the remote change set first. Synchronization therefore inventories the whole workspace against one authoritative remote commit. The native consumer prepares candidates for explicit acceptance.

The host inventories retained baseline paths and the fixed remote tree before staging candidates. It bounds paths, combined text bytes and traversal time. Missing text is not proof of absence: ordinary binary content participates through bounded Git blob inspection and transfer. Conflicting binary content, unsafe local entries and unsupported remote changes are preserved and reported. Text candidates can contain LOCAL/BASE/REMOTE conflict markers. Untracked local-only files are outside the remote change set. Synchronization never publishes a remote commit.

The application reuses this inventory throughout preparation instead of resolving each text path again. Resumed preparation inventories the retained target commit, even if remote main has since advanced.

## Acceptance and recovery

The account journal retains the fixed target revision, ordered paths, immutable originals, candidates and staging progress. Preparation writes only plan staging files. The caller inspects the candidates, edits working files and accepts explicit hashes or absence. Every selected path is checked before that acceptance batch advances host baselines; a mismatch acknowledges none of the batch. Accepted paths use the retained remote revision, while unaccepted paths keep their previous baselines. These are per-path observations, with no simultaneous worktree snapshot.

Status exposes the retained plan. Repeating `poketto sync prepare` resumes preparation. `poketto plan file` restores an original or candidate to an absent output path; releasing the plan removes host backups while retaining working files, staging files and accepted baselines. Saves and other plans wait until the plan is released. Public projections cannot enter this private-read synchronization path, and blob inspection and streaming require current private-read authorization.

Preparation yields after making progress when the command's remaining time reaches its reserve. Retaining each staged path allows a later command to continue instead of restarting a large inventory. A command timeout cannot safely promise that unacknowledged staging completed.

Copy creation also waits up to five seconds for the shared journal index lock instead of failing immediately when background collection holds it briefly. Owner locks remain nonblocking while the index is held, preserving lock ordering and account-level exclusion.

## Alternatives and consequences

Keeping per-path synchronization misses unknown additions. Resetting the worktree loses unfinished edits. Automatically following remote main changes the user's working context without an explicit operation. Whole-workspace synchronization is explicit and recoverable, but preparation and acceptance can be partial and may require manual resolution of binary or file-type conflicts. Raising the command timeout merely moves the failure point for a sufficiently large change set. Retained file baselines remain host-owned write preconditions; their runtime representation is consolidated by the linked mutable-copy decision.

## Evidence and related decisions

`WorkspaceSynchronizationTests` and `WorkspaceSyncInputsTests` pin the shared inventory and reconciliation rules. `NativeSynchronizationTests` pins explicit acceptance, retained candidates and resumable preparation. The [native workspace probe](../../executor-native/overlay_copy_probe.py) exercises preparation and acceptance with the released service. The [earlier workspace sync evidence](../../acceptance/clients/evidence/2026-09-15-workspace-sync.json) remains evidence for the retired signed-worker installation path.

[Mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md) own the runtime baseline state and HEAD/index advancement. [Account working copies](2026-09-14-account-working-copies.md) own account identity, disk quotas and expiry. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns selected remote saves and publication boundaries.
