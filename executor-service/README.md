# Local execution service

This worker supplies the Linux process boundary for repository execution. Spring
authorizes the principal and exports a credential-free Git bundle; the worker
accepts a signed lease and runs SRT 0.0.75 as a separate unprivileged account.
It never opens an application repository or accepts a caller-specified host path.
The [supervisor decision](../notes/proposed/2026-09-05-local-execution-supervisor.md)
owns topology, alternatives, and remaining integration acceptance.

## Runtime

The application's HELLO check requires `codeActProtocol: 1` and `artifactProtocol: 1` before exporting content or opening a lease. These markers cover the synchronous bridge, frozen text/binary capture, guarded materialization and retained artifact contracts. Missing or different values reject execution. Install the complete worker source set, including `artifacts.py`, and restart its service before deploying the application; existing leases end on restart. The outer signed envelope remains version 1.

Linux with cgroup v2, systemd, unprivileged user namespaces, Python 3.10+, Git,
and the toolchain prepared by [the native spike](../executor-spike/README.md)
is required. Install [requirements.txt](requirements.txt) into a root-owned
virtual environment. The service file expects that environment at
`/opt/poketto-executor/venv`. Worker code, launcher, tools, configuration, and
public key must be root-owned and unwritable by both application and execution
accounts. The application holds the Ed25519 private key; the worker receives only
its PEM public key. Never put the signing key or a real operator configuration
in this repository.

[config.example.json](config.example.json) lists all configurable paths and
per-command resource bounds. Its UID/GID and numeric limits are examples, not approved
production values. `runtimeRoot` must be a dedicated root-owned directory;
`exportRoot` contains only the application's atomic `<UUID>.bundle` exports.
`socketPath` must be under `runtimeRoot`. Configure `appUid` for `SO_PEERCRED`
validation and `appGid` for socket mode 0660. The execution account cannot be
the application account or a member of its socket group. Paths must contain no
spaces, control characters, or systemd property metacharacters.

The root supervisor only verifies requests, copies bounded exports, mounts
private tmpfs volumes, and controls fixed systemd units. Git initialization and
commands run as `execUser`, always through the pinned SRT launcher. Each
invocation has a fresh process tree; session files persist until closure.
SRT starts in a root-owned bootstrap directory containing only inert deny-marker
targets. The launcher enters the command-modified repository only after SRT has
installed its boundary; Git and shell configuration cannot influence startup.
Memory, swap, CPU, process count, wall time, and private temporary storage are
bounded by systemd. A session tmpfs bounds working-tree and history storage.
The worker captures at most 4 MiB of combined stdout/stderr bytes and stops
the process tree when that limit is exceeded. Each stream returns a preview of
at most 16 KiB of captured bytes. Longer streams also return immutable artifact
handles; `truncated` on an artifact means its captured bytes are incomplete,
while `stdoutTruncated` and `stderrTruncated` describe the previews. An output
limit preserves the lease and local files after process cleanup. Artifact quota
or storage failures return explicit per-stream `artifactErrors`. UTF-8 decoding
replaces malformed preview bytes; use binary artifact pages for exact bytes.
The complete framed response has a separate 1 MiB bound. Timeout, resource-limit,
cancelled and revoked sessions close under the existing lifecycle policy: they
cannot retain or deliver artifact handles. Their shortened output reports
`ARTIFACT_UNAVAILABLE`; the accompanying `terminationReason` identifies the cause.

Install [resource_pool.py](resource_pool.py) with the worker at
`/opt/poketto-executor/resource_pool.py`, and install
[poketto-executor.slice](poketto-executor.slice) and
[poketto-executor.service](poketto-executor.service) in `/etc/systemd/system/`.
Keep these files and their parent directories root-owned and unwritable by
application, execution, and deployment accounts. The slice is the single source
of aggregate limits: set finite `MemoryMax`, `MemorySwapMax`, `TasksMax` and
`CPUQuota` in a private systemd drop-in before starting the service. Its checked-in
numbers are bounded examples, not production sizing. Run `systemctl daemon-reload`
after installation or changes. `resourceSlice` in worker configuration must match
the service's `Slice`; the default name is `poketto-executor.slice`.

The root supervisor and every transient command explicitly join this pool.
Its memory budget includes bundle copying and tmpfs pages retained after a
command exits. Per-command limits remain additional bounds; they do not replace
the pool. Startup and new OPEN/EXEC operations reject missing, unlimited or
incorrectly placed pools. Startup cleanup runs before validation, and CLOSE,
revocation and `--cleanup` remain available when pool validation fails.

