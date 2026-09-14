# Mutable Working Copy Baselines

Date: 2026-09-15

## Problem

Account-owned disk copies outlive their initial Git export. Selected saves advance the authoritative repository and the host's per-file baseline journal, but leave the sandbox's HEAD and index at the initial export. Successful saves therefore remain dirty in `git status`, and `git log` omits them. Single-file synchronization also cannot discover new remote paths. The worker still implements archived checkpoint transfer despite the application using only disk attachment.

## Decision

[Git baseline installation](../implemented/2026-09-15-executor-git-baseline-installation.md) implements acknowledged Git advancement and removes the archive worker. Whole-workspace synchronization and per-file baseline consolidation below remain pending.

A full-read copy represents a mutable Git working tree. After an acknowledged save or move, install the confirmed authoritative commit as its local Git baseline while preserving unrelated local edits. Report the installed current commit to the caller. Keep the original commit only where provenance or pending-write reconciliation needs it. Sandbox Git state is untrusted and never authorizes a remote write.

Synchronize the complete workspace explicitly. Use the current confirmed baseline, current local files and one authoritative remote revision to preserve local edits and report conflicts. Include newly added and removed remote paths. Do not pull in the background or reset dirty work to follow remote main. Exact-text editing and the final authoritative save conflict check remain required.

A remote success followed by a failed local installation is a pending local update, not permission to publish again. Retain the acknowledged commit and reconcile installation after interruption. Public-only copies remain sanitized projections: they cannot receive private Git objects, author metadata or withdrawn content through synchronization, recovery or a permission increase.

Remove the worker's unused CHECKPOINT, CHECKPOINT_ACTIVE, CHECKPOINT_REMOVE and RESTORE protocol, archive files and archive-only tests. Current account journals, disk quotas, containment, revocation, pending-write receipts and actual disk recovery remain. Update executable fixtures and installation source lists with the code; historical evidence retains its original hashes and claims.

## Implementation boundaries

Collect the original retained paths and acknowledged per-file versions before comparing them with one fixed remote commit. Bound the combined path count, text bytes and traversal duration before changing local files. Preserve non-text presence and diagnostics in that inventory; unreadable, oversized or indexed-media paths cannot be interpreted as deletions.

The local application step must handle additions, deletions and conflicting edits across the workspace, retain non-text content, and report interrupted or partially installed work explicitly. Consolidate retained baseline state only with a corresponding recovery path; collecting the inventory alone does not implement workspace synchronization. The command entrance, local installation, interruption recovery, baseline consolidation and real-client acceptance remain required.

## Alternatives and consequences

Keeping separate original and per-path baselines while only relabeling tool output would leave local Git misleading. Automatically resetting the worktree would lose edits. Trusting the sandbox's own refs would let arbitrary commands change remote-write preconditions. The chosen design must keep authoritative baseline installation separate from user edits and preserve recovery when either side fails.

## Verification

Use real Git and the native disk worker to verify consecutive saves, current HEAD and index, unrelated dirty files, explicit synchronization with new remote paths, conflict preservation, interruption recovery and private/public separation. Confirm retired protocol operations are rejected and current disk attachment still survives lease and worker replacement. Run the owning worker and MCP checks; synthetic authentication is not external-client acceptance.

## Same-topic audit

[Account working copies](../implemented/2026-09-14-account-working-copies.md) continues to own account identity, disk quotas and seven-day expiry. This decision replaces its initial-export and per-file-only synchronization model. [CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md) retains selected remote saves and publication boundaries. [Copy identity](../implemented/2026-09-12-executor-copy-identity.md) retains replacement guards. The rejected [checkpoint proposal](../rejected/2026-09-12-executor-work-continuity.md) remains historical rationale; its archive protocol is not a compatibility requirement. No broader multi-user or UI work is completed by this change.
