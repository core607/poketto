# Executor Work Continuity

Date: 2026-09-12

## Contract and scope

Work retained by a successfully acknowledged command must be recoverable. An interrupted command must explicitly report that it may have partially completed.

Acknowledgement is distinct from exit code zero or a successful remote save. A normal command result with a nonzero exit code still covers its retained local work; recovery must not discard it merely because the command reported failure.

This proposal changes the disposable-session lifecycle described by [CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md) and the [worker reference](../../executor-service/README.md). It does not change remote Git authority: retained local edits are neither a save nor publication. Until the implementation and its failure tests land, expiry and restart can still discard unsaved work.

The broader [CodeAct workspace proposal](2026-09-09-codeact-workspaces.md) retains its tooling and isolation scope. [Session artifacts](../implemented/2026-09-10-session-artifacts.md) retain their separate access, expiry and byte-delivery contract. Neither record is retired by this proposal. [Multi-user workspaces](2026-09-11-multiuser-workspaces-and-discovery.md) retain membership, public authoring and discovery decisions.

## Problem

An MCP transport session can expire during a long conversation. Reconnection creates another executor copy at the same repository commit. A commit therefore cannot identify a working copy or establish that local edits survived. Network loss, application deployment and worker restart create similar ambiguity. Current session and command admission bounds protect resource use but do not preserve a user's work or distinguish capacity rejection from lost execution state.

## Working-copy identity

Expose an opaque working-copy ID independently of the MCP transport session and pinned Git commit. Mutating and read commands that continue a copy carry its expected ID. Resolve current authorization and compare that ID before starting the command or applying a host operation. A missing, replaced or inaccessible expected copy must not silently create a substitute and run the command: return `SESSION_REPLACED` without executing it, with an authorized recovery or explicit new-copy path. Do not disclose other identities' copies.

Initial admission must identify the new copy before dependent work. Subsequent responses expose its ID and execution outcome. A response-only `fresh` flag is insufficient because the command could already have written into an unintended copy. Client guidance must retain the ID across tool calls; tests must exercise the actual MCP schema and handler rather than only a Java entry point.

Recovery is explicit and bound to the original subject, workspace and currently permitted scope. Never auto-claim by subject and workspace alone: two chats using one identity own independent copies. A copy has one fenced writer lease. A stale transport or worker cannot continue writing after ownership transfers; simultaneous recovery attempts cannot both win. Permission revocation or public-projection withdrawal denies access immediately, regardless of retained bytes.

## Durable retained work

Use a durable working directory, or an atomically published recovery checkpoint at command completion. A success response follows durable publication of the retained state. Exit cleanup cannot provide the guarantee: abrupt termination may bypass it. Failed persistence returns an explicit failure rather than acknowledging recoverable work.

Retain the original pinned commit and its required Git objects, worktree contents including untracked and binary files, the media index, independent per-file save baselines, and pending save/move receipts needed to reconcile unknown remote outcomes. Restore these together to the same baseline; do not reconstruct them against current remote `main`. Existing save/sync/recover conflict checks handle a remote that has advanced. Do not add a recovery-specific merge algorithm or automatically retry an uncertain remote write.

Persist command intent before execution and distinguish acknowledged completion from an interrupted or unconfirmed result. On recovery, an incomplete command reports possible partial local changes and any uncertain host write. Never infer failure solely from a missing response or claim a command did not execute. Host write reconciliation remains authoritative even when a process dies between a remote commit and its local acknowledgement.

Retained work has explicit byte and retention bounds, owner-visible expiry, and an explicit discard operation. Admission reserves storage before accepting work; quota exhaustion must preserve the last acknowledged recovery point. Unavailable or corrupt retained state fails visibly. Retention expiry and authorized deletion are stated exceptions to recovery, not silent replacement. Temporary process files remain disposable; artifact handles keep their independently documented lifetime.

## Resource and admission changes

Move retained work off aggregate tmpfs into quota-controlled disk storage, leaving temporary command files bounded and disposable. Reserve host disk headroom and bound I/O as well as bytes. Apply resource accounting to each copy from its creation; moving a process into another cgroup does not transfer memory charged while populating its files.

Timeout and command resource exhaustion terminate the command group and preserve recoverable state when the worker can verify containment and storage integrity. Worker or pool failure follows the interrupted-command contract; it cannot report an intact live copy without verification. Cancellation stops execution; credential revocation additionally prevents recovery access. Cleanup must not erase the last acknowledged retained work as a side effect of releasing runtime capacity.

Use bounded, fair waiting with per-workspace or principal limits and a global resource ceiling. Cold copy preparation and warm commands need separate admission so bundle creation does not monopolize interactive execution. Capacity errors include stable reasons and bounded retry guidance. Idle runtime capacity can be released while durable work remains recoverable; never evict unsaved work merely to admit another client.

Reuse immutable export bundles by workspace, pinned commit, full/public view and projection version, with bounded storage, single-flight creation and active-reader lifetime protection. Revalidate scope and publication. Never share writable Git object inodes or authorize a public copy with a full-history cache entry.

## Delivery and acceptance

1. **Detect replacement before execution.** Add expected-copy identity, explicit initial admission and mismatch behavior, plus active-copy, admission/refusal and lifecycle metrics. Test transparent reconnection at an unchanged commit with unsaved content, two parallel chats under one identity, wrong-scope IDs, and a rejected command that provably did not execute. Until durable recovery ships, report lost/unavailable copies honestly.
2. **Preserve acknowledged work.** Implement retention and fenced recovery. Exercise idle expiry, network loss, application deployment, worker termination and interrupted commands on native Linux. Verify original commit, untracked binary bytes, independent file baselines and uncertain-write receipts after recovery. Advance remote Git before recovery and prove that existing conflict handling is used. Kill the process before and after persistence/response boundaries; test full disk, checkpoint failure, revoked access and concurrent recovery.
3. **Make capacity usable.** Add disk quotas, per-copy resource containment, fair admission and bundle reuse. Measure memory, disk, queue age, rejected admissions, command duration and recovery outcomes without content, credentials or unbounded identity labels. Verify that one saturated copy cannot erase another's acknowledged work; burst reconnections must remain bounded and provide a determinate outcome.
4. **Strengthen and measure isolation.** Validate independent OS identities with persistent ownership and UID reuse accounted for. Extend cross-copy access tests through recovery and identity reuse. Measure warm-command and cold-copy cost before adopting persistent sandboxes or a different history protocol. Existing export byte, object, commit and time bounds remain in force.

An independent refinement change can add `poketto media link`, bounded error reasons, an optional 64 KiB artifact page limit in both schema and runtime, and tool descriptions for command cwd and temporary-directory reset. These do not depend on durable recovery and must not claim it is delivered.

## Alternatives and consequences

Increasing the global session count alone postpones rejection and raises resource consumption. Shorter idle expiry alone increases work loss. Automatically sharing one copy per identity and workspace couples unrelated chats. Packing a diff only during normal shutdown misses crashes, untracked binaries and host-write recovery state. Restoring onto current remote history changes the user's baseline and creates an implicit merge. These alternatives do not satisfy the contract.

Durable local work adds storage ownership, retention and recovery complexity. It is not an off-host backup guarantee, and independent executor hosting remains a deployment option rather than a prerequisite for the first two steps. Dynamic users require an ownership and UID-reuse design; enabling `DynamicUser` independently for each command is insufficient. Keep the existing deny-by-default sandbox boundary and measure any additional layer with real cross-session probes.
