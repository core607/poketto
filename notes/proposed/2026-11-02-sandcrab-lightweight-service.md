# Lightweight, Ready-to-Use Sandcrab

Date: 2026-11-02

## Problem

The [native service](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md) has an independent
API. Its [manual baseline](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/experiments/sandcrab-density/READINESS.md) required
operators to prepare tools, accounts, resource delegation and profiles. The
[native seccomp filter](../archived/2026-11-02-native-seccomp-filter.md) implements L1,
removing the helper's Node.js/npm/SRT provisioning.
[Delegated cgroups](../archived/2026-11-02-delegated-sandbox-cgroups.md)
replace native per-sandbox service-manager transactions.
Independent API tests do not establish first-use cost or capacity on a small host.

The earlier service proposal tied extraction to consumer delivery and a four-week interval. Waiting does not demonstrate that a
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
These priorities supersede the earlier runtime requirements, proposed extensions
and delivery order. There is no time-based extraction gate, and Poketto cutover
does not block extraction. Operator profiles own host paths, execution identities,
tools and isolation bounds; caller labels grant no authority. Unix peers and
loopback tokens authenticate callers. Cancellation and recovery retain admission
until containment is confirmed, with no unrestricted subprocess fallback.

Sandcrab owns its independent source, API, SDKs, evals and benchmark. Source links
below pin the final embedded implementation as historical context; they do not
define the current distribution. Poketto owns its [release pin and integration](../../executor-service/README.md).
The remaining consumer work keeps this record proposed.

### W0: decision record and baseline

The independent [measurement suite](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/experiments/sandcrab-density/README.md)
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

1. L1 is implemented by the [native filter decision](../archived/2026-11-02-native-seccomp-filter.md).
2. L2 uses [delegated cgroups](../archived/2026-11-02-delegated-sandbox-cgroups.md)
   for native launches and confirmed containment. Target cold-start p50 at most 30 ms.