The deployment operator runs the same read-only validation without reading
private worker configuration:

```sh
python3 /opt/poketto-executor/resource_pool.py --service poketto-executor.service
```

It checks the actual root process, service membership and kernel cgroup limits.
The deployment script requires this check when execution is enabled; a missing
helper or failed check stops deployment. Install matching worker/helper versions
before redeploying an application that requires execution.

`maxSessions`, `maxConnections`, `maxRequests`, and `maxExecutionsPerSession`
bound admission and replay state. A full replay table can reject new work until
signed requests expire. Spring must treat failed renewal as loss of execution
authority; it cannot assume an earlier successful request keeps a lease alive.

## Wire version 1

Each UNIX connection carries exactly one request and response: an unsigned
four-byte big-endian length followed by that many UTF-8 JSON bytes, at most
1,048,576 bytes. Separate connections allow renewal and cancellation while EXEC
waits. The application must verify the root-owned socket and directory before
connecting. Responses are unsigned on this authenticated local channel.

The only unsigned request is:

```json
{"version":1,"operation":"HELLO"}
```

It returns `ok`, `version`, `workerBootId` (UUID), `maxFrameBytes`, `leaseSeconds`,
and `renewAfterSeconds`. The boot ID changes after every worker restart. No
path or key material appears in HELLO.
The worker acquires its exclusive supervisor lock and completes startup cleanup
before creating a boot ID or serving HELLO. A new boot at the same authenticated
socket therefore confirms that prior worker leases were cleaned up. A failed
HELLO or the same boot ID supplies no such confirmation.

All other requests use this envelope:

```json
{"payload":"BASE64URL_RAW_JSON_WITHOUT_PADDING","signature":"BASE64URL_ED25519_SIGNATURE_WITHOUT_PADDING"}
```

Sign the decoded raw payload bytes, not a reserialized object. Reject duplicate
JSON keys. Every payload contains exactly these fields:

| Field | Meaning |
|---|---|
| `version` | Integer 1 |
| `workerBootId`, `appBootId` | Worker and application process epochs, UUIDs |
| `operation`, `requestId` | Operation below and unique UUID |
| `issuedAt`, `expiresAt` | Integer Unix seconds; validity no longer than `leaseSeconds` |
| `principalId`, `accountId`, `workspaceId` | API key, owner account, and workspace UUIDs |
| `serverSessionHash` | Lowercase SHA-256 of the server-issued MCP session ID |
| `leaseId` | Application-generated opaque UUID |
| `data` | Operation-specific object |

