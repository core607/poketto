# Per-Lease Command Sandboxes

Date: 2026-09-16
Partially superseded by: [Native repository consumer](2026-11-01-native-repository-consumer.md).
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

Native sessions own a persistent shell under one contained runtime; the [native consumer decision](2026-11-01-native-repository-consumer.md) separates that runtime from application-owned copy retention. The shell's working directory, environment, functions, aliases, private `/tmp` and background processes survive normal commands. They never cross session identities. Operator profiles and enclosing cgroups bound memory, CPU, tasks and temporary storage, including background work.

The fixed [Rust driver](../../executor-service/rust/sandcrab-shell/src/lib.rs) waits on pipes and the shell's pidfd; the [native channel](../../executor-service/rust/sandcrab-core/src/command.rs) bounds host-side framing and output. Completion closes command attribution: late background output cannot enter the next command. At most 128 output readers remain retained; older writers receive `EPIPE` or `SIGPIPE` when their readers close.

Timeout, output exhaustion, shell failure and lifecycle cancellation require whole-runtime containment. An execution ID correlates a command but grants no principal, scope or capability. Native host calls are bound to the current execution, while the application rechecks current grants. A lost command result does not authorize replay. The application can later replace a contained runtime while preserving its durable volume and authoritative baselines; response metadata exposes state loss.

## Alternatives

- Keep per-command units and add a `cwd` parameter and a persisted environment file. Each is a protocol field for one symptom; `/tmp` and background processes still reset. Not adopted.
- On timeout, kill only the command's process group and keep the shell. Commands run in the shell's own process so that builtins persist, so a builtin has no separate process group to kill, and a process-group kill cannot show the control group empty, which is the containment proof every stop path relies on. Not adopted.
- Tie the unit to the lease with no idle end. This retains idle units and lets forgotten background processes run for days. The idle window bounds that; an operator can raise it.
- A warm pool shared across leases. A sandbox must never outlive its lease's identity. Not adopted.
- More retained warm sessions trade memory and tasks for fewer starts. Admission must bound initialization as well as resident work; the [native profile and slice](../../executor-service/rust/sandcrab/README.md) own effective limits. Queueing unbounded cold requests would consume resources while existing commands wait.

## Consequences and risks

State loss is visible instead of constant: runtime termination clears shell state and background processes while application copy retention preserves files. Unit-level CPU and memory counters span commands, so per-command attribution requires observations at command boundaries. Output retention and open file descriptors remain bounded even when shell-created background writers persist.

## Verification

`test_shell_loop.py` drives the native binary. `NativeRepositoryPoolTests`, `NativeRepositoryLifecycleTests` and [native consumer acceptance](../../executor-native/sandcrab.md) cover application reuse, refusal and containment. The direct-launch decision retains dated idle/startup measurements; those fixtures exclude HTTP and client latency.

Related: [account working copies](../implemented/2026-09-14-account-working-copies.md) own copy retention, including that a timeout keeps the copy; the [local execution supervisor](../implemented/2026-09-05-local-execution-supervisor.md) keeps the privileged boundary this note builds on.