3. L3 uses [ordinary directory storage](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#storage-accounting)
   with optional XFS enforcement. Periodic accounting keeps non-XFS hosts usable,
   with weaker bounds and scan overhead.
4. L4 implemented: the [static helper bundle](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab-launch/bwrap/README.md)
   pins bwrap above 0.9, accompanies it with source/version/notices, and checks its
   checksum by default. Release ownership supplies L7's overlay-capable helper
   without depending on Ubuntu 22.04's older distribution package.
5. L5 provides [installation, diagnostics and bounded delegated containers](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/install/README.md).
6. L6 exposes runs, sessions with named contexts, files, command
   events, profiles, the OpenSandbox adapter, CLI and token-authenticated loopback
   HTTP. [Terminal result retention](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#result-retention)
   expires unpinned run, session and upload records; explicit forget remains.
   [Streaming PUT](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#files) handles ordinary
   uploads. Optional staged transfers retain operation receipts for large artifact
   publication across lost replies or restart; they do not resume partial bytes.
   Native transfers operate on live workspaces with raw/plain-tar downloads;
   callers coordinate consistency inside their commands. SDK file operations retain
   their documented internal capture policy. Runtime metadata follows the 0.x rebuild rule.
   Optional profile host calls include an [independent signing example](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/examples/sandcrab-host-calls.py).
   [OpenAPI](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/openapi.json) and the
   [SDK mapping](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/opensandbox.md) own their contracts.
7. L7: [immutable template imports](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#templates)
   use SHA-256 identities including normalized execution UID/GID. This preserves
   profile permissions without world-writable bases or per-session chown.
   Shared bwrap overlays give each parent a disposable writable layer; contexts
   share one mount to avoid concurrent writers on independently mounted uppers.
   Startup requires Linux 5.11+ and verifies the helper and backing filesystem;
   unsupported storage refuses without a copy fallback. Template rootfs protects
   mount anchors so guest renames cannot redirect privileged file helpers.
   Upper tar export translates whiteouts for portable deletion semantics; callers
   coordinate writers and apply the changeset to its base before reimporting.
8. L8: [egress allowlists](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#network-egress).
   Trusted launchers export private-network listeners before UID drop/seccomp,
   avoiding veth/firewall state and resident bridges. Proxy variables select
   daemon-side CONNECT/SOCKS5 and DNS; direct egress stays unavailable. Connections
   are bounded, decisions logged, and cleanup precedes admission release.
   [Native probes](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/sandcrab_conformance/egress.py) pin isolation and lifecycle.
9. L9: [bounded admission](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#command-queue).
   Independent finite-run and shell FIFOs prevent capacity waits blocking existing shells.
   Locked cancellation removes payloads before forgetting, preventing stale execution
   after ID reuse. [Probes](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/sandcrab_conformance/queue.py) cover
   ordering, no-execution refusals and SDKs.
10. [Conformance](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#verification) runs selected
    native and published SDK suites with isolated fixtures and cleanup receipts.
    The [quickstart](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#get-started) stages the
    bundled files before calling the protected native installer. Its authenticated
    loopback endpoint supports SDK clients on first use. CI downloads the build ZIP,
    replays the README on Ubuntu 22.04/24.04 and Debian 12, checks first-run output
    against the readiness limits and exercises the installed binary under delegation.

### H: same-host hardening

Complete hardening before extraction using L4's bundled bwrap. Each change tests
its isolation boundary and measures footprint without adding runtime dependencies or resident processes.

- H1 gives workloads private namespaces, a fixed hostname and disabled nested
  user namespaces. The launch policy requires user and cgroup isolation explicitly
  because bundled bwrap only tries both under `--unshare-all`. Proxy workloads retain
  their acknowledged private network. Fixed file helpers retain host UID mappings for
  ownership changes. [Probes](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/sandcrab_conformance/namespaces.py)
  check isolation across same-UID sessions.
- H2 layers workload denials over the [inherited filter](../archived/2026-11-02-native-seccomp-filter.md).
  The [Moby profile](https://github.com/moby/profiles/blob/4b8214fc4971e06172938a1f1554ccff6647ee80/seccomp/default.json)
  supplies the reference for administrative calls and clone fallback. Sandcrab allows
  ordinary threads and personality queries. [Kernel probes](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/sandcrab_conformance/seccomp_cases.py)
  exercise the additional denials, including VSOCK and namespace creation.
- H3 [checks](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/sandcrab_conformance/resources.py)
  observe profile credentials, empty capability sets and no_new_privs in a finite run
  and a shell. Guest attempts to open cgroup controls for writing and raise a hard
  limit fail. The controller checks the effective session caps across those attempts.
- The [threat model](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/THREAT-MODEL.md)
  defines the shared-kernel trust boundary, operator and caller authority, limits
  and remaining risks. Include it in the standalone release.

### P: measured performance work

The [L7](../../executor-native/evidence/sandcrab-l7-2026-11-03/summary.json) and
[L9](../../executor-native/evidence/sandcrab-l9-2026-11-04/summary.json) checkpoints
put the conservative estimated idle-session share below the 40% trigger in every
budget. Skip idle shell eviction. Reconsider only when measured idle cost reaches
that threshold. An eviction proposal must protect background processes, restore cwd
and exports, describe lost shell state and keep workspaces on disk. Other optimizations
require a measured bottleneck and a proposal before implementation.

### D: environment reuse and measured performance

[DSec](https://arxiv.org/html/2609.22978v1) motivates reusable environment layers,
less repeated setup, resource-aware execution and independent grading. Its
cluster measurements are not Sandcrab estimates. These work items extend P and
the independent-consumer target; they do not change the no-KVM, small-host,
readiness or security requirements. Sandcrab owns generic mechanisms and SDK/CLI
workflows. The [native consumer](../implemented/2026-11-05-native-repository-consumer.md)
owns application journals, authorization and adaptation.

Implement D0 first, then D1/D2 with the relevant D3 measurements. D4 follows its
measured bottleneck. D5 accompanies the affected APIs and evaluations. D6/D7
remain conditional investigations. Complete this work before the final JVM
footprint work and closure of the overall plan.

#### D0: execution cost breakdown

The native consumer's [phase replay](../../acceptance/clients/native-phases.md) implements
this cost collection. Its [comparison summary](../../executor-native/evidence/2026-11-09-native-phase-measurements/summary.json)
keeps the unchanged application baseline separate from disabled and enabled recording.
The [diagnostics decision](../implemented/2026-09-14-service-diagnostics.md) owns the
optional instrumentation boundary. Admission, resource limits and durability remain unchanged.

Measure request admission, template lookup, sandbox and shell startup, upper
restoration, command execution, change capture, durable acknowledgement and
cleanup separately. Distinguish a native API call from the consumer's whole
request. Record wall/CPU time, transferred and written bytes, CPU throttling,
run-queue or CPU-pressure observations, I/O pressure, and anon/file/kernel memory.
Separate cold and warm starts, successful work, refusals and timeouts. A low
all-outcome latency with many refusals is not improved goodput. Do not infer
the bottleneck from aggregate RSS, an empty admission queue or zero OOM alone.

Use fixed command replays and representative small and large writable layers.
Preserve an unmodified baseline and change one mechanism per comparison. Propose
and implement removal of repeated setup only where this breakdown establishes
the cost; preserve integrity checks, containment and acknowledged durability.

#### D1: prepare, validate and publish environments

Provide a short CLI/SDK workflow to prepare an environment interactively, validate
it, publish an immutable template and start independent instances from it. Reuse
existing template and diff primitives before adding a daemon operation. Consumers
must not each implement tar, whiteout and reimport orchestration.

Define this as a filesystem checkpoint: process state, shell variables and cwd
are not implicitly restored. Bound capture and import, coordinate writers, preserve
deletions and supported link/permission semantics, and define cancellation and
lost-reply reconciliation without replaying effects. Demonstrate the workflow in
an independent build/test example and compare preparation cost and client steps.

#### D2: composable environment recipes

Allow independently versioned base, dependency/toolkit and workspace layers with
a private writable upper. Specify layer order, conflicting paths, whiteouts,
execution identity, rootfs anchors and profile authority. Bind the ordered recipe
to immutable digests. Callers select authorized content, never arbitrary host
mount paths or stronger privileges. Pins survive active users; deletion and
garbage collection respect every reference and uncertain cleanup.

Verify independent instances, changing a toolkit without republishing unchanged
workspace content, rootfs and SDK behavior, restart recovery and failed
publication. Record cold/warm preparation, imported bytes, metadata and mount
cost, and the effect on first-use instructions and dependencies.

#### D3: shared file cache and preparation reuse

Identify dependencies, indexes and caches still duplicated in writable copies.
Share immutable trees and file identities where safe; do not count existing
template sharing as a new gain. Measure 1/10/50 readers with matched cold/warm
conditions, file/anon/kernel memory, actual copy-up and bytes written. Include a
write-heavy case so saved read cache does not conceal copy-up cost. Preserve
private writable state and ownership; never drop production caches for a test.

#### D4: bounded startup, execution and CPU service classes

Evaluate separate bounds for live sessions, concurrent startup and executing
commands. Add controls only when D0 demonstrates contention or a useful capacity
trade-off. Compare interactive commands alongside background builds using
successful throughput, p95/p99, starvation and CPU throttling. Evaluate task
weights before scheduler or physical-core controls; verify available topology
and kernel support before claiming SMT isolation on a virtual host.

Keep finite aggregate CPU/memory/task limits and zero swap. Scheduling classes
remain operator-authorized. Cancellation, queue timeout and revocation preserve
no-execution refusals and no replay; admission is released only after containment.
Changing a queue length alone is not a performance result.

#### D5: evaluation integrity and interference

Extend the independent grader with bounded cases that attempt to read reference
answers or grader logs, access control channels, modify protected execution
entrypoints, pollute later samples, or exhaust output/storage. Separate builder,
agent and grader authority; published templates exclude reference answers,
credentials and build residue. Check expected denial or bounded termination,
independent output/state readback and cleanup. Keep scripted boundary probes
distinct from paid model-directed tasks and retain every attempted run.

#### D6: conditional EROFS and on-demand loading

Proceed only when D0/D2 show large-environment import, extraction or local storage
cost dominating the target workload. Compare the existing unpack-once template
path with optional local EROFS first. Record CPU, I/O, cache reuse, startup tail,
tool/kernel requirements and readiness. Remote lazy loading follows only an
observed distribution bottleneck; account for network failure and cache eviction.
Do not introduce 3FS, a distributed scheduler, a required container runtime or a
resident storage service into the small-host default.

#### D7: conditional idle reclamation

Retain P1's 40% trigger and background-process, cwd/export, disk-workspace and
state-loss requirements. Freezing alone establishes no memory saving. Evaluate
only reclamation compatible with the zero-swap reference profile; measure
memory released, reclaim CPU/I/O and resume latency. Swap-assisted pause requires
a separately accepted profile and cannot replace zero-swap evidence. VM-specific
DAX/balloon mechanisms remain inapplicable to the bwrap backend; KVM and new
execution backends stay outside this work.

For each conditional item, retain the trigger measurement and either implement
and verify the warranted mechanism or record why it remains deferred. A deferred
item is not an implemented capability. W0's checkpoint-only full measurements,
aggregate-only committed evidence and issue-and-continue rule still apply;
intermediate changes run only affected checks and targeted comparisons. Update
the owning API/docs and release before Poketto adopts a cross-boundary change.

### X: extraction by readiness

Extract when L1-L10 and H1-H4 have landed and CI readiness, conformance and published
SDK gates pass.
Preserve history when splitting the generic crates, docs, OpenAPI, examples, evals
and benchmark. Prepare a release workflow for static x86_64 musl Sandcrab plus bundled
bwrap, SHA-256 checksums, the container image and H4's threat model.

The independent release has shipped. Poketto's [game-service staging](../../docs/games.md)
verifies the release archive and executable against the [consumer pin](../../executor-service/sandcrab-release.properties).
Poketto contains no embedded Rust workspace, provider SDK harness or generic
benchmark. Its protected release check exercises the consumer against that ZIP;
provider conformance and publishing run under the independent release owner.

### C: consumer adaptation

XFS project IDs span a filesystem. Sandcrab [requires the whole mount](https://github.com/core607/poketto/blob/c7eaa05047dff4e0fd92c6f89998ed36120fe6f4/executor-service/rust/sandcrab/README.md#durable-volumes)
before allocating them or recovering active project volumes. The
[retrieval laboratory](../../experiments/retrieval-lab/README.md#connect-the-independent-sandcrab-service)
uses a fresh volume and a verified Git bundle for each question. One streaming upload
keeps the corpus independent of the template import deadline.

The [native repository backend](../../executor-service/native-repository.md) shares an
authorized commit template and journals each copy's unsaved upper. The
[offline importer](../../executor-service/native-migration.md) preserves old volumes
through disposal or expiry. Existing signed installations need this import before
adopting the native consumer; the [deployment contract](../../executor-service/native-repository.md)
owns the current backend selection.
Deferred cleanup that needs a terminal receipt must request `keepResult`. The
[game cleanup](../../src/main/java/io/github/core607/poketto/executor/internal/GameSandboxJob.java)
requires an observed terminal session before releasing application admission.
Before deployment/import/cutover, back up legacy data and record the previous image
pin for manual recovery; pause writes during import, verify it and retain the result
in delivery evidence. The preregistered 50-agent comparison uses the same host
and bounds, reporting goodput, p95, refusals and CPU by application, supervisor
and sandbox. Fix warm-up, repetition and scoring before collecting results and
retain every attempt. This is a Poketto cutover check; missed targets follow
W0's issue-and-continue rule rather than blocking extraction.

JVM footprint work is last: measure NMT and anon versus file memory under load,
then tune heap, GC, compact object headers, pool sizes and AOT cache as warranted;
report memory freed for sandboxes.
The [matched JVM comparison](../../acceptance/clients/native-jvm.md) selects candidates
by measured private residency and successful throughput. Validate a selected
setting against the deployment artifact and affected capabilities before changing defaults.

## Alternatives

- Complete all Poketto migration and wait four weeks before extraction. This
  measures consumer scheduling and elapsed time, not independent installation,
  footprint or API usability; readiness evidence owns that decision instead.
- Preserve every existing generic-looking operation. It reduces immediate adapter
  changes but retains product-specific transaction costs and unsupported promises.
  General uses and independent consumers decide what remains.
- Compile consumer integrations into the daemon through Rust traits. This keeps
policy near the mechanism but requires every integrator to build Rust and
couples product identities and Git transactions to the generic release.
- Implement the E2B API. Its endpoint routing and Connect RPC introduce DNS,
certificate and protocol work for a local daemon, without an official Java SDK.
- Contribute a bubblewrap backend to OpenSandbox. It avoids a new API but keeps
Python and Go services resident; its inner sandbox relies on an outer resource
boundary. A bounded SDK subset retains compatibility without those runtime costs.
- Use microVMs. They strengthen isolation but require KVM, outside the target
host contract. The [repository execution decision](../implemented/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)
records the separate gVisor trade-off.
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
Independent natural-language tasks use fixed clients/models, repeated runs and
output, file or state grading across execution, sessions, files, cancellation and
recovery. Record code/corpus revisions, failures, calls, latency and usage.
Paid batches record the estimate, upper bound, actual usage/cost and every attempt,
distinguishing known costs from unavailable prices. Publish no private content or credentials.
An unavailable required check is reported, never replaced by an invented equivalent.

Report the original score, including failures. The [note lifecycle](../../AGENTS.md#decision-records-notes)
governs updates and the eventual implemented record.
