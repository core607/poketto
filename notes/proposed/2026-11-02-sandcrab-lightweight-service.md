# Lightweight, Ready-to-Use Sandcrab

Date: 2026-11-02

## Problem

The [native service](../../executor-service/rust/sandcrab/README.md) has an independent
API. Its [manual baseline](../../experiments/sandcrab-density/READINESS.md) required
operators to prepare tools, accounts, resource delegation and profiles. The
[native seccomp filter](../implemented/2026-11-02-native-seccomp-filter.md) implements L1,
removing the helper's Node.js/npm/SRT provisioning.
[Delegated cgroups](../implemented/2026-11-02-delegated-sandbox-cgroups.md)
replace native per-sandbox service-manager transactions.
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

The independent [measurement suite](../../experiments/sandcrab-density/README.md)
owns the implemented W0 protocol, collectors, raw receipts and failure handling.
The following targets remain acceptance criteria for the remaining work.

| Measure | Definition |
|---|---|
| Footprint | Static binary bytes, daemon idle RSS, fixed memory per idle session and finite run, external runtime dependency count and purpose |
| Readiness | Fresh Ubuntu 22.04, Ubuntu 24.04 and Debian 12: download to first `sandcrab run` in at most three commands, no manual configuration edits, under five minutes; record all three quantities |
| Density | Largest tested agent count with ok rate at least 0.99, judged p95 at most 1.0 s and zero OOM kills |

W0 is the baseline. Run the full protocol only at L2, L7 and L9; other pull
requests run the tests their changes affect. Use the checkpoint results for
the final comparison. The 5 GB budget approximates memory beside the default
[application containers](../../deploy/compose.yaml), not production capacity.
Commit only a compact `summary.json` with the revision, outcomes and CI links.
Keep all raw observations, failed attempts and cleanup receipts in CI artifacts.
Record a missed performance target in one follow-up issue and continue the feature
work without retuning or repeating the measurement in that pull request. Missing
capabilities and smaller admitted cohorts never count as passing evidence.
Production runs retain low-traffic [load-suite preflight](../../acceptance/load-suite.py),
bounded resources and ownership-aware cleanup. Public evidence contains no private
configuration, credentials or content.

### L: lightweight and ready to use

Implement L1 through L10 in order, normally one change per item. Preserve independent
SDK conformance while changing the implementation.

1. L1 is implemented by the [native filter decision](../implemented/2026-11-02-native-seccomp-filter.md).
2. L2 uses [delegated cgroups](../implemented/2026-11-02-delegated-sandbox-cgroups.md)
   for native launches and confirmed containment. Target cold-start p50 at most 30 ms.
