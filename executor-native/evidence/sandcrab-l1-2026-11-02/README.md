# L1 native seccomp evidence

Date: 2026-11-02

These local measurements use an isolated Ubuntu 24.04 systemd container with a
private cgroup namespace, a 2 GiB memory limit, no swap and two CPUs. They use
synthetic content and no model calls. [index.json](index.json) identifies every
retained receipt, both failed launcher attempts and the candidate binaries.
Exact-head CI, fresh-distribution readiness and canonical 5 GB results are separate
delivery checks.

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
