# L1 native seccomp evidence

Date: 2026-11-02

These local measurements use an isolated Ubuntu 24.04 systemd container with a
private cgroup namespace, a 2 GiB memory limit, no swap and two CPUs. They use
synthetic content and no model calls. [index.json](index.json) identifies every
retained receipt, both failed launcher attempts and the candidate binaries.
The canonical and CI receipts below identify their own source, host and limits.

The [W0 baseline](https://github.com/core607/poketto/blob/be106fac0af240af362e31749113fa3909d3bab9/executor-native/evidence/sandcrab-w0-2026-11-02/README.md)
and [candidate footprint](native-l1-footprint1.json) report:

| Measure | W0 | L1 candidate |
|---|---:|---:|
| Static Sandcrab binary bytes | 4,125,600 | 4,125,600 |
| Prepared tool directory bytes | 375,103,567 | 9,255,073 |
| Runtime executable surfaces | 14 | 13 |

Prepared bytes include downloaded archives. The candidate directory contains
distribution sandbox tools; the consumer's optional Node interpreter is prepared
separately. The executable inventory includes optional sessions and durable volumes,
excluding shared libraries and workload programs. This comparison supports the
provisioning-footprint claim; the single-session local smoke does not establish a
memory or capacity improvement across different kernels and limits.

The [final native probe](native-l1-probe2.json) records every seccomp rule and ABI
guard, allowed controls, visible-process filters, descriptor consumption and
lifecycle cleanup. The [launcher snapshot](native-l1-launcher3.json) checks mounts,
capabilities, network denial and an untraceable, filtered PID 1. Its separate
[receipt](native-l1-launcher3-receipt.json) includes invalid-tool refusals and cleanup.

The [Java consumer](native-l1-java-all1-receipt.json) passes 40 checks plus lease
expiry after process loss. The [game consumer](native-l1-java-games1-receipt.json)
passes gameplay, storage, exhaustion, descendants, isolation and supervisor-loss
recovery with the separately provisioned Node. Both report cleanup. These are real
sandbox executions with synthetic authentication, not browser or production acceptance.

## First use

Run [41371587448](https://github.com/core607/poketto/actions/runs/41371587448)
completed all five environments with cleanup. The tested PR merge tree is
`8c1624e68633daefbcb774099f5c55cbe49d0c3e`, with the same candidate binary digest.

| Environment | Commands | Manual edits | W0 seconds | L1 seconds | Receipt |
|---|---:|---:|---:|---:|---|
| Native Ubuntu 22.04 | 12 | 2 | 19.16 | 13.19 | [JSON](41371587448/13108419936/readiness-native.json) |
| Native Ubuntu 24.04 | 13 | 3 | 15.25 | 9.52 | [JSON](41371587448/13108489805/readiness-native.json) |
| Ubuntu 22.04 container | 13 | 3 | 20.00 | 11.15 | [JSON](41371587448/13109209404/readiness-ubuntu22.json) |
| Ubuntu 24.04 container | 13 | 3 | 12.75 | 6.89 | [JSON](41371587448/13109461938/readiness-ubuntu24.json) |
| Debian 12 container | 13 | 3 | 10.44 | 4.60 | [JSON](41371587448/13109680658/readiness-debian12.json) |

The fresh containers have neither Node nor npm installed and execute the first
sandboxed Python command. Native GitHub runners already contain them, so those
two runs alone cannot establish their absence as a requirement. Timings exclude
human editing, compilation and controller/image preparation; containers share
the host kernel. These are single automated lower bounds, not a stable speedup
estimate. Command and edit counts remain unchanged, so readiness still misses
the target. The manual recipe is not the later released quickstart.

## Integration

[CI 41371587379](https://github.com/core607/poketto/actions/runs/41371587379)
passes at implementation head `47d58087e9a0ebb03bf0024d9eefa5a5a962dbaa`, testing
merge tree `8c1624e68633daefbcb774099f5c55cbe49d0c3e`:

- [66 scripted Inspect repetitions](41371587379/13109324439/summary.json), all with
  cleanup, zero model tokens and real native execution.
- [21 published Python/Kotlin SDK checks](41371587379/13109324439/sdk.json) and
  [10 context checks](41371587379/13109324439/contexts.json), with cleanup.
- [42 native isolation/lifecycle checks](41371587379/13109324439/native.json),
  including each filter rule and ABI guard, with cleanup.

The [native measurement smoke](41371587347/13110200275/smoke.json), footprint,
cache comparison and three startup traces also completed with cleanup. Its
artifact's matrix label says 5 GB, but smoke deliberately uses a 1 GiB slice;
it is not the canonical capacity result. The index retains all seven smoke files.

## Canonical 5 GB comparison

Run [41371500809](https://github.com/core607/poketto/actions/runs/41371500809)
measures implementation head `47d58087e9a0ebb03bf0024d9eefa5a5a962dbaa` under the
unchanged W0 protocol: daemon and sandboxes share a requested 5 GB budget
(4,999,999,488 kernel bytes after page rounding), two CPUs and zero swap.
All four collectors and API/host cleanup completed. The
[density receipt](41371500809/13110363450/density.json) retains all attempts.

| Measure | W0 final 5 GB | L1 5 GB |
|---|---:|---:|
| Largest passing tested stage | 50 | 50 |
| 50-agent judged p95, three repetitions | 548.22–622.95 ms | 354.95–377.00 ms |
| 50-agent cold-start median, three repetitions | 243.26–243.47 ms | 116.15–121.34 ms |
| First failed stage | 100 | 100 |
| Failure at 100 | 55.07% success, 775 refusals | p95 3,712.21 ms, 100% success |
| OOM kills | 0 | 0 |
| Median daemon idle RSS | 3,018,752 bytes | 3,084,288 bytes |

All 25/50-agent repetitions pass. The first 100-agent repetition fails the p95
limit, so 200/400 are not attempted. Capacity has not increased. W0's earlier
retained 5 GB attempt also passed 50 and failed 100, at 13,770.86 ms p95 with
100% success. That attempt remains separate; it does not replace the final W0
failure mode. These are independent hosted runners, not a paired hardware trial,
so the observed latency changes do not establish a stable causal speedup.

The [full footprint](41371500809/13110363450/footprint.json) records five-sample
median increments over the idle daemon, including the minimal workload and daemon
increment. Values are pool memory per item, not summed process RSS:

| Cohort | W0 idle session bytes | L1 idle session bytes | W0 finite run bytes | L1 finite run bytes |
|---|---:|---:|---:|---:|
| 1 | 3,584,000 | 3,502,080 | 3,346,432 | 2,686,976 |
| 10 | 2,933,555.20 | 2,736,128 | 2,448,179.20 | 2,218,803.20 |
| 50 | 2,886,615.04 | 2,672,476.16 | 2,429,911.04 | 2,194,227.20 |

The full run confirms the binary/tool inventory above. Its
[cache comparison](41371500809/13110363450/cache.json) still observes 819,200,000
resident document bytes across ten reflink copies versus 81,920,000 for a shared
read-only tree, starting from zero residency. L1 does not implement shared templates.
All [three startup traces](41371500809/13110363450/cold-start.json) are complete;
their diagnostic sessions and tracer overhead remain distinct from the untraced
cold-start distribution.