3. L3 uses [ordinary directory storage](../../executor-service/rust/sandcrab/README.md#storage-accounting)
   with optional XFS enforcement. Periodic accounting keeps non-XFS hosts usable,
   with weaker bounds and scan overhead.
4. L4 implemented: the [static helper bundle](../../executor-service/rust/sandcrab-launch/bwrap/README.md)
   pins bwrap above 0.9, accompanies it with source/version/notices, and checks its
   checksum by default. Release ownership supplies L7's overlay-capable helper
   without depending on Ubuntu 22.04's older distribution package.
5. L5 provides [installation, diagnostics and bounded delegated containers](../../executor-service/rust/sandcrab/install/README.md).
6. L6 exposes runs, sessions with named contexts, files, command
   events, profiles, the OpenSandbox adapter, CLI and token-authenticated loopback
   HTTP. [Terminal result retention](../../executor-service/rust/sandcrab/README.md#result-retention)
   expires unpinned run, session and upload records; explicit forget remains.
   [Streaming PUT](../../executor-service/rust/sandcrab/README.md#files) handles ordinary
   uploads. Optional staged transfers retain operation receipts for large artifact
   publication across lost replies or restart; they do not resume partial bytes.
   Native transfers operate on live workspaces with raw/plain-tar downloads;
   callers coordinate consistency inside their commands. SDK file operations retain
   their documented internal capture policy. Runtime metadata follows the 0.x rebuild rule.
   Optional profile host calls include an [independent signing example](../../examples/sandcrab-host-calls.py).
   [OpenAPI](../../executor-service/rust/sandcrab/openapi.json) and the
   [SDK mapping](../../executor-service/rust/sandcrab/opensandbox.md) own their contracts.
7. L7: [immutable template imports](../../executor-service/rust/sandcrab/README.md#templates)
   use SHA-256 identities including normalized execution UID/GID. This preserves
   profile permissions without world-writable bases or per-session chown.
   Shared bwrap overlays give each parent a disposable writable layer; contexts
   share one mount to avoid concurrent writers on independently mounted uppers.
   Startup requires Linux 5.11+ and verifies the helper and backing filesystem;
   unsupported storage refuses without a copy fallback. Template rootfs protects
   mount anchors so guest renames cannot redirect privileged file helpers.
   Upper tar export translates whiteouts for portable deletion semantics; callers
   coordinate writers and apply the changeset to its base before reimporting.
8. L8: [egress allowlists](../../executor-service/rust/sandcrab/README.md#network-egress).
   Trusted launchers export private-network listeners before UID drop/seccomp,
   avoiding veth/firewall state and resident bridges. Proxy variables select
   daemon-side CONNECT/SOCKS5 and DNS; direct egress stays unavailable. Connections
   are bounded, decisions logged, and cleanup precedes admission release.
   [Native probes](../../executor-service/rust/sandcrab/sandcrab_conformance/egress.py) pin isolation and lifecycle.
9. L9: [bounded admission](../../executor-service/rust/sandcrab/README.md#command-queue).
   Independent finite-run and shell FIFOs prevent capacity waits blocking existing shells.
   Locked cancellation removes payloads before forgetting, preventing stale execution
   after ID reuse. [Probes](../../executor-service/rust/sandcrab/sandcrab_conformance/queue.py) cover
   ordering, no-execution refusals and SDKs.
10. [Conformance](../../executor-service/rust/sandcrab/README.md#verification) runs selected
    native and published SDK suites with isolated fixtures and cleanup receipts.
    The [quickstart](../../executor-service/rust/sandcrab/README.md#get-started) stages the
    bundled files before calling the protected native installer. Its authenticated
    loopback endpoint supports SDK clients on first use. CI downloads the build ZIP,
    replays the README on Ubuntu 22.04/24.04 and Debian 12, checks first-run output
    against the readiness limits and exercises the installed binary under delegation.

### H: same-host hardening

After L1 and L2, verify each gap in the owning code before changing it; complete
H1 through H4 before extraction, observing L4's bwrap dependency. Each change
tests its affected isolation boundary and adds no runtime dependency or resident
process. Use the checkpoint measurements for footprint comparisons.

- H1: namespaces. Use `--unshare-all` and `--disable-userns`; preserve L8's private
  network. Probe real sandboxes: nested `unshare -U` fails, SysV shared memory is
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

The [L7](../../executor-native/evidence/sandcrab-l7-2026-11-03/summary.json) and
[L9](../../executor-native/evidence/sandcrab-l9-2026-11-04/summary.json) checkpoints
put the conservative estimated idle-session share below the 40% trigger in every
budget. Skip idle shell eviction. Reconsider only when measured idle cost reaches
that threshold. An eviction proposal must protect background processes, restore cwd
and exports, describe lost shell state and keep workspaces on disk. Other optimizations
require a measured bottleneck and a proposal before implementation.

### X: extraction by readiness

Extract when L1-L10 and H1-H4 have landed and CI readiness, conformance and published
SDK gates pass.
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
Consumers that need a terminal receipt for deferred cleanup must request
`keepResult` before adopting TTL-enabled native binaries. In particular,
[game cleanup](../../src/main/java/io/github/core607/poketto/executor/internal/GameSandboxJob.java)
requires an observed terminal session before releasing application admission.
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

On each pull request select the Rust, native, readiness, eval or SDK checks
affected by the change using [pre-push-checks](../../.agents/skills/pre-push-checks/SKILL.md).
Only the measurement checkpoints above establish capacity. Keep scripted conformance
separate from repeated real-agent usability evidence through [Inspect](../../evals/README.md).
Paid batches record the estimate, upper bound, actual usage/cost and every attempt.
An unavailable required check is reported, never replaced by an invented equivalent.

Report the original score, including failures. The [note lifecycle](../../AGENTS.md#decision-records-notes)
governs updates and the eventual implemented record.
