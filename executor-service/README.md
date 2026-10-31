# Local execution service

This worker supplies the Linux process boundary: Spring authorizes principals and
exports credential-free Git bundles; signed leases start bubblewrap under a separate
unprivileged account. It accepts no caller-specified host paths and never opens
application repositories. The [supervisor decision](../notes/implemented/2026-09-05-local-execution-supervisor.md)
owns topology, rationale and alternatives.

The [offline native import](native-migration.md) preserves retained work and home for explicit operator cutover.

## Runtime

The host needs x64 Linux with cgroup v2, systemd, capability-bearing unprivileged user namespaces, Python 3.10+, Git and the toolchain from [prepare-tools.sh](prepare-tools.sh): checksum-verified Node.js 22.22.0, the package pinned by [sandbox-runtime/package-lock.json](sandbox-runtime/package-lock.json), and distribution bubblewrap, socat, ripgrep and `which`. The Rust launcher, `poketto-launch`, uses bubblewrap 0.6.1+ and the SHA-256-pinned static `apply-seccomp` from SRT 0.0.75; Node.js remains available to commands and the game runtime. The SRT CLI and its proxies are outside the execution path.

Supervisor startup and every launch verify both isolation tools. Binaries and every parent directory must be root-owned, nonsymlink and unwritable by other accounts; `apply-seccomp` must match the source pin. Invalid tools prevent startup or execution, including during automatic deployment. Cleanup remains available. [Direct sandbox launch](../notes/implemented/2026-10-18-direct-sandbox-launch.md) owns the isolation and upgrade contract.

The static Rust `poketto-supervisor` runs from [poketto-executor.service](poketto-executor.service). `./gradlew stageExecutorWorker` stages runtime and test dependencies under `build/executor-service/worker`. Install it beside `poketto-launch` and `resource_pool.py`; sandbox `bin/` contains only `poketto`, `poketto-shell` and `poketto-syncfs`. Code, tools, configuration and public key are root-owned and unwritable by either unprivileged account. Only the application holds the Ed25519 private key. Never commit keys or operator configuration.

The baseline installer uses standard-library Python; [requirements.txt](requirements.txt) is for test clients. The supervisor embeds no interpreter ([decision](../notes/implemented/2026-10-21-rust-repository-supervisor.md)).

Package Python sources with LF endings; CRLF breaks executable shebangs. Gradle builds the [pinned Rust workspace](rust/Cargo.toml) through Docker. The updater delivers sources and programs together. Each repository sandbox mounts the selected release's executable-only `bin/` read-only, sharing file pages. Record revision and hashes, verify installed files and run `poketto --help` through the deployed connector after restart.

Before exporting content or opening a lease, HELLO requires `codeActProtocol: 3` for execution-bound polls and snapshots; `artifactProtocol`, `moveProtocol`, `exportProtocol`, `diskCopyProtocol`, `gitBaselineProtocol`, `workspaceSyncProtocol`, `leaseSandboxProtocol` and `seedProtocol` must equal 1. Other values reject execution. Install and restart the matching worker before deploying the application. A restart ends existing leases and their artifacts; account copies remain on disk.

Install [resource_pool.py](resource_pool.py) at `/opt/poketto-executor/resource_pool.py`, and the [slice](poketto-executor.slice) and service in `/etc/systemd/system/`. Files and parents must be root-owned and unwritable by application, execution and deployment accounts. Before startup, set finite `MemoryMax`, `MemorySwapMax`, `TasksMax` and `CPUQuota` in a private slice drop-in and run `systemctl daemon-reload`; checked-in values are examples. The slice bounds supervision, sandboxes and helpers, including copying, cache and temporary pages; unit limits add constraints and disk is bounded separately. Missing, unlimited or misplaced pools reject startup and OPEN/ATTACH/EXEC; startup cleanup, CLOSE, revocation and `--cleanup` remain available.

The deployment script runs the same read-only validation when execution is enabled, without reading private worker configuration; a missing helper or failed check stops deployment:

```sh
python3 /opt/poketto-executor/resource_pool.py --service poketto-executor.service
```