| Operation | Exact `data` fields and behavior |
|---|---|
| `OPEN` | `exportId` UUID, `bundleSha256` 64 lowercase hex, `bundleBytes` positive integer, `commit` 40 lowercase hex. Blocks until READY or failure. Initialization accepts concurrent RENEW, but has its own hard timeout. |
| `EXEC` | `executionId` UUID, `commit`, `command` nonempty UTF-8 text up to 64 KiB without NUL, `timeoutMillis` within worker bounds. Requires READY and the pinned commit; blocks until the entire process tree terminates. |
| `RENEW` | Empty object. Extends an unexpired INITIALIZING, READY, or RUNNING lease to `expiresAt`. Other operations do not renew it. |
| `BRIDGE_POLL` | Empty object. Claims one CLI request, or returns null after a bounded wait; includes the current `executionId`. Contention with input cleanup or another poll returns no request without cancelling the lease. Does not acquire the command operation lock. |
| `BRIDGE_COMPLETE` | `executionId`, `bridgeRequestId` and `response` object. Publishes one bounded reply only for that running execution and pending request. |
| `CAPTURE_BEGIN` | `executionId`, `writes` and `deletes` path lists. Freezes the command cgroup, captures up to 64 selected UTF-8 files and 4 MiB, then thaws. Returns a worker-owned capture ID, ordered path/length/SHA-256 manifest and explicit deletions. One capture per command; mount cleanup waits for capture. |
| `CAPTURE_OPTIONAL` | `executionId` and `path`. Captures one current text file or explicit absence for synchronization; missing lease roots and unsafe paths fail. Uses the same cgroup freeze and capture lifetime. |
| `CAPTURE_BINARY` | `executionId` and `path`. Freezes the command cgroup and copies one regular binary file with a link count of one into protected lease storage, up to 128 MiB. Returns the same immutable capture manifest and uses `CAPTURE_READ`/`CAPTURE_RELEASE`; unsafe paths and missing bytes fail. |
| `CAPTURE_READ` | `executionId`, `captureId`, zero-based file `index`, byte `offset`, and `limit` from 1 to 65536. Returns a base64 chunk from that immutable capture, never a fresh read of the mutable worktree. |
| `CAPTURE_RELEASE` | `executionId` and `captureId`. Drops the retained capture; command cleanup also drops it. Capture operations require the matching running execution and current lease authority. |
| `MATERIALIZE_BEGIN` | `executionId`, `path`, `bytes` (0 through 128 MiB), `sha256`, `expectedSha256` (null means absent), `delete`, and `allowIdentical`. The last flag permits reusing an identical existing file without replacement; otherwise the captured precondition is strict. Allocates one protected incoming file per lease; returns `transferId`. |
| `MATERIALIZE_CHUNK` | `executionId`, `transferId`, exact next byte `offset`, and base64 `data` of at most 65536 decoded bytes. Stages outside sandbox-readable paths. |
| `MATERIALIZE_COMMIT` | `executionId` and `transferId`. Verifies staged length/hash, freezes the cgroup, compares the current target with the captured precondition, then atomically replaces or explicitly deletes it. Repeated completion returns its retained receipt without modifying a newer local edit. |
| `MATERIALIZE_ABORT` | `executionId` and `transferId`. Releases the transfer slot and any staging file; never undoes an acknowledged installation. Command cleanup also releases them. |
| `ARTIFACT_CREATE` | `executionId`, `path`, and `mediaType`. Freezes the matching running command and copies one regular, single-link file into protected lease storage. Returns immutable artifact metadata. |
| `ARTIFACT_READ` | `artifactId`, byte `offset`, and `limit` from 1 to 65536. Returns a bounded base64 page and metadata from this lease's private artifact map. Works after command completion; never selects a host path. |
| `ARTIFACT_REMOVE` | `artifactId`. Releases the retained object early; an unknown ID is harmless. Every artifact operation requires the signed lease identity and current authority. |
| `CLOSE` | Empty object or `reason`: `cancelled`, `session_closed`, or `client_shutdown`. May return CLOSING until cleanup finishes. A CLOSE arriving before OPEN creates a tombstone and returns CLOSED with null commit. |
| `REVOKE` | `keyIds` and `accountIds`, each a list of at most 1000 UUIDs. Targets the payload workspace. Control identity fields may be zero UUIDs and a zero hash. Tombstones precede cancellation. |

OPEN, RENEW, CLOSE, and EXEC success responses contain `ok: true`, `requestId`,
`leaseId`, `state`, and `commit`. EXEC also returns `result`:

```json
{"commit":"40_HEX","exitCode":0,"stdout":"","stderr":"","stdoutTruncated":false,"stderrTruncated":false,"timedOut":false,"terminationReason":"normal","artifacts":{},"artifactErrors":{}}
```

Bridge responses carry the same lease fields plus `executionId` and `bridgeRequest`. Commands use only a lease-specific FIFO, advisory writer lock and read-only reply directory. SRT keeps Unix socket creation disabled. The root worker installs `cli.py` as `poketto` in its protected bootstrap directory; `bridge.py`, `cli.py`, `session_files.py`, `binary_capture.py`, `materialize.py` and `artifacts.py` must be installed beside the launcher. The application authorizes every request and rechecks the lease before publishing a reply. Command cleanup discards abandoned requests before another command can start.

`poketto save PATH... --delete PATH` sends selections, not file contents. The application captures those files, checks authoritative revisions at its own baseline and uses the shared atomic Git writer. Success advances only the host save baseline; the worker's original history and all unselected local edits remain. Public-only sessions reject saves. Conflicts retain local files and the prior baseline; an ambiguous write blocks subsequent saves. `poketto status` exposes the host baseline and last save receipt.

`poketto recover` reconciles the exact host-retained commit against current remote history. An observed commit is acknowledged without another push; otherwise recovery retries that same commit only while the original remote base still matches. It revalidates the original patch and current authorization, retains newer local edits, and returns a conflict on divergence. A further lost reply retains the same attempt.

