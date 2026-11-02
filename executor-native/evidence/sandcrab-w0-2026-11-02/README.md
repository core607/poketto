# Sandcrab W0 baseline

Date: 2026-11-02

These measurements use synthetic data, the standalone native API and no model
calls. The [protocol](../../../experiments/sandcrab-density/README.md) owns workload,
scoring and measurement boundaries. [index.json](index.json) lists each retained
artifact, its archive digest and every copied file's SHA-256. Receipt timestamps
are UTC. All three canonical budgets and the first-use baseline are complete.

The measured static binary is 4,125,600 bytes, SHA-256
`db8b81132e7a3cc71c43c6c39e02e77f1cc013e66aa5bacc79804113fc48f791`.
The canonical driver uses head `6481ea1b8d3aca64c54529c4fdd9c11aa2755aa0`, merged
as `c805d59bf215bc8008e317fb578eb92d0859dc0d`. Each receipt separately identifies
its source, source-file hashes, kernel, host capacity and effective limits.

## Density

Run [41362079392](https://github.com/core607/poketto/actions/runs/41362079392)
completed the 8/5/4 GB baselines. Daemon and sandboxes
share two CPUs, with swap disabled; the
controller and system manager are outside that slice. Budgets are decimal GB.

| Budget | Largest passing tested stage | First failed stage | Failure | Receipt |
|---|---:|---:|---|---|
| 4 GB | 25 | 50 | p95 1,092.60 ms | [JSON](41362079392/13106576319/density.json) |
| 5 GB | 50 | 100 | 55.07% successful commands | [JSON](41362079392/13107422313/density.json) |
| 8 GB | 100 | 200 | 26.02% successful commands | [JSON](41362079392/13108911083/density.json) |

The 4 GB run passed all three 25-agent repetitions at p95 183.01–184.01 ms.
Its first 50-agent repetition missed the one-second limit despite 100% command
success and zero OOM kills. All four collectors and their cleanup completed,
including three complete startup timelines. Larger stages were not attempted.

The final 5 GB run passed all three 50-agent repetitions at p95 548.22–622.95 ms.
At 100 agents, 43 commands timed out during warm-up and their sessions closed.
The judged window retains 775 `SESSION_NOT_READY` refusals among 1,725 attempts,
so its 882.05 ms p95 cannot make the stage pass. No OOM kill was observed; all
four collectors and cleanup completed. The earlier 5 GB result below is retained
separately and is not substituted for this failure mode.

The 8 GB run passed all three 100-agent repetitions at p95 704.17–822.58 ms.
At 200 agents, 147 commands timed out during warm-up. The judged window contains
949 successes and 2,698 `SESSION_NOT_READY` refusals; its 177.05 ms p95 does not
make the stage pass. All three budgets report zero OOM kills, complete collectors
and verified API/host cleanup. These runs do not establish the cause of the
timeouts or production capacity. Separate GitHub runners emulate the same limits;
their receipts retain host and kernel information rather than assuming identical
physical hosts.

The retained [first 5 GB attempt](41357939802/13106115656/density.json) completed
all three repetitions at 25 and 50 agents. At 100 agents, the first repetition
failed p95 and ended the search. Every judged command succeeded and no OOM kill
was observed. This attempt used `97cddfaf`; its density, footprint and cache
sources and binary match the corresponding collectors at `6481ea1b`.

| First attempt stage | Repetitions completed | Judged p95 range | Result |
|---|---:|---:|---|
| 25 | 3 | 174.45–178.60 ms | Pass |
| 50 | 3 | 386.00–393.18 ms | Pass |
| 100 | 1 | 13,770.86 ms | Fail: p95 |

Thus 50 is that attempt's largest passing tested stage; 200 and 400 were not
attempted. Its workflow ultimately failed in the separate old startup observer,
whose [failed receipt](41357939802/13106115656/cold-start.json) remains alongside
the completed density result. This result does not establish production capacity.

## Fixed costs and file cache

Median daemon idle RSS is 3,018,752 bytes at 4/5 GB and 3,014,656 bytes at 8 GB.
Per-item increments below are
differences between five-sample medians, including the minimal bash/sleep workload
and daemon increment. They are not an estimate of an absent process.

| Budget/attempt | Cohort size | Bytes per idle session | Bytes per concurrent finite run |
|---|---:|---:|---:|
| [4 GB final](41362079392/13106576319/footprint.json) | 1 | 4,030,464 | 2,437,120 |
| 4 GB final | 10 | 2,965,504 | 2,493,235.20 |
| 4 GB final | 50 | 2,891,612.16 | 2,401,730.56 |
| [5 GB final](41362079392/13107422313/footprint.json) | 1 | 3,584,000 | 3,346,432 |
| 5 GB final | 10 | 2,933,555.20 | 2,448,179.20 |
| 5 GB final | 50 | 2,886,615.04 | 2,429,911.04 |
| [8 GB final](41362079392/13108911083/footprint.json) | 1 | 3,788,800 | 2,969,600 |
| 8 GB final | 10 | 2,965,094.40 | 2,509,619.20 |
| 8 GB final | 50 | 2,892,103.68 | 2,433,843.20 |
| 5 GB first attempt | 1 | 3,158,016 | 2,953,216 |
| 5 GB first attempt | 10 | 2,889,318.40 | 2,517,401.60 |
| 5 GB first attempt | 50 | 2,892,185.60 | 2,445,148.16 |

The prepared tool tree occupies 375,103,567 bytes, including downloaded archives
and preparation tools. The inventory names 14 distinct runtime executable
surfaces, including optional sessions and durable volumes; it excludes their
shared-library closure and arbitrary workload tools. Node/npm/SRT preparation is
reported separately from the static daemon.

The full cache comparisons at
[4 GB](41362079392/13106576319/cache.json),
[5 GB](41362079392/13107422313/cache.json) and
[8 GB](41362079392/13108911083/cache.json) use ten readers,
each reading the same seeded 20,000-document corpus. Every document page starts
nonresident; public API commands verify all document bytes and the shared mount's
read-only flag.

| Layout | Distinct file inodes | Resident document bytes after reads | Cgroup file-cache increase |
|---|---:|---:|---:|
| Ten reflink copies | 200,000 | 819,200,000 | 819,200,000 |
| One shared read-only tree | 20,000 | 81,920,000 | 81,920,000 |

The observed document cache differs by a factor of ten. This measures existing
read mounts and supports evaluating shared templates; it does not measure an
overlay template implementation. Both cases report zero OOM and verified cleanup.

## Startup

The untraced density receipts record session submission through observed readiness,
after the caller has prepared its volume. At the largest passing stage, the three
repetitions' median cold starts are 243.26–243.38 ms (4 GB), 243.26–243.47 ms (5 GB)
and 232.35–232.38 ms (8 GB).

The separate startup observer records three complete timelines per budget.
Values below are median milliseconds from API submission, not additive durations:

| Budget | D-Bus client exec | Session slice created | systemd-run exec | Sandbox bwrap exec | Bash exec | API ready observed |
|---|---:|---:|---:|---:|---:|---:|
| [4 GB](41362079392/13106576319/cold-start.json) | 9.50 | 14.44 | 62.72 | 92.69 | 99.49 | 110.76 |
| [5 GB](41362079392/13107422313/cold-start.json) | 9.72 | 15.05 | 63.32 | 90.62 | 97.27 | 100.74 |
| [8 GB](41362079392/13108911083/cold-start.json) | 9.37 | 12.20 | 57.18 | 76.99 | 81.62 | 90.10 |

These diagnostic sessions have no cloned workload volume and run with tracing
overhead. They decompose startup; the untraced density distributions own latency
comparisons. The receipts retain all events, tool versions and tracer limits.

## First-use baseline

The manual recipe runs the README's real Python command and checks `42`.
These final-head receipts all completed installation and cleanup:

| Environment | Commands | Manual configuration edits | Automated seconds | Receipt |
|---|---:|---:|---:|---|
| Native Ubuntu 22.04 | 12 | 2 | 19.16 | [JSON](41363094547/13105784901/readiness-native.json) |
| Native Ubuntu 24.04 | 13 | 3 | 15.25 | [JSON](41363094547/13105962575/readiness-native.json) |
| Ubuntu 22.04 container | 13 | 3 | 20.00 | [JSON](41363094547/13106560328/readiness-ubuntu22.json) |
| Ubuntu 24.04 container | 13 | 3 | 12.75 | [JSON](41363094547/13106590170/readiness-ubuntu24.json) |
| Debian 12 container | 13 | 3 | 10.44 | [JSON](41363094547/13105528708/readiness-debian12.json) |

All miss the readiness target of at most three commands and zero manual edits.
An edit counts one configuration file or host policy change. The extra command
and edit on applicable hosts allow the required user namespaces; restoration is
verified. Native jobs include checkout and candidate-artifact download, while
containers use local copies and share the host kernel. Human editing, compilation,
controller bootstrap and image preparation are excluded, so elapsed time is a
lower bound. The later release quickstart must be measured verbatim.

## Retained failures and verification

- Run `41357939802`: 8/4 GB stopped at the old startup trace preflight; the 5 GB
  density, footprint and cache measurements completed before that observer failed.
  Missing trace events are observer failures, not capacity failures.
- Run `41361629602`: the repaired observer produced three complete native startup
  timelines at the reviewed head, with API and host cleanup. Its
  [receipt](41361629602/13105062638/cold-start-smoke.json) records tracing overhead;
  untraced latency distributions belong to the density receipts.
- Runs `41362289149` and `41362744544`: Debian lacked `sysctl` on an AppArmor-enabled
  host, so its manual recipe failed and could not confirm recipe cleanup. The
  owning container was removed. Both attempts are retained. Run `41363094547`
  explicitly prepares `procps` and verifies policy restoration and cleanup.

The PR #358 integration gate passed all
[66 scripted Inspect cases](41362078989/13105797895/summary.json),
[21 SDK checks](41362078989/13105797895/sdk.json) through the published
Python/Kotlin clients, and cleanup. PR #359 independently passed the same
[eval](41363094586/13106696163/summary.json) and
[SDK](41363094586/13106696163/sdk.json) gates. Scripted model responses drive real
native sandboxes; they do not establish paid-model quality.
