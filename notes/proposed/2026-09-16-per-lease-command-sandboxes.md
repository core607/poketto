# Per-Lease Command Sandboxes

Date: 2026-09-16
Status: Proposed

## Problem

The worker runs every command in its own transient systemd unit: `systemd-run` starts the launcher, SRT and a shell, the command runs, and the unit is stopped, its control group is verified empty and its private `/tmp` is discarded (`SystemdBackend.run` in [worker.py](../../executor-service/worker.py)). The account copy persists, but everything else an agent builds up does not: the working directory returns to the repository root, exported variables and shell functions vanish, `/tmp` is emptied and background processes die. Agents compensate with `cd … && …` chains and files that re-create their environment on every call.

[Account working copies](../implemented/2026-09-14-account-working-copies.md) kept per-command sandboxes and deferred runtime reuse to a measurement. The measurement, taken on the author's production host (2 vCPU, 8 GB) on 2026-09-16:

- An idle sandbox (`sleep` as the command) charges 30–37 MB to its control group (peak 38 MB) and holds 22 tasks in 12 processes: Node.js running SRT, three `socat` proxies, two `bwrap`, two `bash`, two seccomp helpers and the command. Startup consumes about 0.36 s of CPU.
- The unit for `true` lives 0.46–0.49 s; most real commands that day lived 0.4–0.8 s, a few about 6 s. An end-to-end `repo_exec true` took about 1.6 s from the author's location, of which 0.37 s is the plain HTTPS round trip. Sandbox creation and teardown is therefore about 0.5 s of each call; the rest is network and host bookkeeping.

Reuse saves half a second per command. The reason to adopt it is state continuity: a sandbox that lives as long as its lease removes the working-directory, environment and temporary-file resets that every command currently pays for.

## Proposal

One systemd unit per lease. The lease already is the session boundary: the application keeps one lease per account, workspace and reading scope, renews it while the worker is reachable, reuses it across commands and transports, and closes it only on capacity pressure, another client fencing the account, cancellation, revocation, worker restart or shutdown ([IsolatedRepositoryExecutor](../../src/main/java/io/github/core607/poketto/executor/internal/IsolatedRepositoryExecutor.java)).

- The unit starts with the lease's first command and carries today's per-command properties: the resource slice, `User`, `MemoryMax`, `TasksMax`, `CPUQuota`, `NoNewPrivileges`, the private `/tmp`, the read-only tool binds and `BindsTo=` the supervisor unit. `RuntimeMaxSec` no longer applies; the supervisor owns the unit's lifetime.
- Inside SRT, the launcher runs one persistent shell loop instead of `bash -c COMMAND`. The supervisor delivers each command through a per-lease host-to-sandbox channel beside the existing sandbox-to-host bridge FIFO; the loop evaluates it in the shell's own process, so `cd`, `export`, functions and aliases persist, and reports the exit status. Standard output and error are captured per command with the existing 4 MiB combined limit, 16 KiB previews and artifact handles.
- The working directory, environment, shell state, `/tmp` and background processes persist between commands of the same unit. Background processes count against the unit's `TasksMax` and `MemoryMax`, are frozen during captures like everything else in the control group, and end with the unit.
- The unit ends, and the lease continues, on a command timeout, an output-limit stop, or `idleUnitSeconds` (proposed default 1800) without a command. Every end uses the existing control-group kill and empty-cgroup assertion before the lease may start another unit. The next command starts a fresh unit lazily and its result carries `freshSandbox: true`, so an agent knows that shell state, `/tmp` and background processes are gone. Lease close, expiry, revocation, cancellation and startup cleanup stop the unit as they stop a command today.
- Frozen captures, bridge polling and command cleanup key off the lease's unit and keep working between commands.
- The `repo_exec` description stops promising a fresh repository root and an empty `/tmp` per command and documents `freshSandbox`.

## Alternatives

- Keep per-command units and add a `cwd` parameter and a persisted environment file. Each is a protocol field for one symptom; `/tmp` and background processes still reset. Not adopted.
- On timeout, kill only the command's process group and keep the shell. Commands run in the shell's own process so that builtins persist, so a builtin has no separate process group to kill, and a process-group kill cannot show the control group empty, which is the containment proof every stop path relies on. Not adopted.
- Tie the unit to the lease with no idle end. Simplest, and four idle sandboxes cost about 150 MB, but a forgotten background process would run for days. The idle window bounds that; an operator can raise it.
- A warm pool shared across leases. A sandbox must never outlive its lease's identity. Not adopted.

## Consequences and risks

- State loss becomes visible instead of constant: only timeout, output limit and idle end reset it, and the result says so.
- systemd's per-unit `Consumed` and memory-peak figures no longer describe one command; per-command resource attribution needs the control group's counters at command boundaries if it is still wanted.
- Output framing must close at the command boundary even when a background process keeps writing; later writes belong to no command and are discarded.
- The shell loop is new code inside the sandbox boundary. It stays in the fixed launcher, receives commands only through the supervisor's channel, and interprets nothing from the working copy.
- Worker and application ship together: the `freshSandbox` field and the idle setting are protocol additions.

## Acceptance

- Native probe: within one lease, `cd`, `export`, a file under `/tmp` and a background `sleep` survive into the next command; a timeout stops the unit, the lease renews, the next command reports `freshSandbox: true` and the previous control group was asserted empty; the same for the output limit; the idle window stops the unit while the lease keeps renewing; a frozen capture succeeds between commands; lease close, expiry, revocation and startup cleanup leave no unit or mount behind.
- Socket tests for the new result field and for `idleUnitSeconds` validation.
- Measurement after implementation: idle unit memory and per-command latency compared with the numbers above; the per-command saving is expected to be about 0.5 s.
- Documentation updated in the same change: the worker reference's process-boundary section, [usage](../../docs/usage.md) and its Chinese counterpart, the tool description, and the fresh-temporary-directory fact in [command timeout and explicit copy disposal](../implemented/2026-09-14-ephemeral-copy-lifecycle.md).

## Same-topic audit

[Account working copies](../implemented/2026-09-14-account-working-copies.md) is retained; this note is the measurement it deferred. [Command timeout and explicit copy disposal](../implemented/2026-09-14-ephemeral-copy-lifecycle.md) is retained: a timeout still keeps the copy, and only its fresh-`/tmp` fact changes at implementation. The [local execution supervisor](../implemented/2026-09-05-local-execution-supervisor.md) proposal keeps the privileged boundary this note builds on; its per-command process tree is what this note replaces.
