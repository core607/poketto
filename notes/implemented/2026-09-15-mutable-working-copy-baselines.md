# Mutable Working Copy Baselines

Date: 2026-09-15

## Problem

Account-owned disk copies outlive their initial Git export. The original selected-save implementation advanced the authoritative repository and per-file journal while leaving sandbox HEAD and index at the initial export. Single-file synchronization could not discover new remote paths. The worker also retained archived checkpoint transfer after disk attachment replaced it.

## Decision

[Git baseline installation](2026-09-15-executor-git-baseline-installation.md) implements acknowledged Git advancement and removes the archive worker. [Whole-workspace synchronization](2026-09-15-workspace-synchronization.md) implements explicit reconciliation and interruption recovery. Runtime baseline state has one path-to-file map; each file includes its authoritative commit and content state.

A full-read copy represents a mutable Git working tree. An acknowledged save or move installs its confirmed authoritative commit as the local Git baseline while preserving unrelated local edits. The tool reports the installed current commit. The original commit remains only for provenance, unchanged-file baselines and pending-write reconciliation. Sandbox Git state is untrusted and never authorizes a remote write.

Explicit synchronization reconciles the current confirmed baseline, local files and one authoritative remote revision, including newly added and removed paths. It preserves local edits and reports conflicts. It never pulls in the background or resets dirty work to follow remote main. Exact-text editing and the final authoritative save conflict check remain required.

A remote success followed by a failed local installation is a pending local update, not permission to publish again. Retain the acknowledged commit and reconcile installation after interruption. Public-only copies remain sanitized projections: they cannot receive private Git objects, author metadata or withdrawn content through synchronization, recovery or a permission increase.

The worker's unused CHECKPOINT, CHECKPOINT_ACTIVE, CHECKPOINT_REMOVE and RESTORE protocol, archived worktree files and archive-only tests are removed. Account journals, original-file baselines, disk quotas, containment, revocation, pending-write receipts and disk recovery remain. Executable fixtures and installation source lists use the disk protocol; historical evidence retains its original hashes and claims.

## State ownership

The host collects original retained paths and acknowledged file versions before comparing them with one fixed remote commit. Combined path count, text bytes and traversal duration are bounded before changing local files. Non-text presence and diagnostics remain in that inventory; unreadable, oversized or indexed-media paths are not deletions. An unchanged complete immutable file snapshot requires no separate Git blob inspection.

The local application step handles additions, deletions and conflicting edits across the workspace and explicitly reports interrupted installation. Runtime state no longer independently updates a second path-to-commit map. The existing disk record's commit index is derived from complete file baselines at serialization and checked against them on restoration; existing durable copies therefore retain their data without a format conversion. Incomplete or mismatched retained versions still fail restoration.

Every save and move uses the same candidate-publication callback and complete acknowledged file baselines. The former journal/no-journal branches are removed; the empty callback serves initial descriptors and isolated fixtures without changing write or recovery semantics. The original-file archive remains the authority for paths not yet advanced by save, move or sync. It is distinct from acknowledged per-file versions and does not pin local HEAD.

## Alternatives and consequences

Keeping separate original and per-path baselines while only relabeling tool output would leave local Git misleading. Automatically resetting the worktree would lose edits. Trusting the sandbox's own refs would let arbitrary commands change remote-write preconditions. The chosen design must keep authoritative baseline installation separate from user edits and preserve recovery when either side fails.

## Verification

Real Git and the native disk worker cover consecutive saves, current HEAD and index, unrelated dirty files, explicit synchronization with new remote paths, conflict preservation, interruption recovery and private/public separation. Retired protocol operations are rejected; disk attachment survives lease and worker replacement. Storage checks cover complete retained baselines and mismatched state, and authenticated HTTP/MCP acceptance covers the application entrance. Synthetic accounts do not establish acceptance of an inaccessible external client.

## Same-topic audit

[Account working copies](2026-09-14-account-working-copies.md) continues to own account identity, disk quotas and seven-day expiry. This decision replaces its initial-export and per-file-only synchronization model. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) retains selected remote saves and publication boundaries. [Copy identity](2026-09-12-executor-copy-identity.md) retains replacement guards. The rejected [checkpoint proposal](../rejected/2026-09-12-executor-work-continuity.md) remains historical rationale; its archive protocol is not a compatibility requirement. No broader multi-user or UI work is completed by this change.
