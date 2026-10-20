# Direct Sandbox Launch

Date: 2026-10-18
Status: Proposed

## Problem

Every fresh command sandbox starts Node.js to run SRT 0.0.75, which computes the bubblewrap arguments, starts network proxies and then waits as the parent of the sandbox for the unit's whole life. On the author's 2-vCPU, 8 GB production host, idle, a fresh unit running `true` (2026-10-18) used 0.46–0.51 s of CPU, of which Node.js took 0.35–0.37 s with 80 MB RSS and the [shell loop](../../executor-service/shell_loop.py) about 0.05 s. The unit charged 42 MB of memory and held 22 tasks in 12 processes, 11 of them Node.js threads and 3 `socat` bridges.

Both costs bound how many agents one host carries. Each lease keeps its own unit ([per-lease command sandboxes](../implemented/2026-09-16-per-lease-command-sandboxes.md)), so resident memory grows with the number of agents working at once. When that working set exceeds `maxLiveUnits`, warm units are replaced and their next commands start fresh units. Startup admission (`maxStartingUnits`) keeps those starts from saturating the CPU, at the price of `EXECUTION_CAPACITY` refusals. On 2026-10-18, fresh starts under 50 concurrent agents took 7–8 s. On 2026-10-19, during a 100-client ramp on the same host, CPU pressure exceeded 80% and available memory fell by about 2.5 GB within three minutes, before the load guard stopped the run. Tuning the two bounds moves cost between memory, CPU and refusals; only a cheaper unit lowers all three.

The Node.js layer cannot be made cheap from outside:

- Compilation is a small part of it. The compile cache that [install-compile-cache.sh](../../executor-service/install-compile-cache.sh) installs saves about 0.025 s; Node.js bootstrap, module evaluation, an internal HTTP stack, `which` child processes and the proxies take the rest.
- In SRT 0.0.75, the command-line entrance starts its HTTP/SOCKS proxy and a host `socat` bridge whenever a `network` section is present, empty `allowedDomains` included, and runs two more `socat` listeners inside the sandbox. Without a `network` section it does not isolate the network at all, so no setting gives `--unshare-net` without the proxy. Only SRT's internal Linux wrapper accepts that combination.

For the worker's fixed sandbox policy (`sandbox_policy` in [worker.py](../../executor-service/worker.py), shared by the [game worker](../../executor-service/game_worker.py)), SRT's output is the same argument list on every start, apart from paths and proxy details.

## Proposal

[launcher.py](../../executor-service/launcher.py) builds that bubblewrap argument list itself and replaces itself with `bwrap`. Inside, SRT's vendored `apply-seccomp` binary, a static program that does not use Node.js, installs the same filter before the mode's command runs. Node.js, the proxy and every `socat` leave the start path for all four modes (`initialize`, `shell`, `baseline` and `game`). The unit's process tree becomes `bwrap`, its PID-namespace init, two `apply-seccomp` processes, and the mode's command. The game mode's command is still Node.js running the game entry.

`sandbox_policy` stays the single description of what a sandbox may read and write. The worker keeps writing it into the signed record's settings file, and the launcher translates exactly those path lists into bind mounts instead of passing them to SRT, so the repository and game workers cannot drift apart.

### Equivalence contract

The direct launch must give the command exactly what SRT gives it today, except for the deltas listed here. Every item is checked inside a live unit, not inferred from arguments.

- **Readable:** `/usr` with the `/bin`, `/lib` and `/lib64` links, `/etc/ld.so.cache`, the tools root, the lease's `bootstrap`, and `bridge/lock`, `bridge/state` and `bridge/responses`. `snapshot.bundle` is added only in `initialize` mode and `baseline.bundle` only in `baseline` mode.
- **Writable:** `work`, `home`, `bridge/requests` and `/tmp`, the unit's private tmpfs. Read-only and writable grants stay disjoint, as the [supervisor note](../implemented/2026-09-05-local-execution-supervisor.md#supervisor-and-worker) requires.
- **Everything else unreadable.** SRT bind-mounts `/` read-only and then hides each other top-level directory under a tmpfs and each top-level file under `/dev/null`; `/sys` is named in `DENY_READ` and is likewise an empty tmpfs. The direct launch starts from bubblewrap's empty root and binds only the lists above, so `/sys` is absent or an empty tmpfs on both paths. It also leaves the host mount table out of the sandbox's, which is stricter than SRT.
- **Devices and processes:** `--dev /dev` and `--proc /proc` in the new PID namespace.
- **Namespaces and privileges:** `--unshare-user` mapping the execution UID to itself, `--cap-drop ALL`, `--unshare-pid`, `--unshare-net` with only loopback, `--new-session` and `--die-with-parent`; the unit keeps `NoNewPrivileges=yes`.
- **Seccomp:** the same `apply-seccomp` binary, so `socket(AF_UNIX, …)` and `io_uring_setup`, `io_uring_enter` and `io_uring_register` fail with `EPERM`. It runs the command as a child of a non-dumpable PID 1 in a nested user, PID and mount namespace, so the command cannot see or trace an unfiltered process.
- **Environment:** the launcher's variables as today, plus `SANDBOX_RUNTIME=1`. The launcher passes `TMPDIR=/tmp`, but SRT replaces it with `/tmp/claude`, a directory that does not exist, so tools fall back to `/tmp`; the direct launch keeps `TMPDIR=/tmp`. The proxy family (`HTTP_PROXY`, `ALL_PROXY`, `NO_PROXY` and the rest), `GIT_SSH_COMMAND`, `GIT_CONFIG_PARAMETERS`, the Java proxy agent in `JAVA_TOOL_OPTIONS` and `CLAUDE_CODE_HOST_*` disappear. A network attempt fails as unreachable instead of being refused by a proxy.
- **Write denies relative to the working directory:** SRT denies writes to `.bashrc`, `.git/hooks` and similar paths below its own working directory, which is the root-owned bootstrap, so they match nothing today and are not part of the contract.
- **Exit status:** `bwrap` returns the command's status, where SRT maps `SIGINT` and `SIGTERM` to 0 and other signals to 1. The shell loop reports command status through its channel and is unaffected; the `initialize`, `baseline` and `game` modes read the unit's status, so their failure codes must stay distinguishable.
- **No fallback:** a failed check or launch never runs the command outside the sandbox.