`poketto sync PATH` performs a bounded three-way text merge against that file's host-owned baseline and current remote version. It rechecks the local bytes while frozen before installation, retains deletion intent, and updates only the selected file's baseline after acknowledgement. Overlapping changes produce LOCAL/BASE/REMOTE conflict markers and exit unsuccessfully; the agent edits them and saves separately. Unselected files retain their previous expected revisions, so a later save cannot silently overwrite changes that another author made to them. Synchronization does not commit remote changes.

`poketto media list [--prefix PREFIX] [--offset N] [--limit N] [--index-version HASH] [--commit COMMIT]`
lists logical paths, declared media types and sizes without fetching originals.
Full-read sessions use the current local index, including unsaved imports, or an
explicit historical index. Public sessions use only the host-owned admitted
projection and reject historical selection. Local index edits cannot expand that
public list. Metadata does not prove that original bytes remain available.

Pages are sorted by path and bounded to 12 KiB of compact JSON, with a default
of 100 entries and a maximum of 200. A byte-limited page can contain fewer entries;
continue with its `nextOffset` and `indexVersion`. A changed version returns
`MEDIA_INDEX_CHANGED` without stale items. A null `nextOffset` means EOF. The
prefix is a literal string, so use a trailing slash to select a directory tree.
Listing does not materialize, upload, save or publish files.

`poketto media fetch PATH [--commit COMMIT] [--output PATH]` materializes an indexed original into the worktree. Full-read sessions use their current local index, including unsaved imports, or select an authorized historical commit explicitly. The host resolves every original within the admitted workspace. Public sessions use only the admitted projection's host-owned media mapping and exact asset metadata; they expose no original commit identity. Transfer uses the shared original-file authorization and admission limits, bounded chunks and quarantined staging. An identical local file is retained; a different local file is not overwritten. Fetch does not upload, commit or update the index.

`poketto media import FILE --as LOGICAL_PATH --key KEY [--type MIME] [--replace]` captures one regular file while the command cgroup is frozen and stores an immutable original. Captures are bounded to 128 MiB, charged to the lease's temporary storage and transferred in verified chunks. Keys contain 16–128 letters, digits, underscores or hyphens; retry the same bytes and type with the same key. The command updates only the selected entry in the local `.poketto/assets.json`; `--replace` permits a different logical entry while retaining the old original. Missing tracked indexes and collisions with tracked Git files are rejected. If the index changes during upload, the stored original remains available but the newer local index is preserved. `poketto status` retains `lastImport`, distinguishing original storage from index installation. Import requires private write permission and does not commit or publish. Save the index and referring text together with `poketto save`. [Client acceptance](../acceptance/clients/README.md) records real Codex and Claude Code workflows through isolated Spring authentication, PostgreSQL, HTTP MCP and native SRT. That loopback run does not complete the final HTTPS installation acceptance.

### Returned artifacts

`poketto artifact create FILE [--type MIME]` returns an immutable snapshot of a
repository-relative regular file. `poketto artifact remove ID` releases it early.
The worker retains at most 16 artifacts and 256 MiB per lease, with a 128 MiB
per-file bound and a five-minute lifetime. Retained bytes count against the lease
tmpfs quota. The protected copies are inaccessible to sandbox commands. There is
no shared object registry or cross-workspace deduplication; closing or revoking
the session invalidates handles and cleans up their storage.

The `get_artifact` MCP tool returns artifacts only to the originating execution
session after current authorization. Its default `auto` format renders validated
PNG, JPEG, GIF or WebP images up to 16 MiB in full, pages UTF-8 text, and returns
other files, including SVG, as exact binary resource pages. Invalid raster bytes
or a mismatched image digest fail preview validation; use `format=bytes` to read
those original bytes without rendering them. `format=bytes` always returns binary
pages. Page `offset` and `nextOffset` count bytes; `limit` is 4–8192 bytes, and a
null `nextOffset` means EOF. Image previews require offset zero and ignore the
page limit. Public-scope reads recheck the admitted publication before delivery.
Neither creating nor reading an artifact uploads an original, writes Git,
publishes content, or creates an independently accessible URL.

### Termination and cleanup

`terminationReason` is `normal`, `timeout`, `resource_limit`, `output_limit`,
`cancelled`, `session_closed`, `client_shutdown`, `lease_expired`, or `revoked`.
`normal` may have a nonzero exit code. Failed OPEN initialization is an operation
error, never a ready session. A failed launcher or SRT invocation does not run a
replacement command.

