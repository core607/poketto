# CodeAct Workspaces and Permission-Scoped Execution

Date: 2026-09-09
Implemented: 2026-09-15

## Problem

Agents worked the repository through fixed read and write tools, which left two content views (the tool projection and the repository) and made every edited byte travel through the model. The [execution service](../../executor-service/README.md) already provided isolated workspaces, so the agent's file workflow could move into the workspace: search, edit and inspect with ordinary tools, and hand only explicitly selected files back to the host for an authorized save. That workflow needed a permission-scoped export (a public-only key must never receive private files, metadata or history), a host-mediated bridge that keeps remote and storage credentials out of the sandbox, and a save contract in which a command exit or a local commit never implies a durable write.

## Decision

The notes linked below own their parts of this workflow.

**Admission and scope.** The application resolves the workspace, current identity, execution capability, content version and read scope before preparing any export; agent commands cannot choose a host path, raw repository source or broader scope. Execution stays authenticated and explicitly granted through `EXECUTE_REPOSITORY`; there is no anonymous executor and no visitor Q&A.

| Read scope | Export | Authority writes |
|---|---|---|
| Full repository | Exact authorized files and independently copied history covered by `READ_PRIVATE` | Require existing write and publication capabilities |
| Public only | Fresh files derived from the currently serviceable public document and media projection | Unavailable |

The public-only export is built from an empty destination by the [public execution projection](2026-09-09-public-execution-projection.md), never by cloning and pruning: no object database, commit messages, refs, reflogs, alternates or credentials are transferred, and a service-owned root guide replaces excluded guides. A session keeps the scope selected when it opened; a permission downgrade or publication withdrawal terminates affected sessions and denies further output and bridge access, revalidated at admission, dispatch, output delivery and every bridge operation. A full workspace is never downgraded by deleting files in place, and delivered content cannot be recalled. [Account working copies](2026-09-14-account-working-copies.md) own copy ownership, quotas, expiry and identity.

**Execution and the service bridge.** `repo_exec` is the composition entrance ([CodeAct MCP entrance](2026-09-10-codeact-mcp-entrance.md)): ordinary search, shell, Python and Git in the declared [toolkit](2026-09-05-local-execution-supervisor.md#sandbox-toolkit) operate on the same session files; there is no parallel model-facing filesystem API. The `poketto` CLI is a bounded capability bridge over a supervisor-owned request FIFO and read-only reply directory per lease, with 512 KiB frames, four pending requests and 256 request identities per command; the host claims each request once and publishes complete replies by atomic rename, so no-network execution, resource admission and process-tree cancellation stay intact, and Unix socket creation stays denied. CLI calls wait for the host result; a lost reply reports an unknown outcome and never replays a write. Signed host polling and completion stay responsive while the command runs, because polling does not hold the command's operation lock. Images and long output return through the narrow channel of [session artifacts](2026-09-10-session-artifacts.md).

**Durable authoring.** A save collects explicitly selected paths and deletion intent from a stable capture of the session files: the worker freezes the lease's sandbox unit, captures only selected UTF-8 files within the shared 64-file, 4 MiB bounds without following symlinks, and thaws it; the application verifies lengths and hashes, reads expected revisions from its own baseline, never from sandbox Git, and calls the shared atomic writer of the [repository authoring foundations](2026-09-05-repository-authoring-foundations.md). Success advances the host baseline without rewriting unselected edits; a conflicting remote ref leaves the changes for reconciliation; an uncertain acknowledgement retains the exact commit bytes and blocks another save until `poketto recover` acknowledges the existing commit or retries that same commit from the original base. `poketto sync` reconciles the workspace against remote with per-file baselines and conflict markers ([whole-workspace synchronization](2026-09-15-workspace-synchronization.md)); acknowledged saves install their commit in the copy's local Git ([mutable working copy baselines](2026-09-15-mutable-working-copy-baselines.md)). `poketto media fetch` and `poketto media import` move originals through the host with exact-byte validation and idempotency keys, [atomic moves](2026-09-09-atomic-content-moves.md) use host-owned plans, and [portable exports](2026-09-10-portable-content-exports.md) package content through the host. Public-only sessions cannot save or upload. Unsaved work stays in the account's disk copy across restarts and lease closure; only explicit disposal or the idle expiry owned by [account working copies](2026-09-14-account-working-copies.md) removes it.

**MCP cutover.** Execution became the agent file workflow and the standalone read, list and patch entrances were removed without a compatibility alias; the browser APIs and their shared services stayed. The worker is therefore a prerequisite for the agent workflow, and its cold-start and resident cost are part of the executor's acceptance rather than a second agent CRUD architecture.

**Not adopted: automatic root-guide return.** The proposal had the full-workspace bootstrap return the authorized root `AGENTS.md` to the agent. That was not built. Guidance is file-based: the `repo_exec` description tells the agent to read the root guide and `poketto --help`, nested guides are discoverable files, and [repository initialization on connection](2026-09-16-repository-initialization-on-connection.md) makes sure a connected repository has that guide. Injecting guide contents would have the service read repository text on the agent's behalf and would need a separate rule for restricted exports; reading a file the agent can already see needs neither.

## Alternatives

**Keep the fixed read and write tools.** Avoids worker startup for small requests, but leaves two content views and makes the model carry every edited byte. Rejected.

**Wrap the same CRUD calls in a code interface.** Preserves the duplication under a different surface. Rejected.

**Remote credentials inside the sandbox.** Would bypass the service's authorization and concurrency boundary. Rejected; the bridge is the only path back to authority.

**Full clone followed by filtering for public readers.** Exposes original history through Git. Rejected; the projection is built from publication.

## Consequences

An agent's edits live in a retained copy and become authoritative only through a selected save, so an unsaved copy is a draft with a lifetime, not durable state. Every save, move, sync and media operation is reauthorized by the same domain services the browser uses, and a lost reply can only be reconciled, never replayed. The public-only path serves a derived representation that may differ in layout from the repository (`~`-prefixed route folders, `_media` aliases), which agents reading it must not equate with repository paths. This record does not add per-file ACLs, choose a new publication format, implement a folder picker or enable public arbitrary execution.

## Implementation and acceptance

The worker advertises `codeActProtocol: 1`, and the application rejects incompatible readiness before exporting files. `executorServiceTests` (the worker's `test_bridge.py` and `test_session_files.py`) and `WorkerSocketTests` pin the bridge bounds and frozen capture. The [native bridge evidence](../../executor-native/evidence/2026-09-10-public-projection.json) and the [real-client workflow](../../acceptance/clients/evidence/2026-09-10-codeact.json) record the CLI through Java, the root worker and SRT, and through Codex and Claude Code over authenticated HTTP MCP.

Related: [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns the content format, media ownership, history and export contracts; [repository authoring foundations](2026-09-05-repository-authoring-foundations.md) own shared writes, visibility and media; the [local execution supervisor](2026-09-05-local-execution-supervisor.md) owns transport, resource and lifecycle guarantees.
