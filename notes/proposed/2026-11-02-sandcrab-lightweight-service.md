# Lightweight, Ready-to-Use Sandcrab

Date: 2026-11-02

## Problem

The [native service](../../executor-service/rust/sandcrab/README.md) has an independent
API, but installation requires manually prepared tools, accounts, systemd units
and profiles; durable volumes require XFS. [Tool preparation](../../executor-service/prepare-tools.sh)
installs Node.js and npm to obtain the pinned SRT seccomp helper. Each sandbox
starts through [systemd-run](../../executor-service/rust/sandcrab-core/src/unit.rs).
Independent API tests do not establish first-use cost or capacity on a small host.

The [service proposal](2026-10-26-sandcrab-sandbox-service.md) originally tied extraction
to consumer delivery and a four-week interval. Waiting does not demonstrate that a
new operator can install the service or that its fixed costs are acceptable.
Preserving consumer-specific operations can also grow the public surface without
helping another project.

## Proposal

### Priorities and ownership

Prioritize low footprint and first-use readiness, then concurrent agent density
without worsening those priorities. Reference hosts have two CPUs and 4 or 8 GB
of memory and do not require KVM. Sandcrab must not require a container runtime,
another host or another resident service. Same-host hardening is included;
kernel-boundary runtimes such as gVisor are outside this work.

Poketto is the first consumer, test bed and load generator. Sandcrab defines a
general API; a feature needs a use outside Poketto to remain in the daemon.
Poketto implements its own repository consistency and adapts when that API changes.
These priorities, workstreams and readiness criteria partially supersede the
service proposal's runtime requirements, proposed extensions and delivery order.
Its authorization boundaries, retained-data obligations and evaluation discipline
remain. There is no time-based extraction gate, and Poketto cutover does not block
extraction.

### W0: decision record and baseline

Land this decision record before implementation, then add
`experiments/sandcrab-density/` with a README, footprint script, public-API density
driver and breakdown measurements. The following numbers are targets and protocol
definitions, not measured results.

| Measure | Definition |
|---|---|
| Footprint | Static binary bytes, daemon idle RSS, fixed memory per idle session and finite run, external runtime dependency count and purpose |
| Readiness | Fresh Ubuntu 22.04, Ubuntu 24.04 and Debian 12: download to first `sandcrab run` in at most three commands, no manual configuration edits, under five minutes; record all three quantities |
| Density | Largest tested agent count with ok rate at least 0.99, judged p95 at most 1.0 s and zero OOM kills |

Run daemon and sandboxes together in a slice with `CPUQuota=200%`,
`MemorySwapMax=0` and `MemoryMax` set to 8, 5 or 4 GB. The 5 GB budget approximates
the space left beside the default [application containers](../../deploy/compose.yaml)
on an 8 GB host; it is not a production capacity claim. W0 and the final comparison
run all three budgets; intermediate changes may use 5 GB alone. Record the effective
kernel byte limits and runner capacity so a larger configured limit cannot stand
in for available host memory.

Use a seeded synthetic corpus of approximately 20,000 Markdown files and a small
Git repository. Each of N agents repeatedly thinks for uniform(3, 10) seconds and
runs one command: `rg` 35%, `cat`/`head` 20%, `sed -i` 15%, `ls`/`find` 10%,
`git status`/`diff` 10%, and `python3 -c` 10%. Stages are 25, 50, 100, 200 and 400
agents, stopping at the first failure. Use only the public API, with no Poketto
application. Fix seeds, stage durations, warm-up, repetitions, latency boundaries
and scoring before collecting results; retain refused and failed attempts in the
judged population. Do not silently replace a limit refusal with a smaller cohort.

For each stage record latency percentiles, refusals by code, live versus executing
sandboxes, `memory.stat` anon/file/shmem/kernel, CPU split, OOM kills and cold-start
distribution. Separately measure 1, 10 and 50 idle sessions; the cold-start timeline
from slice creation through systemd-run/D-Bus, bwrap and exec; and page-cache use for
10 sessions over reflink copies versus one shared read-only tree. A missing capability
is a recorded baseline limitation, not an invented passing measurement.

Write a JSON receipt for every run under `executor-native/evidence/`, retaining
failures and cleanup outcomes. Production-host runs use low traffic and the existing
[load-suite preflight](../../acceptance/load-suite.py), with ownership-aware cleanup
and bounded resource use. No private configuration, credentials or content enters
public fixtures or receipts.

### L: lightweight and ready to use

Implement L1 through L10 in order, normally one change per item. Preserve independent
SDK conformance while changing the implementation.

1. L1: replace the helper dependency. Read the source of the pinned SRT helper
   and enumerate its actual rules. Generate an equivalent seccomp filter in Rust
   with seccompiler or libseccomp and supply it through bwrap `--seccomp FD`.
   This is equivalence, not hardening. Remove Node.js, npm and SRT from the Sandcrab
   toolchain, retain the AF_UNIX probe and add a real probe for every rule.
   Programs selected by a consumer, including Node.js, remain workload tools.
