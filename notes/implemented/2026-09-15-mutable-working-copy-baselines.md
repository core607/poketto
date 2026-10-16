# Mutable Working Copy Baselines

Date: 2026-09-15

## Problem

Account-owned disk copies outlive their initial Git export. The original selected-save implementation advanced the authoritative repository and per-file journal while leaving sandbox HEAD and index at the initial export, so saved files stayed dirty and local Git history omitted acknowledged saves. Single-file synchronization could not discover new remote paths. The worker also retained archived checkpoint transfer after disk attachment replaced it.

## Decision

[Whole-workspace synchronization](2026-09-15-workspace-synchronization.md) owns explicit reconciliation and its interruption recovery. Runtime baseline state has one path-to-file map; each file includes its authoritative commit and content state.

A full-read copy represents a mutable Git working tree. An acknowledged save or move installs its confirmed authoritative commit as the local Git baseline before the CLI reports success, preserving unrelated local edits. The host exports an incremental bundle relative to the installed baseline. The signed worker verifies its size and digest, freezes the lease's sandbox unit and runs Git through SRT as the execution account. Git imports the objects and resets HEAD and the index without changing working files. Existing local commit objects remain; distinct staged content is stored in a stash before the index is reset. A missing prerequisite permits one full-bundle retry of the local installation, never another remote write.

The original export commit remains immutable lease provenance and the baseline for unchanged files and pending-write reconciliation; it does not pin local HEAD. `repo_exec.commit` reports the installed Git baseline, and `poketto status` distinguishes the acknowledged `baseCommit`, the installed `gitCommit` and `localBaselinePending`. Sandbox Git state is untrusted and never authorizes a remote write: save preconditions come from authoritative revisions, never from command-modified Git metadata.

Explicit synchronization reconciles the current confirmed baseline, local files and one authoritative remote revision, including newly added and removed paths. It preserves local edits and reports conflicts. It never pulls in the background or resets dirty work to follow remote main. Exact-text editing and the final authoritative save conflict check remain required.

A remote success followed by a failed local installation is a pending local update, not permission to publish again. The CLI reports `LOCAL_BASELINE_PENDING`, the authoritative save receipt is retained, and `poketto recover` completes the installation. A contained helper failure preserves the parent command and lease, and later inspection does not retry installation on every call. Parent cancellation still stops the helper, and unconfirmed helper containment fences the lease before another writer can attach. Temporary bundles and pending markers are removed on completion or failure, and attachment clears staging left by a worker crash. Public-only copies remain sanitized projections: they cannot invoke baseline installation or receive private Git objects, author metadata or withdrawn content through synchronization, recovery or a permission increase.

The worker serves only quota-backed disk copies; it has no CHECKPOINT, CHECKPOINT_ACTIVE, CHECKPOINT_REMOVE or RESTORE operation and no archived worktree storage. Account journals, original-file baselines, disk quotas, containment, revocation, pending-write receipts and disk recovery remain. Historical evidence retains its original hashes and claims.

## State ownership

The host collects original retained paths and acknowledged file versions before comparing them with one fixed remote commit. Combined path count, text bytes and traversal duration are bounded before changing local files. Non-text presence and diagnostics remain in that inventory; unreadable, oversized or indexed-media paths are not deletions. An unchanged complete immutable file snapshot requires no separate Git blob inspection.

The local application step handles additions, deletions and conflicting edits across the workspace and explicitly reports interrupted installation. The disk record's commit index is derived from complete file baselines at serialization and checked against them on restoration; incomplete or mismatched retained versions fail restoration.

Every save and move uses the same candidate-publication callback and complete acknowledged file baselines. The original-file archive remains the authority for paths not yet advanced by save, move or sync. It is distinct from acknowledged per-file versions.

The acknowledged versions are stored beside the account journal in one framed file named after its payload digest ([AccountFileBaselines](../../src/main/java/io/github/core607/poketto/executor/internal/AccountFileBaselines.java)). The journal ([StoredAccountCopy](../../src/main/java/io/github/core607/poketto/executor/internal/StoredAccountCopy.java)) references the digest, and the per-path commit index is derived from the versions on restoration. Every command writes the journal at least twice, so embedding the versions made each call parse, hash and fsync the full text of every advanced file. In production on 2026-10-16 that was a 1.3 MB journal for 499 paths and about half of the application CPU of a trivial `repo_exec`. A journal write that keeps the versions keeps their file. A write that changes them publishes the new file durably before the journal can reference it, then deletes the superseded file; a failed deletion is logged and retried by the next change or removal rather than making the durable write uncertain. A removal deletes the journal first, and the next admission of that owner clears unreferenced version files. Reads require the referenced digest and copy ID. Because a version file is immutable, it is cached in memory by name.

## Alternatives and consequences

Keeping separate original and per-path baselines while only relabeling tool output would leave local Git misleading. Replacing the entire Git directory would discard local references and staged-only work. Automatically resetting the worktree would lose edits. Trusting the sandbox's own refs would let arbitrary commands change remote-write preconditions. Running Git in the privileged supervisor would expose host authority to command-modified configuration; incremental import within SRT keeps the isolation boundary and avoids copying complete history on every save. The design keeps authoritative baseline installation separate from user edits and preserves recovery when either side fails.

Referencing each version by its Git blob in the workspace cache instead of storing its text would make baselines depend on a disposable cache that garbage collection may prune, which this note's stored versions exist to avoid. One content-addressed file per path would multiply small writes and directory entries on every save. Keeping the versions inline and caching the parsed journal would still rewrite the full text on every journal write.

Application and worker versions must be coordinated: the application requires `gitBaselineProtocol: 1` from the worker's HELLO.

## Verification

`RetainedBaselineFilesTests`, `RetainedFileBaselineTests` and `WorkspaceSynchronizationTests` pin the baseline state and its restoration checks; `AccountFileBaselinesTests` pins digest naming, single storage of unchanged versions, superseded-file removal and refusal of another copy's or mismatched bytes; the worker's `native_probe.py --baseline-only` pins helper timeout, parent continuation and cancellation on real SRT. The [baseline-state evidence](../../acceptance/clients/evidence/2026-09-15-baseline-state.json) records consecutive saves through authenticated HTTP/MCP.

Related: [account working copies](2026-09-14-account-working-copies.md) owns account identity, disk quotas and expiry; [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns selected remote saves and publication boundaries.
