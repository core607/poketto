# Native phase replay

On the disposable Linux systemd host used by [native HTTP acceptance](README.md), pass
`native-host.py --phase-evidence NEW_DIRECTORY` with the same `--runtime`, `--binary`,
`--tools` and `--java` paths. It runs real authenticated MCP calls against a fresh
application: first parent, five warm reads each with empty, 64 KiB and 4 MiB upper
content, one missing-copy refusal, upper recovery after restarting only its owned
Sandcrab service, and a one-second command timeout followed by a retained-file check.
It exits after cleanup. It cannot be combined with `--games`.

`--phase-recording` additionally enables the optional
[native JFR event](../../deploy/observability/README.md#native-execution-phases), with
no default JFR profile or stack traces. The recording is bounded to 16 MiB and 900
seconds. The driver attaches as the owned application UID after checking its PID,
executable and synthetic entry point. It never targets another application process.

Run the same replay without recording and with the previous runtime to measure
instrumentation overhead. Keep resource limits and workload fixed and alternate
comparison order. First-parent means a fresh application and session; the host page
cache is not dropped. HTTP wall time includes stateless MCP initialization and teardown.

`summary.json` holds aggregate HTTP and nested phase timings, calling-thread CPU,
archive bytes, cgroup CPU/throttling/I/O pressure and anon/file/kernel memory. Nested
phase durations overlap and must not be summed. Cgroup I/O counts physical writes,
which can complete after a request; archive bytes are a separate measure. Endpoint
memory is not a peak. The application, native pool, daemon and workloads are sampled;
the pool includes its daemon and workloads, so their hierarchical counters must not be
added together. PostgreSQL and the client are outside these cgroups.
Throttling counters describe those cgroups' quotas; per-sandbox quota counters are not sampled.
When delegated daemon/workload cgroups lack `io.stat`, their I/O values are `null`;
application and native-pool I/O counters remain required.

Raw request windows, cgroup samples and the JFR recording stay in CI artifacts or
private operator storage; commit only the aggregate. Retain failed attempts. Missing
or ambiguous request phases, data loss, unexpected event types, command failures and
OOM fail the measurement. Require both its result and controller cleanup to pass.
Five serial repeats do not establish capacity or a tail-latency SLO.