2. L2: delegated cgroups. Replace per-sandbox systemd-run with leaves created
   in a delegated cgroup v2 subtree. Set `memory.max`, `memory.swap.max`, `pids.max`
   and `cpu.max`; launch bwrap with CLONE_INTO_CGROUP or join before exec. Contain
   with `cgroup.kill` and observed `populated 0`. Carry over user/group dropping,
   no_new_privs, umask, sized private `/tmp` and binds from the current unit owner.
   Support systemd `Delegate=yes`, containers with a delegated cgroup namespace,
   and root on a cgroup v2 host. No unrestricted fallback; retain admission until
   containment and clean old leaves before publishing a new epoch. Target
   cold-start p50 at most 30 ms.
3. L3: ordinary filesystems. Default volumes and session workspaces to plain
   directories. Reflink where supported, copy otherwise. Enforce XFS project quotas
   when configured. Without quotas, report usage and refuse growth through periodic
   accounting, explicitly documenting that this is not a hard storage bound.
   Only explicit `require_quota = true` refuses startup without enforcement.
4. L4: bundled bwrap. Include a pinned static bwrap version at least 0.9 in
   releases, use it by default and verify its checksum. Publish its source and
   version and satisfy LGPL obligations. This supplies L7's overlay support on
   Ubuntu 22.04 instead of depending on its older distribution package.
5. L5: installation and diagnosis. `sandcrab install` creates the execution
   user, delegated slice/unit, working `default` profile with read-only host `/usr`,
   python3, bash and network none, plus a token file for loopback HTTP. `--dry-run`
   prints every intended change without writing. `sandcrab doctor` checks the kernel,
   cgroup delegation, user namespaces, bwrap checksum, storage and a real test run,
   with a fix for each failure. Publish a container image running `sandcrab serve`
   and document the exact Docker flags needed for delegation.
6. L6: general API. Keep runs, sessions with named contexts, files, command
   events, profiles, the OpenSandbox adapter, CLI and token-authenticated loopback
   HTTP. Terminal run, session and upload records expire after a TTL unless
   `keep_result`; explicit forget remains. Add streaming
   `PUT /v1/sessions/{id}/files?path=` for ordinary uploads. Retain staged uploads
   only with a documented general use for recoverable large transfers. Keep plain
   tar downloads; remove freeze flags and checksummed snapshot manifests unless
   a general use is documented. Poketto can capture consistently inside one command.
   Keep opaque host calls optional per profile with a non-Poketto example. Update
   [OpenAPI](../../executor-service/rust/sandcrab/openapi.json), the README and
   [SDK mapping](../../executor-service/rust/sandcrab/opensandbox.md) together.
7. L7: templates. Store an immutable tree once, addressed by SHA-256, imported
   from tar or an unpacked OCI image. A session uses
   `--overlay-src TEMPLATE --overlay UPPER WORK /workspace`, optionally using the
   template as its root filesystem. Export UPPER as tar, preserving whiteout deletion
   semantics. Refuse unsupported kernels below 5.11 or bwrap without overlay support
   at startup, with no copy fallback. This shares page cache/disk and supplies
   reusable custom environments.
8. L8: egress. Support `network = "none"` or `network = { allow = ["pypi.org", ...] }`. Implement a
   daemon-side HTTP CONNECT/SOCKS proxy, bridged into the sandbox network namespace
   before seccomp, and set proxy environment variables. No direct egress; DNS goes
   only through the proxy. Log every policy decision.
9. L9: admission queue. Use a bounded FIFO for commands, with `max_queued`
   defaulting to 256 and `queue_timeout_millis` to 2,000, instead of immediate
   BUSY/CAPACITY. Timeout returns refusal without execution. Preserve no replay.
10. L10: contributor entrance. Document quickstarts for all three distributions
    and the container. Provide CLI/curl and Python/Kotlin OpenSandbox examples, a
    build/test agent and a batch code runner. Consolidate `*_probe.py` into one
    locally runnable conformance runner. Fresh GitHub-hosted Ubuntu 22.04/24.04
    runners and a Debian 12 VM or systemd container download the built artifact,
    follow the README verbatim and run conformance. CI fails when the readiness
    target is missed.

### H: same-host hardening

After L1 and L2, verify each gap in the owning code before changing it; complete
H1 through H4 before extraction, observing L4's bwrap dependency. Every H change
records footprint before and after and adds no runtime dependency or resident process.

- H1: namespaces. Use `--unshare-all` and `--disable-userns`; keep the network
  unshared. Probe real sandboxes: nested `unshare -U` fails, SysV shared memory is
  invisible across sessions using the same execution user, hostnames are private,
  and `/proc/self/cgroup` does not reveal host unit names.
- H2: seccomp. Extend L1's Rust filter using Docker's default profile as the
  reference. Keep AF_UNIX/AF_VSOCK socket denial. Deny io_uring, bpf,
  keyctl/add_key/request_key, perf_event_open, userfaultfd, kexec, module loading,
  the mount family/new mount API, setns, namespace flags in unshare/clone, ptrace
  and nondefault personality changes. Return ENOSYS for clone3 so runtimes fall
  back. Probe every rule; keep bash, python3, git, rg, node, the benchmark mix and
  published SDK conformance working.
