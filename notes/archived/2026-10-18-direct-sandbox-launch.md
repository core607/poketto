# Direct Sandbox Launch

Date: 2026-10-18
Archived: 2026-11-05
Implemented: 2026-10-20
Partially superseded by: [Native Seccomp Filter](2026-11-02-native-seccomp-filter.md), [Delegated Sandbox Cgroups](2026-11-02-delegated-sandbox-cgroups.md)

## Problem

Each [per-lease sandbox](../archived/2026-09-16-per-lease-command-sandboxes.md) retains its launcher. SRT 0.0.75 starts Node.js, HTTP/SOCKS proxies and three `socat` bridges even with no allowed domains. These consume memory and tasks; exceeding `maxLiveUnits` makes eviction repeat startup CPU work, bounded by `maxStartingUnits` admission refusals.

The worker needs no proxy, but omitting SRT's network section removes isolation and its CLI has no isolated proxy-free mode. Compilation caching retains Node bootstrap, module evaluation and proxies.

## Decision

The [launcher](../../executor-service/rust/sandcrab-launch/src/lib.rs), installed as `poketto-launch`, validates tools, translates worker policy and execs bubblewrap for initialization, shells, baselines and games. The [native filter](2026-11-02-native-seccomp-filter.md) owns seccomp installation and pinned-helper equivalence; game Node.js stays sandboxed.

Workers own their path grants. The launcher rejects unsupported fields, overlaps, weaker namespaces and network exceptions. It enters the root-owned bootstrap first, excluding untrusted repository and shell configuration from host startup.

The containment contract is:

- An empty root exposes only the policy's readable and writable binds, with usr-merged system links preserved. Root scaffolding is read-only. `/sys` is absent; `/dev` and `/proc` are fresh. Initialization and baseline bundles remain limited to their respective modes, and game jobs receive no repository or bridge.
- User, PID and network namespaces, dropped capabilities, a new session and parent-death handling accompany the runner's identity and resource bounds. Native runs and games use [delegated cgroups](2026-11-02-delegated-sandbox-cgroups.md); the repository worker retains systemd units.
- The native filter preserves SRT 0.0.75's syscall policy. Unfiltered outer processes are hidden; the visible namespace reaper inherits the filter before the workload can run.
- The sanitized environment retains `SANDBOX_RUNTIME=1` and uses the actual private temporary directory, `/tmp`. SRT's proxy variables, Git proxy settings and Java proxy agent are absent. Network failures come from an isolated network rather than a proxy.
- Bubblewrap preserves command exit status. The persistent shell's status channel remains authoritative; initialization, baseline and game failures remain distinguishable. No validation or launch failure invokes an unsandboxed replacement.

### Tool integrity and delivery

Supervisors and unprivileged launchers verify the [bundled helper's](../../executor-service/rust/sandcrab-launch/bwrap/README.md) checksum before serving and per execution. Binaries and ancestors remain root-owned, nonsymlink and unwritable by other accounts. Cleanup precedes validation, including with damaged tools.

[Coordinated delivery](../archived/2026-10-19-coordinated-worker-delivery.md) binds the helper to the launcher's immutable generation, preserving operator workload tools/configuration and restoring the previous generation after failed startup. Acceptance requires a post-deployment read-only MCP sandbox command. Workload-tool upgrades remain explicit operator work.

### Resource evidence

The 2026-10-20 same-host comparison ran three fresh `true` shells per launcher at 50% CPU/192 MiB. Direct startup CPU, idle memory and tasks were below one third of SRT's. This does not establish HTTP capacity; [runtime sizing](../../executor-service/README.md#runtime) separates command, supervisor and admission costs.

## Alternatives

- Cache or pool tuning retains the fixed processes and shifts cost between memory, startups and refusals.
- A smaller Node entry retains Node startup and depends on unexported SRT internals; a fork adds maintenance.
- A larger host retains per-unit overhead.

## Consequences

Poketto owns argument generation; SRT fixes do not apply automatically. Bubblewrap/seccomp upgrades require live equivalence verification. Poketto provisions optional toolkit/game Node. Lower idle cost does not remove workload CPU, storage or application limits.

The [launcher tests](../../executor-service/rust/sandcrab-launch/src/tests.rs) and `test_launcher.py` pin policy/tool validation, startup failure and interrupted-record cleanup. The [launcher comparison](../../executor-native/README.md#launcher-comparison) owns live equivalence/resource ratios; worker/Java [native probes](../../executor-native/README.md), toolkit and games cover behavior, containment and cleanup.
