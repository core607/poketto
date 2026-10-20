# Per-Lease Command Sandboxes

Date: 2026-09-16
Status: Implemented

## Problem

Per-command systemd units discarded working directories, exported variables,
shell functions, private `/tmp` and background processes after every command.
The account's files survived, but agents had to reconstruct runtime state with
`cd` chains and environment scripts on every call.

[Account working copies](../implemented/2026-09-14-account-working-copies.md) kept per-command sandboxes and deferred runtime reuse to a measurement. The measurement, taken on the author's production host (2 vCPU, 8 GB) on 2026-09-16:

- An idle sandbox (`sleep` as the command) charges 30–37 MB to its control group (peak 38 MB) and holds 22 tasks in 12 processes: Node.js running SRT, three `socat` proxies, two `bwrap`, two `bash`, two seccomp helpers and the command. Startup consumes about 0.36 s of CPU.
- The unit for `true` lives 0.46–0.49 s; most real commands that day lived 0.4–0.8 s, a few about 6 s. An end-to-end `repo_exec true` took about 1.6 s from the author's location, of which 0.37 s is the plain HTTPS round trip. Sandbox creation and teardown is therefore about 0.5 s of each call; the rest is network and host bookkeeping.

The measurement identified about half a second of avoidable setup per command. The reason to adopt it is state continuity: reusing a sandbox across commands avoids repeated working-directory, environment and temporary-file setup.

## Decision

One systemd command unit per lease. The application keeps one lease per account copy, renews it while the worker is reachable, and reuses it across commands and transports. Capacity refusal closes it unless the worker confirms startup admission restored READY; another client fencing the copy, cancellation, revocation, worker restart and shutdown still close it ([SessionLifecycle](../../src/main/java/io/github/core607/poketto/executor/internal/SessionLifecycle.java)).

- The unit starts with the lease's first command and retains the isolation properties: the resource slice, `User`, `MemoryMax`, `TasksMax`, `CPUQuota`, `NoNewPrivileges`, the private `/tmp`, the read-only tool binds and `BindsTo=` the supervisor unit. `RuntimeMaxSec` no longer applies; the supervisor owns the unit's lifetime.
- Inside the sandbox, the launcher runs one persistent shell loop instead of `bash -c COMMAND`. The supervisor delivers each command through the unit's private stdin channel alongside the existing sandbox-to-host bridge FIFO; the loop evaluates it in the shell's own process, so `cd`, `export`, functions and aliases persist, and reports the exit status. The fixed driver is [shell_loop.py](../../executor-service/shell_loop.py); [command_channel.py](../../executor-service/command_channel.py) bounds its supervisor-side transport. Standard output and error are captured per command with the existing 4 MiB combined limit, 16 KiB previews and artifact handles.
- The working directory, environment, shell state, `/tmp` and background processes persist between commands of the same unit. Background processes count against the unit's `TasksMax` and `MemoryMax`, are frozen during captures like everything else in the control group, and end with the unit.
- The unit ends, and the lease continues, on a command timeout, an output-limit stop, a shell exit, or `idleUnitSeconds` (example 300; required integer from 1 through 86400) without a command. With the optional `maxLiveUnits`, a command that would start a unit first stops the least recently used idle units beyond the bound; when every other live unit is running a command, it is refused with `EXECUTION_CAPACITY` before it runs, and its lease stays usable. Memory follows active agents rather than every lease that ran a command in the last half hour, with idle overhead reduced by [direct sandbox launch](2026-10-18-direct-sandbox-launch.md). Every end uses the existing control-group kill and empty-cgroup assertion before the lease may start another unit. The next command starts a fresh unit lazily and its result carries `freshSandbox: true`, so an agent knows that shell state, `/tmp` and background processes are gone. Lease close, expiry, revocation, cancellation and startup cleanup stop the unit as they stop a command today.
- Frozen captures key off the lease's unit. Signed read captures accept an empty execution ID between commands, require current lease authority, and are released by the next command or unit cleanup. Idle baseline updates also freeze the unit. Host mutations still require the current running command.
- Bridge cleanup and execution-epoch changes happen with the unit frozen or empty. CLI processes inherit their command's `POKETTO_EXECUTION_ID`; the protected bridge state and request epoch reject late background requests and acknowledgements. The epoch correlates commands and grants no principal, scope or capability. Idle bridge polls return no request; a poll claims only its own execution's requests, and a command reset wakes it at once.
- The `repo_exec` description documents the shared lease state and `freshSandbox`; it promises no fresh repository root or empty `/tmp` per command.

## Alternatives

- Keep per-command units and add a `cwd` parameter and a persisted environment file. Each is a protocol field for one symptom; `/tmp` and background processes still reset. Not adopted.
- On timeout, kill only the command's process group and keep the shell. Commands run in the shell's own process so that builtins persist, so a builtin has no separate process group to kill, and a process-group kill cannot show the control group empty, which is the containment proof every stop path relies on. Not adopted.
- Tie the unit to the lease with no idle end. This retains idle units and lets forgotten background processes run for days. The idle window bounds that; an operator can raise it.
- A warm pool shared across leases. A sandbox must never outlive its lease's identity. Not adopted.
- A live-unit bound alone limits resident memory, but a larger working set can repeatedly replace warm units and saturate CPU with startup work. Independent startup admission reserves capacity before eviction and releases it at the shell greeting, so a long first command does not block later startups. Raising the live bound instead spends more memory; queueing every cold request occupies execution capacity while warm commands wait. Operators size the separate startup budget in the [worker configuration](../../executor-service/README.md#runtime).

## Consequences and risks

- State loss becomes visible instead of constant: timeout, output limit, shell exit, idle cleanup and lease replacement reset it, and the next result says so.
- Startup admission trades refusals for bounded initialization concurrency; it cannot promise enough resident memory or execution capacity for every lease. Its explicit reusable-lease reason avoids repeated close and attachment work during contention; replay-capacity, expiry and unconfirmed failures still require containment. Reuse requires the refusal journal to be durable and current authorization to pass.
- systemd's per-unit `Consumed` and memory-peak figures no longer describe one command; per-command resource attribution needs the control group's counters at command boundaries if it is still wanted.
- Each command has separate output FIFOs. The driver closes attribution at completion and drains later background bytes without adding them to another command. At most 128 readers, including the current pair, remain open; excess oldest readers close and later writers receive `EPIPE`/`SIGPIPE`. The existing combined output bound still stops the whole unit. After containment, the supervisor closes channel pipes before waiting for the host forwarding client so buffered output cannot block cleanup.
- The shell loop is new code inside the sandbox boundary. The driver is loaded from the root-owned bootstrap and receives commands only through the supervisor's channel; command payloads are evaluated inside the sandbox.
- Worker and application ship together: HELLO requires `leaseSandboxProtocol: 1`, every EXEC result contains a boolean `freshSandbox`, and worker configuration requires the idle setting. Missing or coerced protocol fields are rejected.

## Verification

`WorkerSocketTests`, `WorkerAnswerTests`, the worker's `test_shell_loop.py`, `test_command_channel.py` and `test_worker_startup.py`, and the [native adapter probe](../../executor-native/README.md) pin state continuity, resets, startup admission, `freshSandbox`, idle cleanup and containment. The direct-launch decision owns current idle/startup measurements; these fixture figures exclude HTTP and client latency.

Related: [account working copies](../implemented/2026-09-14-account-working-copies.md) own copy retention, including that a timeout keeps the copy; the [local execution supervisor](../implemented/2026-09-05-local-execution-supervisor.md) keeps the privileged boundary this note builds on.