REVOKE returns `ok`, `requestId`, `state` (CLOSING or CLOSED), and `closedCount`.
Poll CLOSE or REVOKE with fresh request IDs until CLOSED. Reusing a request ID
returns its cached response and therefore does not observe a state transition.
A signed CLOSE matching the session identity remains available after revocation
so the application can confirm cleanup. It grants no new execution or renewal.
Repeated close requests preserve the first cancellation reason.

Errors contain `ok: false`, a fixed `code`, and `requestId` when verified. They
do not contain exception strings, source paths, or command text. Codes include
`INVALID_FRAME`, `INVALID_REQUEST`, `INVALID_SIGNATURE`, `WORKER_RESTARTED`,
`LEASE_EXPIRED`, `AUTH_REVOKED`, `SESSION_NOT_FOUND`, `SESSION_EXISTS`,
`SESSION_CLOSED`, `SESSION_BUSY`, `SESSION_COMMIT_MISMATCH`, `INVALID_EXPORT`,
`INITIALIZATION_FAILED`, `EXECUTOR_FAILED`, `REPLAY_CONFLICT`,
`REQUEST_IN_PROGRESS`, `EXECUTION_ALREADY_STARTED`, `REQUEST_CAPACITY`,
`SESSION_CAPACITY`, `EXECUTION_CAPACITY`, and `RESPONSE_LIMIT`.

Identical signed request bytes return their cached result while their signature
is valid. A reused request ID with different bytes fails. A reused execution ID
with a new request ID also fails. An absent response never authorizes retrying
the command. CLOSE the affected session and report an unknown result instead.
Tombstones outlive every request signed before cancellation or revocation;
fresh signatures after that boundary remain Spring's authorization responsibility.

## Verification

Run protocol and state tests on Linux with the pinned Python dependencies:

```sh
python -m unittest discover -s executor-service -v
```

The root-only [native probe](native_probe.py) creates synthetic history, a
temporary account, transient units, and bounded tmpfs mounts. It verifies the
actual signed socket entry point and cleans units, mounts, and the account in
`finally`. Its runtime uses a new root-owned directory under `/run` and the
production `UMask=0077`; this prevents the private `/tmp` write grant from
concealing a production filesystem-mount error. Use a new disposable root
directory containing worker.py, resource_pool.py, native_pool.py,
launcher.py, bridge.py, cli.py, session_files.py, binary_capture.py, materialize.py, artifacts.py, native_probe.py, and a prepared `tools` directory. Install the
pinned Python dependencies into `tools/python`; the probe's supervisor uses
that directory. `prepare-native.sh NEW_TOOLS_DIRECTORY executor-spike` creates
the pinned SRT toolchain without installing global packages.

```sh
sudo env PYTHONPATH=/temporary/probe/tools/python python3 /temporary/probe/native_probe.py --root /temporary/probe
```

Only exit zero plus both `summary: PASS` and `cleanup: PASS` completes the probe.
The disposable source, tools, and logs remain for inspection; remove that exact
verified probe directory after its mounts and units are gone. The checked-in
[evidence](evidence.jsonl) records 19 synthetic checks, including supervisor and
command membership in the finite resource pool, with the worker, launcher,
probe and pool source hashes. Those hashes define the verified implementation;
changed sources require a new run. The record does not claim production sizing,
actual MCP clients, or formal deployment.

The separate [resource pool probe](resource_pool_probe.py) needs only root,
systemd/cgroup v2, Python, `runuser` with a `nobody` account, `mount`, and the kernel
journal. Place it beside `resource_pool.py` and `native_pool.py`, then run:

```sh
sudo python3 resource_pool_probe.py --output /temporary/new-pool-evidence.json
```

The output must not exist. The probe creates a new 96 MiB pool and a 128 MiB tmpfs,
checks the unprivileged deployment helper against real service identities and
missing limits, then verifies that 32 MiB remains charged after its allocating
process exits. A second 80 MiB write must hit the parent memory limit. It records
kernel counters and scoped OOM evidence, and removes only its own units, mount,
slice and files. Both `result: PASS` and `cleanup: PASS` are required. This is an
isolated aggregate-budget test, not an SRT or production-capacity acceptance.
The [recorded result](resource-pool-evidence.json) contains source hashes and
synthetic counters from a Linux cgroup v2 run; it does not include operator paths
or production limits.