- H3: process authority. Test the daemon's replacement process setup: the
  profile's execution user, no capabilities, no_new_privs, no writable cgroup
  controls and no ability to raise resource limits from inside a sandbox.
- H4: threat model. Add `executor-service/rust/sandcrab/THREAT-MODEL.md` and
  link it from the README. Document trusted/semi-trusted code, the same-kernel
  boundary and excluded protections, profiles/network/host-call guarantees and
  limits, Unix peer UID and loopback-token authentication, resource exhaustion
  and known gaps. Publish it with the release.

### P: measured performance work

Rerun W0 after L2, L7 and L9 and report footprint and density before/after. Implement
P1 idle shell eviction only if idle sessions consume at least 40% of sandbox memory
at the measured capacity stage. Otherwise record the numbers and skip P1.

If triggered, after `idle_evict_millis` and only when the shell is the sole cgroup
process, save cwd and `export -p`, exit the shell and restore that state on the next
command. Never evict while background processes exist; document lost state and move
session workspaces from tmpfs to disk. Propose any other measured bottleneck, such
as interpreter startup, in the decision record before implementing an optimization.

### X: extraction by readiness

Extract when L1-L10 and H1-H4 have landed and CI readiness, conformance and published
SDK gates pass. There is no waiting interval and no dependency on Poketto cutover.
Preserve history when splitting the generic crates, docs, OpenAPI, examples, evals
and benchmark. Prepare a release workflow for static x86_64 musl Sandcrab plus bundled
bwrap, SHA-256 checksums, the container image and H4's threat model.

The owner selects repository name, owner, visibility and first version before the
repository is created and the release published. Poketto then pins that release by
checksum and removes its in-repository Sandcrab sources.

### C: consumer adaptation

After L6 and L7 define the API, rework native adoption against it. Split delivery
into the XFS bind-mount fix unless L3 replaces it, retrieval-lab migration, native
consumer plus importer, and legacy removal. Adapt callers of removed freeze,
snapshot and staged-upload interfaces; do not restore them solely for Poketto.

New account copies and public projections use the commit snapshot as a template
and unsaved changes as the upper layer. Existing volumes remain until disposal or
expiry. Update the owning [account-copy](../implemented/2026-09-14-account-working-copies.md)
and [authoring](../implemented/2026-09-09-codeact-workspaces.md) decisions with their
implementation. Preserve data and current authorization during transition.
Before deployment/import/cutover, back up legacy data and record the previous image
pin for manual recovery; pause writes during import, verify it and retain the result
in delivery evidence. The service proposal's preregistered 50-agent comparison is
a Poketto cutover check, not a Sandcrab or extraction gate.

JVM footprint work is last: measure NMT and anon versus file memory under load,
then tune heap, GC, compact object headers, pool sizes and AOT cache as warranted;
report memory freed for sandboxes.

## Alternatives

- Complete all Poketto migration and wait four weeks before extraction. This
  measures consumer scheduling and elapsed time, not independent installation,
  footprint or API usability; readiness evidence owns that decision instead.
- Preserve every existing generic-looking operation. It reduces immediate adapter
  changes but retains product-specific transaction costs and unsupported promises.
  General uses and independent consumers decide what remains.
- Optimize shell residency first. It may reduce memory, but it is unjustified until
  W0 separates idle shell cost, page cache and executing workload. P1 has a measured
  trigger instead.
- Require XFS, systemd, a container runtime or a larger isolation service for every
  user. That narrows installation choices and prevents the first-use target. Optional
  quotas/delegation and same-host isolation keep their limits explicit; they do not
  promise a microVM boundary.

## Consequences

Consumer migration is reworked after the smaller API and templates settle. Optional
storage accounting is weaker than enforced quotas, overlay requires supported kernel
and bwrap features, and same-host hardening cannot remove shared-kernel risk. Bundling
bwrap and owning seccomp shifts release, licensing and compatibility work into Sandcrab.
These are deliberate costs of a smaller operator dependency set and independent delivery.

## Verification

Each delivery names the priority it serves and supplies relevant footprint, readiness
or density before/after measurements. W0 first establishes baselines; this proposal
alone claims no runtime improvement. Run `executorRustCheck`, native lanes, Sandcrab
evals and SDK gates; select other checks through
[pre-push-checks](../../.agents/skills/pre-push-checks/SKILL.md). Keep scripted conformance
separate from repeated real-agent usability evidence through [Inspect](../../evals/README.md).
Paid batches record the estimate, upper bound, actual usage/cost and every attempt.
An unavailable required check is reported, never replaced by an invented equivalent.

Retain every run, including failures, without changing the scored metric or selecting
only successful reruns. The [note lifecycle](../../AGENTS.md#decision-records-notes)
governs updates and the eventual implemented record.
