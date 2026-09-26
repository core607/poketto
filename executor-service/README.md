# Local execution service

This worker supplies the Linux process boundary for repository execution. Spring
authorizes the principal and exports a credential-free Git bundle; the worker
accepts a signed lease and runs SRT 0.0.75 as a separate unprivileged account.
It never opens an application repository or accepts a caller-specified host path.
The [supervisor decision](../notes/implemented/2026-09-05-local-execution-supervisor.md)
owns topology, rationale and alternatives.

## Runtime

The host needs Linux with cgroup v2, systemd, unprivileged user namespaces, Python 3.10+, Git and the SRT toolchain that [prepare-tools.sh](prepare-tools.sh) builds in a new directory: a checksum-verified Node.js 22.22.0, the sandbox runtime pinned by [sandbox-runtime/package-lock.json](sandbox-runtime/package-lock.json), and extracted bubblewrap, socat, ripgrep and `which`.

Install [requirements.txt](requirements.txt) into a root-owned virtual environment at `/opt/poketto-executor/venv`, where [poketto-executor.service](poketto-executor.service) expects it, and the worker sources in one directory: `worker.py`, `launcher.py`, `command_channel.py`, `shell_loop.py`, `disk_pool.py`, `resource_pool.py`, `bridge.py`, `cli.py`, `session_files.py`, `binary_capture.py`, `materialize.py` and `artifacts.py`. Worker code, launcher, tools, configuration and public key must be root-owned and unwritable by the application and execution accounts. The worker receives only the PEM public key; the application holds the Ed25519 private key. Never put the signing key or a real operator configuration in this repository.

Package worker sources from the selected commit with `git archive` or raw Git blobs so they keep LF line endings; a launcher shebang with CRLF cannot execute on Linux. Record the source revision and hashes, verify the installed files, and run `poketto --help` through the deployed connector after restart.

Before exporting content or opening a lease, the application's HELLO check requires `codeActProtocol`, `artifactProtocol`, `moveProtocol`, `exportProtocol`, `diskCopyProtocol`, `gitBaselineProtocol`, `workspaceSyncProtocol` and `leaseSandboxProtocol`, each equal to 1; any other value rejects execution. Install and restart the matching worker before deploying the application. A restart ends existing leases and their artifacts; account copies remain on disk.

Install [resource_pool.py](resource_pool.py) at `/opt/poketto-executor/resource_pool.py`, and [poketto-executor.slice](poketto-executor.slice) and the service file in `/etc/systemd/system/`, keeping files and parent directories root-owned and unwritable by the application, execution and deployment accounts. The slice is the single source of aggregate limits for the supervisor, every lease sandbox and every helper, including bundle copying, filesystem cache and temporary pages; per-unit limits add to it, and repository storage has its own disk bound. Before starting the service, set finite `MemoryMax`, `MemorySwapMax`, `TasksMax` and `CPUQuota` in a private systemd drop-in, since the checked-in values are examples, and run `systemctl daemon-reload`. Startup and every new OPEN, ATTACH and EXEC reject a missing, unlimited or incorrectly placed pool; startup cleanup, CLOSE, revocation and `--cleanup` still run when that validation fails.

The deployment script runs the same read-only validation when execution is enabled, without reading private worker configuration; a missing helper or failed check stops deployment:

```sh
python3 /opt/poketto-executor/resource_pool.py --service poketto-executor.service
```