Provision the [content toolkit](../notes/implemented/2026-09-05-local-execution-supervisor.md#sandbox-toolkit) as root after preparing the tools directory, substituting its actual path:

```sh
sudo bash executor-service/install-sandbox-tools.sh /opt/poketto-executor/tools
```

This installs host distribution packages for Pillow, BeautifulSoup/lxml, pypdf, openpyxl, python-docx, `awk`, `zip`, Poppler (`pdftotext`, `pdfinfo`, `pdftoppm`) and Noto CJK fonts, and links `python` and `awk` inside the tools directory without changing host-wide commands. It runs no system upgrade and restarts no service; on an existing host, inspect `apt-get --simulate install` with its package list first. Afterwards validate through `repo_exec`, including native image and PDF operations.

[config.example.json](config.example.json) lists every setting; its UID/GID and numeric limits are examples, not production values. Startup enforces these rules:

| Setting | Rule |
|---|---|
| Paths | Absolute; only letters, digits, `_`, `.`, `/` and `-` |
| `runtimeRoot` | Dedicated root-owned directory; `socketPath` lies directly inside it |
| `exportRoot` | Holds only the application's atomic `<UUID>.bundle` exports, owned by `appUid` |
| `copyRoot` | Neither inside nor containing `runtimeRoot`; see [account copy storage](#account-copy-storage) |
| `appUid`, `appGid` | The only accepted `SO_PEERCRED` peer, and the group of the mode-0660 socket |
| `execUser` | Unprivileged, not the application account, not a member of `appGid` |
| `idleUnitSeconds` | Required, 1 through 86400 (example 300) |
| `maxLiveUnits` | Optional, 1 through `maxSessions`: live units, starting ones included; a new one first stops the least recently used idle unit, or is refused with `EXECUTION_CAPACITY` when all run commands |
| `maxStartingUnits` | 1 through `maxSessions` (example 1; omitted: `maxSessions`). Bounds command-unit startups. Admission before eviction refuses with `EXECUTION_CAPACITY` when full; the lease survives. Warm units bypass it. A shell greeting or confirmed containment releases the slot; unconfirmed cleanup retains it. |
| `renewAfterSeconds` | Less than `leaseSeconds` |
| `unitPrefix` | Matches `poketto-exec-[a-z0-9]+-` |
| `resourceSlice` | Required; the slice the worker must run in |

## Capacity budgets

`maxSessions` limits leases, `maxLiveUnits` retained units, Spring's `max-operations` command flows including preparation/cleanup, and `max-copies` stored copies. Ceilings reserve nothing. Size `maxStartingUnits` with a guarded ramp; excess retained units evict idle units.

Budget slice tasks jointly:

```text
pool tasks >= service budget + L*b + C*(t-b) + transient headroom
```

`L` counts retained units, `b` their measured idle tasks excluding relays, `C` active units, and `t` their task budget. Count each thread. The service includes main threads, one retained `systemd-run --pipe --wait` relay per unit, connection threads and helpers. Set `maxConnections >= 3*max-operations` for EXEC/poll/renewal, with service headroom beyond connections and relays. Parent limits also constrain helpers. Background processes can exceed the workload-dependent idle baseline after commands finish.

Idle-shell measurements omit workload costs. The slice includes supervision, relays, sandboxes, cache and kernel charges; count it once in host budgets, leaving room for other services and the OS. Attribute `memory.current` with process PSS and `memory.stat`, not Rust RSS alone.

`OOMScoreAdjust=-900` biases selection away from supervision and inherited helpers, without memory-limit immunity. Independent sandbox units have their own settings. `OOMPolicy=stop` ends leases and cleans runtime state after a service-member OOM; copies survive. Keep parent `memory.oom.group=0` for victim selection between units. Verify parent and service-local OOM separately.

After startup cleanup and before listening, the supervisor raises `RLIMIT_NOFILE`'s soft limit to its existing hard limit. Bridge and command waits support descriptor numbers above 1023. Size the service's `LimitNOFILE` hard limit for leases, retained artifacts and active connections; cgroup memory and configured capacities still bound resource use.

## Native service dependency

Deployment verifies [independent Sandcrab](../deploy/README.md#native-service-dependency). Installation stays operator-owned.

## Account copy storage

The application keeps a default disk copy per account, content workspace and reading scope, plus forks opened while it is busy; OAuth grants create no copies. [Account working copies](../notes/implemented/2026-09-14-account-working-copies.md) owns the storage and lifecycle decisions.

`copyRoot` must be a dedicated root-owned XFS mount with enforced project quotas and a total size no greater than `poolBytes`; the worker refuses any other mount and has no tmpfs fallback. A preallocated loopback filesystem provides that bound without remounting the host root. Place exports, copies, original archives and account metadata on it, and provision the mount and the application-owned export and metadata directories before starting either service.

Each copy receives inherited project hard limits `diskBytes` and `diskInodes` before command access; initial limits are 4 GiB per copy and 32 GiB for the pool. Set `poketto.executor.copies.metadata-root` to the application's private metadata directory in this pool. The other `poketto.executor.copies` defaults are `max-copies=128`, `max-record-bytes=16777216`, `pool-bytes=34359738368`, `idle-seconds=604800`, `original-bytes=268435456`, `original-expanded-bytes=1073741824` and `original-entries=100000`; validate them against the host's capacity. Stored-copy count is separate from active leases and command concurrency.

Each copy retains a private journal of ownership, pinned original commit, pending remote-write receipts and the digest of per-file baselines stored once beside it, plus a checksummed immutable original archive. Public copies carry only their host-owned projection proof. Publication requires file fsync, atomic rename and directory fsync before acknowledgment. Commands hold the account writer lock, record intent before EXEC and host-write state before acknowledging writes, and flush before success. Reconnection fences the prior writer and reattaches files and baselines; incomplete commands remain potentially partial. Copies survive transport loss, lease closure, timeout and restarts.

A copy initialized from an export is reflinked, before any command runs, into the supervisor-private seed `copyRoot/seeds/<workspace>/<scope>/<commit>`, which is never mounted. A later full copy at that commit is reflinked from it without an export or clone; the two newest seeds per workspace and scope, and any opened in the last ten minutes, are kept ([seed copies](../notes/implemented/2026-10-18-seed-copies.md)).

Successful authorized operations renew a seven-day idle deadline. A collector removes expired copies and incomplete initializations after containment, deferring to active writers. Disposal intent is durable, so an interrupted deletion resumes and never makes the copy executable again. Cleanup never undoes remote Git commits, and there is no off-host backup.

For full-read copies, an acknowledged save or move installs its authoritative commit as local HEAD and index before the CLI reports success, keeping working files and local commits and stashing distinct staged content. Public copies never receive this update. Save preconditions come only from the application's authoritative baseline, never from sandbox Git state. If local installation fails, the CLI reports `LOCAL_BASELINE_PENDING`, `poketto status` shows `baseCommit`, `gitCommit` and `localBaselinePending`, and `poketto recover` completes it without repeating the remote write. A reopened lease retries a pending installation once. A contained helper failure keeps the lease usable, unconfirmed helper containment fences it, and temporary baseline files are removed after failures and on attachment ([mutable working copy baselines](../notes/implemented/2026-09-15-mutable-working-copy-baselines.md)).

## Process boundary

Git initialization and commands use `execUser`, bubblewrap and pinned seccomp, without fallback. Startup stays in a root-owned bootstrap until isolated, excluding repository Git/shell configuration. Every mode has an empty root bound only to permitted reads/writes, network/user/PID namespaces, dropped capabilities and seccomp. Proxy variables are absent; `TMPDIR=/tmp` names the unit's private tmpfs.

One unit starts lazily per lease, retaining shell state, directory, environment, `/tmp` and background processes ([contract](../notes/implemented/2026-09-16-per-lease-command-sandboxes.md)); units never cross leases. systemd bounds memory, swap, CPU, tasks and temporary storage including background work; XFS bounds repository storage. Timeout, output limit, shell exit, `idleUnitSeconds` or `maxLiveUnits` eviction stops it. Containment requires confirmed empty cgroups, not exit codes; the next command starts with `freshSandbox: true`. The driver discards post-command background output, retaining at most 128 readers and closing the oldest beyond that bound; later writes receive `EPIPE`/`SIGPIPE`.

The worker captures at most 4 MiB of combined output, stopping the unit beyond it, and returns a preview of at most 16 KiB per stream with malformed UTF-8 replaced. Longer streams also return immutable artifact handles: an artifact's `truncated` means its capture is incomplete, `stdoutTruncated` and `stderrTruncated` describe the previews, and per-stream failures appear in `artifactErrors`. The framed response has a separate 1 MiB bound. Leases closed by resource limits, cancellation or revocation cannot deliver handles; their long output reports `ARTIFACT_UNAVAILABLE`.

`maxRequests` and `maxExecutionsPerSession` bound replay state. A connection beyond `maxConnections` is closed unread, like a lost reply. `EXECUTION_CAPACITY` means nothing executed. Its `STARTUP_LIMIT` reason confirms an unexpired READY lease with the unused execution ID released: after acknowledging the refusal and rechecking authorization, Spring retains it. Other refusals close the lease; the next call reattaches the copy. A full replay table rejects work until signatures expire. Failed renewal ends execution authority.

## Working-copy identity

`repo_exec` requires `expectedCopyId`. `"new"` opens the account's default copy for the grant's reading scope, creating it only if absent. While that copy is busy or recently used, `"new"` opens a fork within the account's quota (`poketto.executor.account-copies.*`), else waits. Afterwards pass the returned `copyId`. A matching Git commit does not prove copy survival; transports do not own copies; ID mismatches are refused before execution.

```json
{"expectedCopyId":"new","command":"pwd"}
```

A copy keeps its reading scope when a grant gains private-read permission, and a public-only grant cannot select a full copy. Reconnection is automatic and needs no generation or resume input. Results contain `retention.expiresAt` in epoch milliseconds, `retention.resumed` and nullable `retention.lastInterruptedCommand`. A copy keeps its acknowledged baseline and local edits and is never rebuilt against current main because metadata is missing. Artifacts and `/tmp` expire sooner than the copy.

`SESSION_REPLACED` identifies missing or different copies. `EXECUTION_REFUSED` reports `executed: false`, recovery availability and a reason: `RECOVERY_REQUIRED`, `BUSY`, `CAPACITY`, `MISSING_COPY`, `EXPIRED`, `UNAVAILABLE` or `PUBLICATION_CHANGED`. `copyId`, when known, names the retained copy even after `new`; retries name it. `PUBLICATION_CHANGED` keeps a public copy with local changes after publication changes ([public execution projection](../notes/implemented/2026-09-09-public-execution-projection.md)). Refusals describe this request, not earlier commands. `EXECUTION_UNCONFIRMED` returns the copy ID and expiry when execution may be partial. Never replay it: inspect that copy and `poketto status`, and reconcile uncertain remote saves with `poketto recover`.

`repo_discard` takes the exact copy ID and requires current execution permission and account ownership, but not content-read permission. A busy writer prevents deletion. `DISCARDED` and `ABSENT` confirm completion; retry an unconfirmed result only with the same ID. Remote Git commits remain unchanged.

Micrometer exposes `poketto.executor.sessions.active`, `poketto.executor.operations.active`, `poketto.executor.sessions.created`, `poketto.executor.sessions.released` and `poketto.executor.admission.rejected` with bounded reason tags. `POKETTO_METRICS_ENABLED` serves them at `/actuator/prometheus` ([names](../deploy/observability/README.md)); they omit memory and disk.

## Wire version 1

Each UNIX connection carries one HTTP/1.1 request and JSON response, then closes. Bodies declare `Content-Length`, at most 1,048,576 bytes; chunked bodies, pipelining and upgrades are refused. Separate connections allow renewal and cancellation while EXEC waits. The application must verify the root-owned socket and directory before connecting. Responses are unsigned on this authenticated local channel.

The only unsigned request is `GET /v1/hello`. It returns `ok`, `version`, the protocol markers listed under [runtime](#runtime), `workerBootId`, `maxFrameBytes`, `leaseSeconds` and `renewAfterSeconds`, and no path or key material. The worker takes its exclusive supervisor lock and completes startup cleanup before creating a boot ID, so a new boot ID at the same authenticated socket confirms that earlier leases were cleaned up. A failed HELLO or an unchanged boot ID confirms nothing.

All other requests are `POST /v1/requests` with the body `{"payload":"BASE64URL_RAW_JSON","signature":"BASE64URL_ED25519_SIGNATURE"}` without padding; operation results, refusals included, have status 200. Sign the decoded raw payload bytes, not a reserialized object, and reject duplicate JSON keys. Every payload contains exactly these fields:

| Field | Meaning |
|---|---|
| `version` | Integer 1 |
| `workerBootId`, `appBootId` | Worker and application process epochs, UUIDs |
| `operation`, `requestId` | Operation below and unique UUID |
| `issuedAt`, `expiresAt` | Integer Unix seconds; validity no longer than `leaseSeconds` |
| `principalId`, `accountId`, `workspaceId` | API key, owner account, and workspace UUIDs |
| `serverSessionHash` | Stable lowercase SHA-256 of the copy's reading scope; not an MCP transport identity |
| `leaseId` | Application-generated opaque UUID |
| `data` | Operation-specific object |

The worker rejects `data` with a missing or extra field; [java-frames.json](java-frames.json), written by `WorkerFrameContractTests`, holds one exact sample per operation.

| Operation | Behavior |
|---|---|
| `OPEN` | Creates copy `copyId` (`scope` `full` or `public`) at `commit` from export `exportId` after checking its size, at most `maxBundleBytes`, and SHA-256. Blocks until READY or failure within `initTimeoutMillis`, accepting RENEW meanwhile. With `seed: true` and no export, it is reflinked from the seed at `commit`, or refused with `SEED_ABSENT` before any lease exists. |
| `BASELINE` | Full-read copies only. Freezes the lease unit, idle or running, installs the bundle's commit as Git HEAD and index inside the sandbox, keeps working files and returns `gitCommit`. |
| `ATTACH` | Claims an existing copy for a new lease after checking account, workspace, scope, pinned commit and the exclusive copy lock. Never clones or replaces files. |
| `DISCARD` | Deletes an owner-matched copy once no lease holds its lock. Returns DISCARDED or ABSENT, or `COPY_BUSY`. |
| `EXEC` | Runs `command` (1 byte to 64 KiB of UTF-8, no NUL) within `timeoutMillis`, at most `maxTimeoutMillis`. Requires READY and the pinned `commit`; blocks until completion or containment. |
| `RENEW` | Extends an unexpired lease in any live state, UPDATING included, to `expiresAt`. Nothing else renews. |
| `BRIDGE_POLL` | Requires `executionId`; claims one CLI request only from that command. Returns null within the poll interval when idle or unmatched. Never takes the command lock or cancels on contention. |
| `BRIDGE_COMPLETE` | Publishes one bounded reply to that pending request of that running execution. |
| `SNAPSHOT` | Sent to `POST /v1/snapshots` and copies files while the sandbox is frozen: `kind` `text` takes up to 64 selected UTF-8 files and 4 MiB plus explicit deletions, `optional` one text file or its absence, `binary` one regular, single-link file of up to 128 MiB. Success is one ustar archive: `manifest.json` (lease fields, capture ID, ordered path/length/SHA-256 list), then `files/0`, `files/1` and so on. The capture is released once the archive is written or the reader disconnects; a refusal is JSON. |
| `MATERIALIZE_BEGIN` | Opens a transfer of up to 1 GiB to `path`, or a deletion, with precondition `expectedSha256` (null means absent); `allowIdentical` accepts an identical existing file. |
| `MATERIALIZE_CHUNK` | Appends at most 65536 decoded bytes at the exact next offset, staged outside sandbox-readable paths. |
| `MATERIALIZE_COMMIT` | Verifies length and hash, then with the cgroup frozen checks the precondition and atomically replaces or deletes the target. A repeat returns the retained receipt without touching newer edits. |
| `MATERIALIZE_ABORT` | Releases the transfer; never undoes an acknowledged installation. |
| `MOVE_BEGIN` | Opens a move-plan transfer of 1 byte to 64 MiB. |
| `MOVE_CHUNK` | Appends like `MATERIALIZE_CHUNK`. |
| `MOVE_CHECK` | Validates the plan and local preconditions with the cgroup frozen and reserves a completion receipt before Git changes. |
| `MOVE_COMMIT` | Rechecks preconditions while frozen, renames and installs repaired text. Failure rolls back; a failed rollback or receipt closes the lease. A repeat returns the receipt. |
| `MOVE_ABORT` | Releases the transfer and an unused receipt reservation; completed receipts stay until the lease closes. |
| `ARTIFACT_CREATE` | Freezes the running command and copies one regular, single-link file into protected storage. |
| `ARTIFACT_READ` | Returns up to 65536 bytes of an artifact from the lease's private map, also after its command completes. |
| `ARTIFACT_REMOVE` | Releases an artifact early; an unknown ID is harmless. |
| `CLOSE` | Optional `reason`: `cancelled`, `session_closed` or `client_shutdown`. Returns CLOSING until cleanup finishes; before OPEN it leaves a tombstone and returns CLOSED. |
| `REVOKE` | Closes leases of up to 1000 `keyIds` and 1000 `accountIds` in the payload workspace, tombstones first. Identity fields may be zero UUIDs and a zero hash. |

Captures freeze the cgroup, allow one per lease, and require the running execution's ID, or an empty one with an idle live unit; the next command or unit cleanup releases them. Materialize and move share one incoming slot per lease, require the running execution and end with command cleanup. Operations on a lease recheck its identity and, except CLOSE, current authority.

Success responses contain `ok: true`, `requestId`, `leaseId`, `state`, `commit` (the original export commit) and `gitCommit` (the installed baseline). DISCARD instead returns `copyId`, `commit` and `state`; REVOKE returns `state` (CLOSING or CLOSED) and `closedCount`; bridge responses add `executionId` and `bridgeRequest`. EXEC adds `result`:

```json
{"commit":"40_HEX","exitCode":0,"stdout":"","stderr":"","stdoutTruncated":false,"stderrTruncated":false,"timedOut":false,"freshSandbox":true,"terminationReason":"normal","artifacts":{},"artifactErrors":{}}
```

Errors contain `ok: false`, a fixed `code`, an optional bounded `reason`, and `requestId` when verified, never exception strings, source paths or command text. General codes are `INVALID_HTTP`, `INVALID_BODY`, `NOT_FOUND`, `PEER_REJECTED` (a peer other than the application), `INVALID_REQUEST`, `INVALID_SIGNATURE`, `WORKER_RESTARTED`, `LEASE_EXPIRED`, `AUTH_REVOKED`, `SESSION_NOT_FOUND`, `SESSION_EXISTS`, `SESSION_CLOSED`, `SESSION_BUSY`, `SESSION_COMMIT_MISMATCH`, `INVALID_EXPORT`, `INITIALIZATION_FAILED`, `EXECUTOR_FAILED`, `REPLAY_CONFLICT`, `REQUEST_IN_PROGRESS`, `EXECUTION_ALREADY_STARTED`, `REQUEST_CAPACITY`, `SESSION_CAPACITY`, `EXECUTION_CAPACITY` and `RESPONSE_LIMIT`; copy, capture, transfer and artifact operations add their own, such as `COPY_BUSY` and `MATERIALIZE_CAPACITY`.

Identical signed request bytes return their cached result while the signature is valid. A reused request ID with different bytes fails, as does a reused execution ID under a new request ID. An absent response never authorizes retrying the command: CLOSE the session and report an unknown result. Tombstones outlive every request signed before cancellation or revocation; fresh signatures after that boundary are Spring's authorization responsibility.

### Termination and cleanup

`terminationReason` is `normal`, `timeout`, `resource_limit`, `output_limit`, `cancelled`, `session_closed`, `client_shutdown`, `lease_expired`, `sandbox_failed` or `revoked`; `normal` may have a nonzero exit code. A timeout or output-limit stop keeps the lease, prior files and partial work once the unit's control group is confirmed empty. Failed containment closes the lease and keeps the copy locked against another lease; resource exhaustion and lifecycle cancellation also close the lease. Failed OPEN initialization is an operation error, never a ready session.

Poll CLOSE or REVOKE with fresh request IDs until CLOSED; a reused request ID returns its cached response. A signed CLOSE matching the session identity works after revocation so the application can confirm cleanup, and grants no execution or renewal. Repeated close requests keep the first cancellation reason.

## Sandbox CLI

Commands reach the host only through the shared static `poketto` program, which uses a lease-specific FIFO, writer lock and read-only reply directory; the pinned seccomp filter keeps Unix socket creation disabled. The application authorizes every request and rechecks the lease before replying. The supervisor changes the bridge epoch with the unit frozen, so requests from completed commands or idle background processes never reach the host; the execution ID each CLI process inherits grants no authority. The sandbox has no outbound network or access to client-local paths; clients send images through [MCP URL import or temporary raw upload](../docs/usage.md).

`poketto edit PATH --old TEXT --new TEXT` replaces one exact, unique match in an existing UTF-8 file; `poketto create PATH --text TEXT` creates an absent file. `--stdin`, `--text-file`, `--old-file`, `--new-stdin` and `--new-file` read long UTF-8 text with final newlines, resolving files from the shell's directory. Both install through frozen compare-and-replace, so an intervening local write fails, and neither changes the save baseline or remote Git. Failures return `EDIT_REJECTED` with `OLD_TEXT_NOT_FOUND`, `AMBIGUOUS_MATCH`, `ALREADY_EXISTS` or `LOCAL_FILE_CHANGED`. The 16,384-character `repo_exec` command and 512 KiB encoded bridge frame still bound the text; ordinary shell writes get none of these checks.

`poketto save PATH... --delete PATH` sends selections, not contents. The application captures them, checks authoritative revisions at its own baseline and commits through the shared atomic Git writer; success advances the save baseline and the local Git baseline while unselected edits stay local. Public-only sessions cannot save. A conflict keeps local files and the prior baseline, and an ambiguous write blocks later saves.

`poketto status` reports the copy ID, host baseline and last save receipt. On full-read copies it compares remote main with the last confirmed save or sync base: `remote.state` is `MATCHES_BASE` or `DIFFERS_FROM_BASE` with `remote.commit` (null when empty), or `UNAVAILABLE`. That comparison does not certify every file, and status changes nothing. Public copies report `PUBLIC_PROJECTION` and their synthetic commit without querying the private head.

`poketto recover` reconciles the exact retained commit with current remote history. An observed commit is acknowledged without another push; otherwise the same commit is retried only while the original remote base still matches. Recovery revalidates the patch and current authorization, keeps newer local edits, and reports divergence as a conflict. A further lost reply keeps the same attempt.

Acknowledged saves, syncs and moves record each affected file's version (text, non-text presence with its byte revision and diagnostics, or absence); other paths use the original archive, and unselected draft media mappings never become the index baseline. Later operations use these records, never historical caches, and check private-read permission before and after archive reads. A missing record or archive fails the operation instead of falling back or inferring absence. A text save onto an existing non-text file returns `NO_WRITABLE_BASELINE` before any write intent.

`poketto move prepare SOURCE DESTINATION` retains originals and a domain plan for saved Git files, indexed media and repaired Markdown references. Full read and write authority are required, with publication authority for public changes. Dirty affected files, changed selected media mappings and occupied targets are refused. Preparation never installs working files or commits remote Git. Plans retain the domain bounds of 16,384 paths and 32 MiB of replacement text.

Inspect candidates with `poketto plan show ID`, apply them with ordinary tools, then use `poketto move accept ID --expect PATH=SHA256 [--absent PATH]`. Expectations must equal retained candidates. Each accepted path is verified at that call; later local edits remain unsaved. Final acceptance requests the saved remote change set under current authorization and CAS. `remoteCommitted` is separate from `localVerified` and its per-path verification scope. Unrelated local index entries stay local. `poketto move recover ID` or `poketto recover` reconciles the exact retained attempt and finishes confirmed baselines without installing worktree files. Unknown outcomes and pending baseline retention prevent plan release; no new move is generated during recovery. [Usage](../docs/usage.md#mcp-and-isolated-execution) owns plan inspection and backup release.

`poketto sync` merges one fixed remote commit into the workspace, including new and deleted paths, and commits nothing. The host first inventories at most 16,384 paths and 32 MiB of text within 20 seconds. Text merges three ways with LOCAL/BASE/REMOTE markers; local-only files remain; unchanged local binaries can receive remote blobs up to 128 MiB. Conflicting binary edits, unsafe paths and unsupported changes stay in place as conflicts, and receipts list the total and at most 64 paths within 16 KiB, with `conflictsTruncated`. Each installation is journaled first. After interruption `poketto status` shows `syncPending`; `poketto recover` continues, preserving a different local edit with `LOCAL_SYNC_PENDING`, and `--skip-local` releases the rest without undoing installed paths. A run stops after at least one path once less than five seconds remain before the command timeout or the CLI's 55-second reply wait; it journals its progress and returns `SYNC_INCOMPLETE`, and `poketto sync` or `poketto recover` continues it. Completion, conflicts included, installs the new Git baseline; resolve conflicts before saving.

`poketto media list [--prefix PREFIX] [--offset N] [--limit N] [--index-version HASH] [--commit COMMIT]` lists logical paths, declared types and sizes; the originals may no longer exist. Full-read sessions read the local index, including unsaved imports, or a historical one; public sessions read only the admitted projection, never local edits or history. Pages are sorted by path within 12 KiB of JSON, 100 entries by default and 200 at most. Continue with `nextOffset` and `indexVersion` under the same prefix and commit; a changed index returns `MEDIA_INDEX_CHANGED`, and a null `nextOffset` means EOF. The prefix is literal, so end a directory with a slash. Historical reads share the original-read pool of four operations globally and two per workspace, returning `MEDIA_UNAVAILABLE` when saturated.

`poketto media fetch PATH [--commit COMMIT] [--output PATH]` materializes an indexed original into the worktree from the local index or an authorized historical commit; public sessions use only the projection's host-owned mapping and never see original commits. It shares original-read authorization and admission limits, keeps an identical local file, never overwrites a different one, and does not upload, commit or change the index.

`poketto media import FILE --as LOGICAL_PATH --key KEY [--type MIME] [--replace]` captures one regular file of up to 128 MiB with the cgroup frozen, charged to temporary storage, and stores an immutable original. Keys have 16–128 letters, digits, `_` or `-`; retry the same bytes and type with the same key. Only that entry of the local `.poketto/assets.json` changes; `--replace` replaces a different entry and keeps the old original. Missing tracked indexes and collisions with tracked Git files are rejected. If the index changed during upload, the original stays stored and the newer index is kept; `lastImport` in `poketto status` shows both steps. Import needs private write permission and neither commits nor publishes.

`poketto media link LOGICAL_PATH --asset ID --revision REV [--replace]` adds an existing original of this workspace to the local index without transferring bytes, with the same permissions, validation and compare-and-replace as import; repeating the same entry keeps the index bytes. Missing or foreign originals return `MEDIA_UNAVAILABLE` with `reason: NOT_FOUND`. Save the index with its referring text through `poketto save`.

`poketto export PATH... --output FILE [--public]` packages the latest saved documents and media as a ZIP; `.` selects the visible workspace. Full-read sessions default to private content, and `--public` requires public selections and dependencies; public-read sessions always export public content through the projection mapping. Selections expand to at most 128 source paths. Missing selections, internal guides and forged paths fail without revealing source coordinates. The verified ZIP is the only change, locally or remotely; a different existing destination returns `LOCAL_FILE_CHANGED` untouched, and an identical one may be reused. Results report path, scope, bytes and SHA-256. Failures are `EXPORT_CAPACITY`, `EXPORT_NOT_FOUND`, `EXPORT_UNAVAILABLE`, `INVALID_EXPORT_SELECTION` and `ACCESS_DENIED`. Transfer is capped at 1 GiB or lower server limits, within the command deadline and disk quota. Host packages are released after every attempt and at shutdown; cancellation never completes a stopped transfer.

Incoming files must fit the available space with 1 MiB to spare, checked again while streaming. `MATERIALIZE_CAPACITY` reports the shortfall without discarding existing files; free space, select fewer files, or use browser export. When media import stored the original but could not install the index, the receipt shows `originalStored: true` and `indexUpdated: false`; retry with the same bytes, type and key after freeing space. Other transfer or storage failures require session cleanup.

### Selection diagnostics

`INVALID_SELECTION` and `INVALID_MEDIA_REQUEST` include a stable `reason` for rejected local inputs. Reasons are `INVALID_ARGUMENTS`, `INVALID_PATH`, `SELECTION_LIMIT`, `PATH_COLLISION`, `NOT_FOUND`, `NOT_REGULAR_FILE`, `NOT_UTF8`, `TEXT_LIMIT`, `BINARY_LIMIT`, `FILE_CHANGED`, `CAPTURE_UNAVAILABLE`, or `NO_WRITABLE_BASELINE`. Missing selected files are not deletions; use an explicit `--delete`. Unsafe or unavailable captures may be indistinguishable and return `CAPTURE_UNAVAILABLE`. Diagnostics never include host exception text or storage paths.

### Returned artifacts

`poketto artifact create FILE [--type MIME]` returns an immutable snapshot of a repository-relative regular file, and `poketto artifact remove ID` releases it early. A lease retains at most 16 artifacts and 256 MiB, charged to its disk quota, with 128 MiB per file and a five-minute lifetime. Sandbox commands cannot read the protected copies, and closing or revoking the lease invalidates handles and deletes their storage.

`get_artifact` serves a handle to any MCP request of the same account, workspace and reading scope while the capturing lease stays open; a request under another credential of the account moves the copy to a new lease, ending the old lease's artifacts, and a public-only grant never reads a full copy's artifacts ([session artifacts](../notes/implemented/2026-09-10-session-artifacts.md)). Every read rechecks authorization, and public-scope reads recheck the publication. Its default `auto` format renders validated PNG, JPEG, GIF or WebP images up to 16 MiB in full, pages UTF-8 text, and returns other files, including SVG, as binary resource pages. Invalid raster bytes or a mismatched digest fail preview; `format=bytes` always returns binary pages. `offset` and `nextOffset` count bytes, `limit` is 4–65536 (8192 by default), and a null `nextOffset` means EOF. Image previews require offset zero. Artifacts never upload originals, write Git, publish or create URLs.

## Verification

Run the protocol and state tests on Linux with the pinned Python dependencies; `./gradlew executorServiceTests` runs them there, or in a pinned Linux container on Windows:

```sh
python -m unittest discover -s executor-service -v
```

The root-only probes below use synthetic, disposable fixtures, and each needs `cleanup: PASS`. [Client acceptance](../acceptance/clients/README.md) covers real accounts, and [executor-native](../executor-native/README.md) probes the Java adapter with this worker.

`python3 executor-service/disk_pool_probe.py` kills allocation on a disposable 512 MiB XFS mount before identity publication, after identity fsync and after publication, and checks recovery and an existing copy. Every phase must report `PASS`.

The [native probe](native_probe.py) drives the signed socket entry point against a temporary account, transient units and a 512 MiB XFS pool with per-copy quotas, under `/run` with the production `UMask=0077` ([why](../notes/implemented/2026-09-05-local-execution-supervisor.md#supervisor-and-worker)). Copy `stageExecutorWorker`'s complete output into a new disposable disk directory, preserving `bin/`, executable modes and test-client Python modules. Add `tools` from `prepare-tools.sh NEW_TOOLS_DIRECTORY`, with pinned test dependencies in `tools/python`.

```sh
sudo env PYTHONPATH=/temporary/probe/tools/python python3 /temporary/probe/native_probe.py --root /temporary/probe
```

`--baseline-only`, `--lease-sandbox-only` and `--oom-only` select baseline installation, shell reuse/reset or bounded parent/service OOM recovery. The OOM case checks score inheritance, containment and retained text, binary and Git state; kernel events must identify memcg, not host-wide OOM. Require exit zero, `summary: PASS` and `cleanup: PASS`; remove staging after mounts and units are gone. [evidence.jsonl](evidence.jsonl) proves recorded sources, not production sizing, MCP clients or deployment.

The [resource pool probe](resource_pool_probe.py) needs root, systemd with cgroup v2, Python, `runuser` with a `nobody` account, `mount` and the kernel journal. Place it beside `resource_pool.py` and `native_pool.py` and give it a new output path:

```sh
sudo python3 resource_pool_probe.py --output /temporary/new-pool-evidence.json
```

In a 96 MiB pool it checks the deployment helper against real services and missing limits, and verifies that tmpfs pages stay charged after their writer exits, so the pool's memory limit stops a second write. It removes only what it created and requires `result: PASS`; it is not a sandbox or production-capacity acceptance. The [recorded result](resource-pool-evidence.json) holds source hashes and synthetic counters.
