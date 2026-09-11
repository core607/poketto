# Executor Working-Copy Identity

Date: 2026-09-12

## Decision

Every `repo_exec` request includes `expectedCopyId`. The literal `new` explicitly admits a fresh copy only when this MCP transport does not already own one. Other values must match the opaque UUID returned as `copyId` by an earlier command result. `poketto status` returns the same ID. The ID is separate from the pinned Git commit, worker lease and transport session.

This is the admission guard in [executor work continuity](../proposed/2026-09-12-executor-work-continuity.md), not its durable-recovery implementation. It extends the [CodeAct entrance](2026-09-10-codeact-mcp-entrance.md). Existing expiry, cancellation and worker-loss behavior can still discard unsaved work. A normal nonzero exit returns the copy ID, so the caller can continue using that copy; it does not acknowledge a remote save or promise recovery after closure.

## Failure and authority boundaries

The adapter checks identity before capacity admission and again after selecting or creating the copy. A mismatch never exports a replacement bundle or executes the rejected command. Explicit `new` cannot replace a live copy. It can retire a closed entry in the same transport only after the worker confirms lease release and the previous command relinquishes ownership. This creates a different copy; it does not recover unsaved work. Concurrent initial requests cannot both claim a newly admitted copy. A matching ID still requires current authorization, a live session and the existing command lock.

`SESSION_REPLACED` responses carry `executed: false`, `recoveryAvailable: false` and a bounded reason:

- `MISSING_COPY`: this transport has no matching copy; `newCopyAllowed: true` permits intentional initial admission, subject to ordinary authorization and resource limits.
- `DIFFERENT_COPY`: the transport owns another live copy; its authorized `copyId` is returned, with `newCopyAllowed: false`. The caller must deliberately select that copy or reconnect to create an independent one.
- `CLOSED_COPY`: the transport's copy is closed or closing; no available ID is returned. `newCopyAllowed` becomes true only after lease release and command exit are confirmed. The caller may then explicitly start a new copy in the same transport. A missing close acknowledgement keeps admission closed; merely dropping the application entry cannot establish worker cleanup.

The server resolves principal, workspace and MCP session before lookup. It never searches for or claims copies by principal and workspace alone. Two chats under one principal retain distinct copies. A returned available ID belongs only to the current transport and permitted scope; revoked access or public withdrawal prevents disclosure. IDs are not bearer credentials and do not grant recovery or access through another transport.

Worker transport loss may leave an earlier operation indeterminate. Such failures retain their unavailable/unknown-outcome behavior; only a rejection known to precede execution reports `executed: false`. Clients must preserve the expected ID across reconnects and must not replay an uncertain write or treat `new` as an automatic retry policy. Omitted or malformed IDs are invalid input; there is no unguarded compatibility path.

## Observability

The configured Micrometer registry receives active session and operation gauges, created/released session counters, and admission-rejection counters with fixed reasons `copy_mismatch`, `session_limit`, `operation_limit` and `session_busy`. Session creation counts admission attempts, including initialization failures; release counts confirmed capacity release, including worker restart reconciliation. An unconfirmed close keeps its capacity charged.

Metrics contain no identity, workspace, command, file path or content labels. They do not measure slice memory, disk allocation, command latency or durable recovery. Management HTTP exposure remains separately configured; this change adds no public management route. Resource tuning and fair scheduling remain in the continuity proposal.

## Verification and alternatives

[Adapter tests](../../src/test/java/io/github/core607/poketto/executor/internal/WorkerSocketTests.java) exercise real Unix frames with a synthetic worker peer. They cover idle-close/reconnect at an unchanged commit, refusal before OPEN/EXEC, two chats under one identity, foreign workspace/principal IDs, permission loss, concurrent initial calls and metrics. [MCP handler tests](../../src/test/java/io/github/core607/poketto/mcp/internal/McpCopyIdentityTests.java) cover the required schema field, explicit admission, normal nonzero results, structured replacement errors and the distinction from unknown execution outcomes. The [native probe](../../src/test/java/io/github/core607/poketto/executor/internal/ExecutorNativeProbe.java) verifies separate local files and rejected sentinel writes through actual worker/SRT execution; it simulates the trusted idle-close event rather than waiting for a real chat client's idle timer. External-client acceptance remains separate.

A response-only `fresh` flag arrives after a potentially harmful command. Comparing only commits misses fresh clones at the same revision. Automatically selecting another copy by identity and workspace merges independent chats. Those alternatives do not provide the execution precondition. The required request field intentionally changes the development API; callers must retain the returned ID. Durable work directories, explicit recovery and pending-write retention remain proposed.

[Native closure evidence](../../executor-native/evidence/2026-09-12-copy-restart.json) records 35 passing Java/SRT scenarios, process-loss lease expiry and cleanup, with verified runtime and worker hashes. It includes explicit new admission in the same transport after confirmed cancellation, a different copy ID at the same commit, and rejected old-ID writes. Adapter tests additionally hold the old command open after worker closure and deny replacement until it relinquishes ownership; a missing close reply also prevents replacement. The receipt states the synthetic-authentication and injected-idle boundaries. [Earlier combined evidence](../../executor-native/evidence/2026-09-12-copy-identity.json) retains the initial identity and usability baseline.
