# Native executor feasibility probe

This probe exercises SRT 0.0.75 on Linux with synthetic repositories. It is a
test harness, not a production executor or an MCP endpoint. It never opens a
configured content repository or accepts commands from remote callers.

## Run

Use a test host with cgroup v2, systemd, unprivileged user namespaces, Python 3,
Git, Node.js >= 20.11, npm, and Debian-compatible package tools. Root access is
required to create and remove a temporary account, tmpfs mount, and transient
systemd units. The sandboxed commands run as that account, with no inherited
operator environment. Do not interrupt the harness with SIGKILL: its cleanup
handler cannot execute after the supervisor is killed.

```sh
sudo bash executor-spike/prepare.sh /opt/poketto-spike-tools
sudo python3 executor-spike/probe.py --tools /opt/poketto-spike-tools
```

The tools directory must not exist before preparation. Dependencies are unpacked
there without a global package installation. SRT and its npm dependencies use
the lockfile; distribution tools use the host's configured package repositories.
The tools directory remains after the test so the operator can inspect or reuse
it, then remove that exact directory. Each probe run removes its own account,
mount, units, and synthetic repository directories in `finally`.

The probe fails when a prerequisite or assertion fails. SRT failure never runs
the payload without SRT. JSON lines report individual results and final cleanup;
payload excerpts are limited to 1 KiB. Only a `summary: PASS` followed by
`cleanup: PASS`, with process exit zero, means the complete probe succeeded.

## Verified boundary

SRT denies filesystem reads from `/` and re-allows only the toolchain and
execution directory. Synthetic host data, the source repository, and another
workspace remain outside that directory. The command cannot reach an actual
synthetic host Unix socket, direct external or loopback TCP, or a forced HTTP
proxy on host loopback. The source clone is copied with `git clone --no-local`,
has no remote or alternates, and shares no source object inode. Destroying its
object database must leave every source byte unchanged.

The supervisor applies memory, process-count and wall-time limits through
systemd/cgroup v2, bounded tmpfs storage, and a bounded output reader. These are
adversarial test limits, not production sizing recommendations. Timeout,
cancellation, output overflow, and normal completion must leave no processes
owned by the temporary account. The probe measures one synthetic clone and
twenty fresh SRT invocations reusing its working directory.

The [recorded native run](evidence.jsonl) includes the probe and lockfile SHA-256,
runtime versions, denial errors, resource-limit outcomes, timings, and cleanup.
Its repository is a tiny synthetic fixture; its timings do not predict real
content history or production throughput.

## Remaining integration requirements

The test's root supervisor is not a service authorization design. A production
low-privilege executor still needs a narrowly trusted supervisor or delegated
resource controls, a permission-protected socket, authenticated server-issued
leases, principal/workspace/session isolation, revocation-driven cancellation,
and restart recovery. This harness does not prove those application contracts.
It also does not measure real content history transfer or final production
resource limits. Passing it establishes native isolation feasibility only.

SRT's native restrictions and configuration are documented in the
[upstream runtime](https://github.com/anthropics/sandbox-runtime). The probe does
not enable the runtime's weaker nested-sandbox mode.
