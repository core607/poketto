# Local execution service

This worker supplies the Linux process boundary for repository execution. Spring
authorizes the principal and exports a credential-free Git bundle; the worker
accepts a signed lease and runs SRT 0.0.75 as a separate unprivileged account.
It never opens an application repository or accepts a caller-specified host path.
The [supervisor decision](../notes/proposed/2026-09-05-local-execution-supervisor.md)
owns topology, alternatives, and remaining integration acceptance.

## Runtime

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
The worker captures at most 64 KiB of combined stdout/stderr bytes and stops
the process tree when that limit is exceeded. UTF-8 decoding replaces malformed
output bytes; the decoded string can use more encoded bytes than its captured
input. The complete framed response still has a separate 1 MiB bound.

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
| `CLOSE` | Empty object or `reason`: `cancelled`, `session_closed`, or `client_shutdown`. May return CLOSING until cleanup finishes. A CLOSE arriving before OPEN creates a tombstone and returns CLOSED with null commit. |
| `REVOKE` | `keyIds` and `accountIds`, each a list of at most 1000 UUIDs. Targets the payload workspace. Control identity fields may be zero UUIDs and a zero hash. Tombstones precede cancellation. |

OPEN, RENEW, CLOSE, and EXEC success responses contain `ok: true`, `requestId`,
`leaseId`, `state`, and `commit`. EXEC also returns `result`:

```json
{"commit":"40_HEX","exitCode":0,"stdout":"","stderr":"","stdoutTruncated":false,"stderrTruncated":false,"timedOut":false,"terminationReason":"normal"}
```

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
launcher.py, native_probe.py, and a prepared `tools` directory. Install the
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
