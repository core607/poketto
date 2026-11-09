# JVM footprint comparison

`jvm-footprint.py` measures the full synthetic Spring/PostgreSQL/native HTTP/MCP
fixture with Native Memory Tracking, process RSS and cgroup anon/file/kernel
observations. It uses the existing judged load: eight agents, 60 seconds, a
15-second warmup and uniform one-to-three-second think time. Each application
has 1536 MiB, 150% CPU quota, 384 tasks and zero swap. The native pool and disposable
database retain their own finite limits. Host and enclosing-cgroup preflight
reserve headroom; a 90% memory threshold stops the attempt.

The baseline uses the deployed 65% heap ratio and records its ergonomic collector.
Candidates change one mechanism: a 50% heap ratio, compact object headers, G1,
smaller idle HTTP/database pools, or an AOT cache. Request thread and connection
maximums stay unchanged. Two repetitions reverse candidate order. A baseline
warmup and separately bounded AOT training precede the comparison; every attempt
and cleanup outcome remains in the output.

An eligible candidate must save at least 16 MiB and 5% of median private RSS anon
in both repetitions, with at least 20 judged calls, success of 99% or better,
p95 no worse than the larger of one second and 110% of baseline, and at least
90% of baseline goodput. It also needs zero OOM and confirmed cleanup. Eligibility
requires later validation against the actual deployment artifact and affected
capabilities; this short synthetic load alone does not set a production heap cap.

NMT runs before, during and after load in every comparison arm. GC pause logs and the entire sampling
window, including warmup and cleanup, are retained. JVM reservations, committed
bytes, process RSS and cgroup charges have different meanings. Shared JDK/library
caches stay warm; file charges can move when a retired cgroup disappears. The
decision therefore credits private anonymous residency, not lower file charges.
AOT training uses process/cgroup observations because
[Temurin 26.0.2 crashes while reporting NMT in record mode](https://github.com/core607/poketto/issues/414).
Its training cost is reported separately, without NMT category claims. Normal
startup and cache-consumption comparisons require all three NMT observations.

A byte-verified JAR of the staged acceptance classes gives every arm the same
classpath shape. AOT training records that application and a separate bounded
JVM assembles the cache; consumption requires `AOTMode=on` to reject fallback.
The cache stays outside published evidence. Its validity depends on the exact
application, JDK, OS and architecture under the [Java 26 command contract](https://docs.oracle.com/en/java/javase/26/docs/specs/man/java.html).
A production image must train and verify its own artifact before adoption.

The [completed comparison](../../executor-native/evidence/2026-11-09-jvm-footprint/summary.json)
retains both orders and all missed thresholds. Compact headers, a smaller heap,
G1 and smaller pools did not meet the selection rule. AOT met the fixture rule,
but the production JAR/JRE cache fails during JVM initialization in both required
and automatic modes ([tracked limitation](https://github.com/core607/poketto/issues/417)).
Production JVM settings remain unchanged, so this experiment frees no deployed
memory for sandboxes. Reconsider AOT only after artifact compatibility and affected
capabilities pass; fixture savings alone do not qualify it.

Run on a disposable root-capable systemd Linux host with Docker, XFS utilities,
the staged acceptance runtime, the pinned service bundle and prepared tools:

```sh
python3 acceptance/clients/jvm-footprint.py --runtime /prepared/runtime \
  --binary /prepared/bin/sandcrab --tools /prepared/tools --java /prepared/jdk/bin/java \
  --work /var/lib/jvm-inputs-new --evidence /operator/evidence/jvm-new
```

`--smoke` checks one-agent, 15-second cases and supplies no selection decision.
The [protected entrypoint](../../.github/ci/sandcrab-jvm-check.sh) prepares inputs
and retains raw receipts in its CI artifact. Only an aggregate `summary.json`
belongs in source control. A missed performance threshold becomes a tracked
limitation; it does not trigger repeated tuning in this change.