### Tool integrity and delivery

[Coordinated worker delivery](../implemented/2026-10-19-coordinated-worker-delivery.md) installs new worker sources with each application release but leaves the tools directory to the operator. The launcher therefore relies only on files every prepared tools directory already holds: `bwrap` from the extracted `bubblewrap` package that [prepare-tools.sh](../../executor-service/prepare-tools.sh) installs, and `apply-seccomp` from the pinned SRT package.

- `apply-seccomp` is pinned by the SHA-256 of SRT 0.0.75's `vendor/seccomp/x64/apply-seccomp` in the worker source. `bwrap` comes from the host's distribution, so it has no portable digest; it must be a root-owned regular file that the execution account cannot write, at a minimum version the implementation states.
- The supervisor checks both at startup and refuses to start when either fails. A host that was never prepared then fails during delivery's worker start, which restores the previous source generation, instead of failing each command after the application was replaced. The launcher repeats the checks before every launch.
- Delivery's post-deployment read-only MCP command exercises a real sandbox, so a launch failure after a successful start still keeps the deployment pending.

## Acceptance criteria

- One probe script runs inside both launch paths on the same host and dumps the mount table, a fixed list of readable and writable probe paths, environment variable names, the visible processes and the `Cap*`, `NoNewPrivs` and `Seccomp` lines of `/proc/self/status`. The two dumps differ only by the deltas above.
- These negative tests pass on a real host, run by the [native adapter probe](../../executor-native/README.md) as a root-only acceptance:
  - reading `/etc/passwd`, `/root`, `/home`, `/srv`, `/sys`, another lease's session directory and the copy pool fails, and only the sandbox's own processes are visible;
  - writing the tools root, `bootstrap`, the bridge's read-only paths, `/usr` and `/` fails, while `work`, `home`, `bridge/requests` and `/tmp` accept writes;
  - creating an `AF_UNIX` socket and calling `io_uring_setup` fail with `EPERM`;
  - a TCP connection to an external address and to a service on the host's loopback fails;
  - `CapEff` is zero, `NoNewPrivs` is 1 and `Seccomp` is 2, and the command cannot trace or signal `bwrap` or the outer `apply-seccomp`;
  - a fork bomb and a memory bomb stop at the unit's `TasksMax` and `MemoryMax`, and the stop path's empty-cgroup assertion holds;
  - a tampered `apply-seccomp` or a `bwrap` the execution account can write stops the supervisor at startup and the launcher before any command;
  - no file is left on the host after the unit stops.
- The [sandbox toolkit](../implemented/2026-09-05-local-execution-supervisor.md#sandbox-toolkit) acceptance (image, PDF, Office, archive and Git operations), the game worker's tests and the existing 40 native adapter scenarios pass unchanged.
- On the same host, a fresh `true` unit uses at most a third of today's CPU and memory, and an idle unit holds at most a third of today's tasks.
- A 50-agent load round on each path, with the same `maxLiveUnits` and `maxStartingUnits`, shows a lower `workerReadyMs` distribution and no more `EXECUTION_CAPACITY` refusals on the direct path. The executor README then recommends `maxLiveUnits`, `maxStartingUnits` and the slice's `MemoryMax` and `TasksMax` from the measured unit size.

## Alternatives

- **Keep SRT and tune it.** The compile cache shipped; module evaluation, the proxies and the bridges remain, and SRT offers no setting for the rest.
- **Raise `maxLiveUnits` and `maxStartingUnits`.** More warm units spend memory the host does not have, and more concurrent starts saturate the CPU that startup admission protects; the per-unit cost stays.
- **Call SRT's internal Linux wrapper from a small Node.js entry** with network restriction and no proxy sockets. It removes the proxies and bridges but keeps Node.js's startup and memory, and depends on unexported internals.
- **Fork SRT** to remove the proxy coupling. Poketto would maintain a Node.js fork that still pays Node.js's startup.
- **A larger host.** More vCPUs and memory buy capacity linearly and can be combined with this proposal; the per-unit cost stays.

## Consequences

Poketto owns the sandbox argument list. A bubblewrap or `apply-seccomp` update arrives only through the pinned tools and needs the equivalence probe again, and a fix SRT ships for its own argument generation no longer applies automatically. SRT remains in the tools for `apply-seccomp`, and Node.js remains for the game runtime inside the sandbox. When this is implemented, the same change updates the launcher's statement that only SRT interprets an execution payload, the supervisor note's statement that commands run through pinned SRT, the measurements in [per-lease command sandboxes](../implemented/2026-09-16-per-lease-command-sandboxes.md), and the worker configuration sizing in the [executor README](../../executor-service/README.md#runtime).