Provision the [content toolkit](../notes/implemented/2026-09-05-local-execution-supervisor.md#sandbox-toolkit) as root after preparing the tools directory, substituting its actual path:

```sh
sudo bash executor-service/install-sandbox-tools.sh /opt/poketto-executor/tools
```

It installs host distribution packages for Pillow, BeautifulSoup/lxml, pypdf, openpyxl, python-docx, `awk`, `zip`, Poppler (`pdftotext`, `pdfinfo`, `pdftoppm`) and Noto CJK fonts, and links `python` and `awk` inside the tools directory without changing host-wide commands. It runs no system upgrade and restarts no service; on an existing host, inspect `apt-get --simulate install` with its package list first. Afterwards validate through `repo_exec`, including native image and PDF operations.

[config.example.json](config.example.json) lists every setting; its UID/GID and numeric limits are examples, not production values. Startup enforces these rules:

| Setting | Rule |
|---|---|
| Paths | Absolute; only letters, digits, `_`, `.`, `/` and `-` |
| `runtimeRoot` | Dedicated root-owned directory; `socketPath` lies directly inside it |
| `exportRoot` | Holds only the application's atomic `<UUID>.bundle` exports, owned by `appUid` |
| `copyRoot` | Neither inside nor containing `runtimeRoot`; see [account copy storage](#account-copy-storage) |
| `appUid`, `appGid` | The only accepted `SO_PEERCRED` peer, and the group of the mode-0660 socket |
| `execUser` | Unprivileged, not the application account, not a member of `appGid` |
| `idleUnitSeconds` | Required, 1 through 86400 (example 1800) |
| `renewAfterSeconds` | Less than `leaseSeconds` |
| `unitPrefix` | Matches `poketto-exec-[a-z0-9]+-` |
| `resourceSlice` | Required; the slice the worker must run in |

## Account copy storage

The application keeps one disk copy per account, content workspace and reading scope; OAuth grants authorize operations and create no copies. [Account working copies](../notes/implemented/2026-09-14-account-working-copies.md) owns the storage and lifecycle decisions.

`copyRoot` must be a dedicated root-owned XFS mount with enforced project quotas and a total size no greater than `poolBytes`; the worker refuses any other mount and has no tmpfs fallback. A preallocated loopback filesystem provides that bound without remounting the host root. Place exports, copies, original archives and account metadata on it, and provision the mount and the application-owned export and metadata directories before starting either service.

Each copy receives inherited project hard limits `diskBytes` and `diskInodes` before command access; initial limits are 4 GiB per copy and 32 GiB for the pool. Set `poketto.executor.copies.metadata-root` to the application's private metadata directory in this pool. The other `poketto.executor.copies` defaults are `max-copies=128`, `max-record-bytes=16777216`, `pool-bytes=34359738368`, `idle-seconds=604800`, `original-bytes=268435456`, `original-expanded-bytes=1073741824` and `original-entries=100000`; validate them against the host's capacity. Stored-copy count is separate from active leases and command concurrency.

The private account journal (ownership, pinned original commit, per-file baselines, pending remote-write receipts) and an immutable, checksummed original archive stay with the copy; public copies carry only their host-owned projection proof. Journal publication uses file fsync, atomic rename and directory fsync, and no command is acknowledged before it is confirmed. Each command holds the account writer lock, records its intent before EXEC and its host write state before acknowledging a write, and is flushed to disk before a successful response. Reconnection fences the previous writer and reattaches the same files and baselines; an incomplete earlier command stays visible as potentially partial work. Copies survive transport loss, lease closure, command timeout and service restarts.

Successful authorized operations renew a seven-day idle deadline. A collector removes expired copies and incomplete initializations after containment, deferring to active writers. Disposal intent is durable, so an interrupted deletion resumes and never makes the copy executable again. Cleanup never undoes remote Git commits, and there is no off-host backup.

For full-read copies, an acknowledged save or move installs its authoritative commit as local HEAD and index before the CLI reports success, keeping working files and local commits and stashing distinct staged content. Public copies never receive this update. Save preconditions come only from the application's authoritative baseline, never from sandbox Git state. If local installation fails, the CLI reports `LOCAL_BASELINE_PENDING`, `poketto status` shows `baseCommit`, `gitCommit` and `localBaselinePending`, and `poketto recover` completes it without repeating the remote write. A reopened lease retries a pending installation once. A contained helper failure keeps the lease usable, unconfirmed helper containment fences it, and temporary baseline files are removed after failures and on attachment ([mutable working copy baselines](../notes/implemented/2026-09-15-mutable-working-copy-baselines.md)).

## Process boundary

The root supervisor only verifies requests, copies bounded exports, mounts disk copies and controls fixed systemd units. Git initialization and commands run as `execUser`, always through the pinned SRT launcher; a failed launcher or SRT invocation never runs a replacement command. SRT starts in a root-owned bootstrap directory, and the launcher enters the repository only after SRT has installed its boundary, so Git and shell configuration cannot influence startup.

A lease runs its commands in one systemd unit, started by the first command, whose shell, working directory, environment, `/tmp` and background processes persist ([per-lease command sandboxes](../notes/implemented/2026-09-16-per-lease-command-sandboxes.md)). A new lease never reuses another lease's unit. systemd bounds the unit's memory, swap, CPU, tasks and private temporary storage, including background processes; XFS quotas bound repository storage. A timeout, an output-limit stop, a shell exit or `idleUnitSeconds` without a command stops only the unit, always followed by an empty-cgroup assertion; the next command of the lease starts a new unit reporting `freshSandbox: true`. Background output after its command completes is discarded. Shell exit codes do not prove that background work stopped; only the host's empty-cgroup check does. The shell driver keeps at most 128 output readers; beyond that the oldest close, and a background process writing to one receives `EPIPE`/`SIGPIPE`.

The worker captures at most 4 MiB of combined output, stopping the unit beyond it, and returns a preview of at most 16 KiB per stream with malformed UTF-8 replaced. Longer streams also return immutable artifact handles: an artifact's `truncated` means its capture is incomplete, `stdoutTruncated` and `stderrTruncated` describe the previews, and per-stream failures appear in `artifactErrors`. The framed response has a separate 1 MiB bound. Leases closed by resource limits, cancellation or revocation cannot deliver handles; their long output reports `ARTIFACT_UNAVAILABLE`.

`maxSessions`, `maxConnections`, `maxRequests` and `maxExecutionsPerSession` bound admission and replay state. An `EXECUTION_CAPACITY` refusal did not execute; the application closes that lease, and the next call attaches the same copy with a fresh budget. A full replay table can reject new work until signed requests expire. Spring must treat failed renewal as loss of execution authority; an earlier successful request does not keep a lease alive.

## Working-copy identity

`repo_exec` requires `expectedCopyId`. `"new"` opens the account's default copy for the grant's reading scope, creating it only if absent; afterwards pass the returned `copyId`. A matching Git commit does not prove that a copy survived, transports do not own copies, and an ID mismatch is refused before the command executes.

```json
{"expectedCopyId":"new","command":"pwd"}
```

A copy keeps its reading scope when a grant gains private-read permission, and a public-only grant cannot select a full copy. Reconnection is automatic and needs no generation or resume input. Results contain `retention.expiresAt` in epoch milliseconds, `retention.resumed` and nullable `retention.lastInterruptedCommand`. A copy keeps its acknowledged baseline and local edits and is never rebuilt against current main because metadata is missing. Artifacts and `/tmp` expire sooner than the copy.

`SESSION_REPLACED` identifies an absent, closing or different copy. `EXECUTION_REFUSED` reports `executed: false`, whether recovery remains available, and a reason: `RECOVERY_REQUIRED`, `BUSY`, `CAPACITY`, `MISSING_COPY`, `EXPIRED` or `UNAVAILABLE`. Both describe this request, not an earlier command. `EXECUTION_UNCONFIRMED` returns the actual copy ID and expiry when a command may have partly executed. Never replay it: inspect the same copy and `poketto status`, and reconcile uncertain remote saves with `poketto recover`.

`repo_discard` takes the exact copy ID and requires current execution permission and account ownership, but not content-read permission. A busy writer prevents deletion. `DISCARDED` and `ABSENT` confirm completion; retry an unconfirmed result only with the same ID. Remote Git commits remain unchanged.

Micrometer exposes `poketto.executor.sessions.active`, `poketto.executor.operations.active`, `poketto.executor.sessions.created`, `poketto.executor.sessions.released` and `poketto.executor.admission.rejected` with bounded reason tags. Their HTTP exposure is operator-configured, and they do not measure memory or disk.

## Wire version 1

Each UNIX connection carries exactly one request and response: an unsigned four-byte big-endian length followed by that many UTF-8 JSON bytes, at most 1,048,576. Separate connections allow renewal and cancellation while EXEC waits. The application must verify the root-owned socket and directory before connecting. Responses are unsigned on this authenticated local channel.

The only unsigned request is `{"version":1,"operation":"HELLO"}`. It returns `ok`, `version`, the protocol markers listed under [runtime](#runtime), `workerBootId`, `maxFrameBytes`, `leaseSeconds` and `renewAfterSeconds`, and no path or key material. The worker takes its exclusive supervisor lock and completes startup cleanup before creating a boot ID, so a new boot ID at the same authenticated socket confirms that earlier leases were cleaned up. A failed HELLO or an unchanged boot ID confirms nothing.

All other requests use `{"payload":"BASE64URL_RAW_JSON","signature":"BASE64URL_ED25519_SIGNATURE"}` without padding. Sign the decoded raw payload bytes, not a reserialized object, and reject duplicate JSON keys. Every payload contains exactly these fields:

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
| `OPEN` | Creates copy `copyId` (`scope` `full` or `public`) at `commit` from export `exportId` after checking its size, at most `maxBundleBytes`, and SHA-256. Blocks until READY or failure within `initTimeoutMillis`, accepting RENEW meanwhile. |
| `BASELINE` | Full-read copies only. Freezes the lease unit, idle or running, installs the bundle's commit as Git HEAD and index inside SRT, keeps working files and returns `gitCommit`. |
| `ATTACH` | Claims an existing copy for a new lease after checking account, workspace, scope, pinned commit and the exclusive copy lock. Never clones or replaces files. |
| `DISCARD` | Deletes an owner-matched copy once no lease holds its lock. Returns DISCARDED or ABSENT, or `COPY_BUSY`. |
| `EXEC` | Runs `command` (1 byte to 64 KiB of UTF-8, no NUL) within `timeoutMillis`, at most `maxTimeoutMillis`. Requires READY and the pinned `commit`; blocks until completion or containment. |
| `RENEW` | Extends an unexpired INITIALIZING, READY or RUNNING lease to `expiresAt`. Nothing else renews. |
| `BRIDGE_POLL` | Claims one pending CLI request of the running command, or returns null when idle or after a bounded wait. Never takes the command lock or cancels on contention. |
| `BRIDGE_COMPLETE` | Publishes one bounded reply to that pending request of that running execution. |
| `CAPTURE_BEGIN` | Captures up to 64 selected UTF-8 files and 4 MiB plus explicit deletions; returns a capture ID and an ordered path/length/SHA-256 manifest. |
| `CAPTURE_OPTIONAL` | Captures one text file, or its absence, for synchronization. |
| `CAPTURE_BINARY` | Captures one regular, single-link file of up to 128 MiB. |
| `CAPTURE_READ` | Returns up to 65536 bytes of a captured file, never a fresh worktree read. |
| `CAPTURE_RELEASE` | Drops the capture. |
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

Errors contain `ok: false`, a fixed `code`, an optional bounded `reason`, and `requestId` when verified, never exception strings, source paths or command text. General codes are `INVALID_FRAME`, `INVALID_REQUEST`, `INVALID_SIGNATURE`, `WORKER_RESTARTED`, `LEASE_EXPIRED`, `AUTH_REVOKED`, `SESSION_NOT_FOUND`, `SESSION_EXISTS`, `SESSION_CLOSED`, `SESSION_BUSY`, `SESSION_COMMIT_MISMATCH`, `INVALID_EXPORT`, `INITIALIZATION_FAILED`, `EXECUTOR_FAILED`, `REPLAY_CONFLICT`, `REQUEST_IN_PROGRESS`, `EXECUTION_ALREADY_STARTED`, `REQUEST_CAPACITY`, `SESSION_CAPACITY`, `EXECUTION_CAPACITY` and `RESPONSE_LIMIT`; copy, capture, transfer and artifact operations add their own, such as `COPY_BUSY` and `MATERIALIZE_CAPACITY`.

Identical signed request bytes return their cached result while the signature is valid. A reused request ID with different bytes fails, as does a reused execution ID under a new request ID. An absent response never authorizes retrying the command: CLOSE the session and report an unknown result. Tombstones outlive every request signed before cancellation or revocation; fresh signatures after that boundary are Spring's authorization responsibility.

### Termination and cleanup

`terminationReason` is `normal`, `timeout`, `resource_limit`, `output_limit`, `cancelled`, `session_closed`, `client_shutdown`, `lease_expired`, `sandbox_failed` or `revoked`; `normal` may have a nonzero exit code. A timeout or output-limit stop keeps the lease, prior files and partial work once the unit's control group is confirmed empty. Failed containment closes the lease and keeps the copy locked against another lease; resource exhaustion and lifecycle cancellation also close the lease. Failed OPEN initialization is an operation error, never a ready session.

Poll CLOSE or REVOKE with fresh request IDs until CLOSED; a reused request ID returns its cached response. A signed CLOSE matching the session identity works after revocation so the application can confirm cleanup, and grants no execution or renewal. Repeated close requests keep the first cancellation reason.

## Sandbox CLI

Commands reach the host only through `poketto` (`cli.py` in the lease's protected bootstrap directory), which uses a lease-specific FIFO, writer lock and read-only reply directory; SRT keeps Unix socket creation disabled. The application authorizes every request and rechecks the lease before replying. The supervisor changes the bridge epoch with the unit frozen, so requests from completed commands or idle background processes never reach the host; the execution ID each CLI process inherits grants no authority. The sandbox has no outbound network or access to client-local paths; clients send images through [MCP URL import or temporary raw upload](../docs/usage.md).

`poketto edit PATH --old TEXT --new TEXT` replaces one exact, unique match in an existing UTF-8 file; `poketto create PATH --text TEXT` creates an absent file. `--stdin`, `--text-file`, `--old-file`, `--new-stdin` and `--new-file` read long UTF-8 text with final newlines, resolving files from the shell's directory. Both install through frozen compare-and-replace, so an intervening local write fails, and neither changes the save baseline or remote Git. Failures return `EDIT_REJECTED` with `OLD_TEXT_NOT_FOUND`, `AMBIGUOUS_MATCH`, `ALREADY_EXISTS` or `LOCAL_FILE_CHANGED`. The 16,384-character `repo_exec` command and 512 KiB encoded bridge frame still bound the text; ordinary shell writes get none of these checks.

`poketto save PATH... --delete PATH` sends selections, not contents. The application captures them, checks authoritative revisions at its own baseline and commits through the shared atomic Git writer; success advances the save baseline and the local Git baseline while unselected edits stay local. Public-only sessions cannot save. A conflict keeps local files and the prior baseline, and an ambiguous write blocks later saves.

`poketto status` reports the copy ID, host baseline and last save receipt. On full-read copies it compares remote main with the last confirmed save or sync base: `remote.state` is `MATCHES_BASE` or `DIFFERS_FROM_BASE` with `remote.commit` (null when empty), or `UNAVAILABLE`. That comparison does not certify every file, and status changes nothing. Public copies report `PUBLIC_PROJECTION` and their synthetic commit without querying the private head.

`poketto recover` reconciles the exact retained commit with current remote history. An observed commit is acknowledged without another push; otherwise the same commit is retried only while the original remote base still matches. Recovery revalidates the patch and current authorization, keeps newer local edits, and reports divergence as a conflict. A further lost reply keeps the same attempt.

Acknowledged saves, syncs and moves record each affected file's version (text, non-text presence with its byte revision and diagnostics, or absence); other paths use the original archive, and unselected draft media mappings never become the index baseline. Later operations use these records, never historical caches, and check private-read permission before and after archive reads. A missing record or archive fails the operation instead of falling back or inferring absence. A text save onto an existing non-text file returns `NO_WRITABLE_BASELINE` before any write intent.

`poketto move SOURCE DESTINATION` moves saved files, folders and indexed media through the shared atomic writer and repairs Markdown references. It needs a full-read session and write authority, plus publication authority for public changes. Dirty selected files, changed selected media mappings, unexpected source entries and occupied destinations are refused before the remote move; unselected edits stay local, and materialized originals move with their directory. Plans are limited to 16,384 paths, 32 MiB of replacement text and 64 MiB of transfer data. A lease keeps at most 256 completion receipts of 4 KiB; receipts and staging count against its quotas, and a failed reservation rejects preflight.

A move whose local installation is pending returns `worktreeUpdated: false`, and later saves, moves and syncs wait for `poketto recover`, which uses the retained request and receipt even when a successful reply was lost. `LOCAL_MOVE_CONFLICT` means installation was refused after the remote commit. `poketto recover --skip-local` confirms any uncertain remote outcome, leaves local files untouched and releases the pending installation while keeping per-file baselines, so later saves cannot resurrect old paths or overwrite moved files without an explicit `poketto sync`.

`poketto sync` merges one fixed remote commit into the workspace, including new and deleted paths, and commits nothing. The host first inventories at most 16,384 paths and 32 MiB of text within 20 seconds. Text merges three ways with LOCAL/BASE/REMOTE markers; local-only files remain; unchanged local binaries can receive remote blobs up to 128 MiB. Conflicting binary edits, unsafe paths and unsupported changes stay in place as conflicts, and receipts list the total and at most 64 paths within 16 KiB, with `conflictsTruncated`. Each installation is journaled first. After interruption `poketto status` shows `syncPending`; `poketto recover` continues, preserving a different local edit with `LOCAL_SYNC_PENDING`, and `--skip-local` releases the rest without undoing installed paths. Completion, conflicts included, installs the new Git baseline; resolve conflicts before saving.

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

`get_artifact` serves a handle to any MCP session of the same account, workspace and reading scope while the capturing lease stays open; a request under another credential of the account moves the copy to a new lease, ending the old lease's artifacts, and a public-only grant never reads a full copy's artifacts ([session artifacts](../notes/implemented/2026-09-10-session-artifacts.md)). Every read rechecks authorization, and public-scope reads recheck the publication. Its default `auto` format renders validated PNG, JPEG, GIF or WebP images up to 16 MiB in full, pages UTF-8 text, and returns other files, including SVG, as binary resource pages. Invalid raster bytes or a mismatched digest fail preview; `format=bytes` always returns binary pages. `offset` and `nextOffset` count bytes, `limit` is 4–65536 (8192 by default), and a null `nextOffset` means EOF. Image previews require offset zero. Artifacts never upload originals, write Git, publish or create URLs.

## Verification

Run the protocol and state tests on Linux with the pinned Python dependencies; `./gradlew executorServiceTests` runs them there, or in a pinned Linux container on Windows:

```sh
python -m unittest discover -s executor-service -v
```

The root-only probes below use synthetic, disposable fixtures, and each needs `cleanup: PASS`. [Client acceptance](../acceptance/clients/README.md) covers real accounts, and [executor-native](../executor-native/README.md) probes the Java adapter with this worker.

`python3 executor-service/disk_pool_probe.py` kills allocation on a disposable 512 MiB XFS mount before identity publication, after identity fsync and after publication, and checks recovery and an existing copy. Every phase must report `PASS`.

The [native probe](native_probe.py) drives the signed socket entry point against a temporary account, transient units and a 512 MiB XFS pool with per-copy quotas, under `/run` with the production `UMask=0077` ([why](../notes/implemented/2026-09-05-local-execution-supervisor.md#supervisor-and-worker)). Prepare a new disposable directory on disk with `native_probe.py`, `native_pool.py`, the worker sources listed under [runtime](#runtime) and a `tools` directory created by `prepare-tools.sh NEW_TOOLS_DIRECTORY`, with the pinned Python dependencies in `tools/python`.

```sh
sudo env PYTHONPATH=/temporary/probe/tools/python python3 /temporary/probe/native_probe.py --root /temporary/probe
```

`--baseline-only` and `--lease-sandbox-only` limit the run to baseline installation or to lease sandbox reuse and resets. Success requires exit zero, `summary: PASS` and `cleanup: PASS`; remove the directory after its mounts and units are gone. [evidence.jsonl](evidence.jsonl) records one run with its source hashes and proves only those sources, not production sizing, MCP clients or deployment.

The [resource pool probe](resource_pool_probe.py) needs root, systemd with cgroup v2, Python, `runuser` with a `nobody` account, `mount` and the kernel journal. Place it beside `resource_pool.py` and `native_pool.py` and give it a new output path:

```sh
sudo python3 resource_pool_probe.py --output /temporary/new-pool-evidence.json
```

In a 96 MiB pool it checks the deployment helper against real services and missing limits, and verifies that tmpfs pages stay charged after their writer exits, so the pool's memory limit stops a second write. It removes only what it created and requires `result: PASS`; it is not an SRT or production-capacity acceptance. The [recorded result](resource-pool-evidence.json) holds source hashes and synthetic counters.
