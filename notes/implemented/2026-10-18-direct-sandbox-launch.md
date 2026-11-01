# Direct Sandbox Launch

Date: 2026-10-18
Partially superseded by: [Native repository consumer](2026-11-01-native-repository-consumer.md).
Implemented: 2026-10-20

## Problem

Each [per-lease command sandbox](2026-09-16-per-lease-command-sandboxes.md) keeps its launcher alive. SRT 0.0.75 starts Node.js, HTTP/SOCKS proxies and three `socat` bridges even when the worker's network policy allows no domains. Those processes consume memory and tasks throughout the lease. Once the working set exceeds `maxLiveUnits`, eviction turns their startup CPU cost into repeated work; `maxStartingUnits` bounds that work by refusing admission.

The fixed worker policy needs no proxy. Omitting SRT's network section removes isolation, however, and its CLI has no proxy-free isolated mode. A compile cache saves compilation but retains Node bootstrap, module evaluation and proxy setup.

## Decision

The [launcher](../../executor-service/rust/sandcrab-launch/src/lib.rs), used by the native binary, validates the tools and translates an operator-owned profile into bubblewrap arguments, then replaces itself with `bwrap`. The pinned static `apply-seccomp` installs its filter before the command. Repository initialization, shells and game programs execute inside this boundary; the application owns their workflows.

The repository and game workers retain ownership of their readable and writable paths. The launcher rejects unsupported policy fields, overlapping grants, weaker namespaces and network exceptions instead of silently ignoring them. It enters the root-owned bootstrap before starting the boundary; untrusted repository or shell configuration cannot influence host startup.

The containment contract is:

- An empty root exposes only the policy's readable and writable binds, with usr-merged system links preserved. Root scaffolding is read-only. `/sys` is absent; `/dev` and `/proc` are fresh. Each configured consumer receives only its admitted paths; game profiles receive no repository or host-call channel.
- User, PID and network namespaces, dropped capabilities, a new session and parent-death handling accompany the systemd unit's `NoNewPrivileges`, CPU, memory, swap, task and temporary-storage bounds.
- SRT 0.0.75's unchanged `apply-seccomp` denies Unix sockets and `io_uring`. Its nested namespace and non-dumpable PID 1 keep unfiltered outer processes invisible and untraceable.
- The sanitized environment retains `SANDBOX_RUNTIME=1` and uses the actual private temporary directory, `/tmp`. SRT's proxy variables, Git proxy settings and Java proxy agent are absent. Network failures come from an isolated network rather than a proxy.
- Bubblewrap preserves command exit status. The persistent shell's status channel remains authoritative; application initialization and game failures remain distinguishable. No validation or launch failure invokes an unsandboxed replacement.

### Tool integrity and delivery

The prepared toolchain already contains the required binaries. The native service validates the x64 `apply-seccomp` SHA-256 pin and bubblewrap's minimum version before serving requests, and its unprivileged launcher repeats validation before each execution. Both binaries and their ancestors must be root-owned and unwritable by other accounts; symlinks are rejected. Cleanup precedes validation and remains available with damaged tools.

[Independent sandbox deployment](2026-10-31-independent-sandbox-deployment.md) verifies the installed runtime and a read-only MCP command. Runtime and tool installation, upgrades and recovery remain explicit operator work.

### Resource evidence

A same-host serial comparison on 2026-10-20 used three fresh persistent shells per launcher, each running `true` under identical 50% CPU and 192 MiB limits. Median startup CPU was 471 ms with SRT and 149 ms with direct launch; idle cgroup memory was 37.7 MiB and 8.8 MiB, and task counts were 22 and 6. Each direct cost was below one third of its reference. These are fixture measurements, not an HTTP latency or client-capacity guarantee. [Runtime sizing](../../executor-service/README.md#runtime) accounts separately for commands, supervisor overhead and admission.

## Alternatives

- Keep SRT and tune its cache or pool limits. This shifts cost between memory, startups and refusals without removing the fixed per-unit processes.
- Call SRT's internal wrapper from a smaller Node entry. This removes proxies but retains Node's startup and memory and depends on unexported internals.
- Fork SRT. Poketto would own a Node-based fork while retaining its startup cost.
- Expand the host. Additional resources can complement this change, but retain the per-unit overhead.

## Consequences

Poketto owns sandbox argument generation. SRT fixes to that layer do not apply automatically; bubblewrap or seccomp upgrades require live equivalence verification again. The SRT package remains the source of the pinned helper, and Node remains a toolkit and game dependency. Reducing idle cost does not remove workload CPU, storage or application limits.

The [launcher tests](../../executor-service/rust/sandcrab-launch/src/tests.rs) pin policy rejection and tool validation. The public [native probes](../../executor-native/README.md) cover startup failure, interrupted operations, toolkit behavior, games and confirmed cleanup. Dated [launcher comparisons](../../executor-native/README.md#launcher-comparison) retain their measured equivalence and resource ratios without establishing current-source acceptance.
