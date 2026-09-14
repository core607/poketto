# Executor Git Baseline Installation

Date: 2026-09-15

## Problem

Selected saves advance remote main and the host's saved-file state, but the original implementation leaves sandbox HEAD and the index at the initial export. Saved files remain dirty and Git history omits acknowledged saves. The disk worker also retains unused archive-transfer operations from transport-owned copies.

## Decision

Acknowledged full-read saves and moves install their authoritative commit in local Git before the CLI reports success. The host exports an incremental bundle relative to the installed baseline. The signed worker verifies its size and digest, freezes an active command and runs Git through SRT as the execution account. Git imports the objects and resets HEAD and the index without changing working files. Existing local commit objects remain; distinct staged content is stored in a stash before resetting the index. A missing prerequisite permits one full-bundle retry of local installation, never another remote write.

The original export commit remains immutable lease provenance. `repo_exec.commit` reports the installed Git baseline. `poketto status` distinguishes the acknowledged `baseCommit`, installed `gitCommit` and `localBaselinePending`. A failed local installation retains the authoritative save receipt and returns `LOCAL_BASELINE_PENDING`; recovery completes the local update. Host save preconditions continue to come from authoritative revisions, not command-modified Git metadata. Public projections cannot invoke baseline installation or receive private Git objects.

The worker serves only quota-backed disk copies. CHECKPOINT, CHECKPOINT_ACTIVE, CHECKPOINT_REMOVE, RESTORE, archived tree storage and their dedicated tests are removed. Native fixtures use XFS copies and real attachment/restart paths. Account journals, original-file baselines, pending save/move receipts, containment and authorization remain. The empty transport-close listener is removed; cancellation, revocation, disposal and expiry retain their own owners. `SaveStateJournal` names the remaining host-state publication callback.

A signed EXECUTION_CAPACITY rejection occurs before command execution. The application clears that command's intent without recording an interruption, reports `EXECUTION_REFUSED` with `CAPACITY`, and closes the exhausted lease. The next call attaches the same disk copy with a fresh execution budget.

## Alternatives and consequences

Changing only tool output would leave Git misleading. Replacing the entire Git directory would discard local references and staged-only work. Running Git in the privileged supervisor would expose host authority to command-modified configuration. Incremental import within SRT preserves the existing isolation boundary and avoids copying complete history on every save.

Application and worker versions must be coordinated: the application requires `gitBaselineProtocol: 1`. The worker retains the existing disk protocol so it can be installed before the application. Whole-workspace synchronization and consolidation of per-file baselines remain in the [mutable baseline proposal](../proposed/2026-09-15-mutable-working-copy-baselines.md); this change does not claim either is complete.

## Verification

The authenticated HTTP fixture uses real Spring authentication, PostgreSQL, JGit and the native worker. Consecutive saves update HEAD, index and tool commit in the same command, preserve an unpublished local commit and distinct staged/working versions, retain an unselected binary draft, and match authoritative readback. Separate grants and a new MCP transport on every call continue the same copy. Public-scope isolation, revocation, timeout retention and disposal also pass. The disk-only native probe verifies quotas, process isolation, containment and worker restart recovery. Worker, socket, frame, MCP protocol and Linux storage checks cover the changed boundaries. These fixtures do not claim external chat-client acceptance.

## Same-topic audit

[Account working copies](2026-09-14-account-working-copies.md) retains account identity, quotas, expiry and durable host state. This record replaces its initial-export-only Git view. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) retains selected saves and publication permissions. [Copy identity](2026-09-12-executor-copy-identity.md) retains explicit replacement guards. The rejected [checkpoint proposal](../rejected/2026-09-12-executor-work-continuity.md) remains rationale, and historical evidence keeps its original hashes and scope. The broader multi-user and interface plans remain independently owned.
