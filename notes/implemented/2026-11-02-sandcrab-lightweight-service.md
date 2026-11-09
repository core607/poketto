# Lightweight, Ready-to-Use Sandcrab

Date: 2026-11-02

## Problem

An independent API did not make the sandbox service easy to install. Operators
still had to assemble tools, identities, resource delegation and profiles. Its
extraction proposal also depended on consumer delivery and a four-week waiting
period, neither of which measured installation cost or usefulness to another
project. Preserving Poketto-specific operations risked making that coupling permanent.

## Decision

Low fixed cost and first-use readiness take priority over concurrent-agent
density. The reference is a two-CPU Linux host with 4 or 8 GB of memory, without
KVM. Readiness, native conformance and published SDK acceptance establish the
extraction boundary; elapsed time and Poketto's cutover schedule do not.

Sandcrab owns its independently released source, API, CLI, SDK integration,
evaluations and benchmark. Poketto owns its [checksum pin and integration](../../executor-service/README.md),
repository authorization, Git consistency, copy journals and application admission.
The [native consumer decision](2026-11-05-native-repository-consumer.md) owns those
contracts. A general primitive needs a use outside Poketto; application identities
and transactions stay out of the daemon. Consumers adapt to the generic service.

### Runtime and authority

A static daemon and checksum-verified bundled bubblewrap keep host preparation
small. Native seccomp removes the helper's Node/npm provisioning; Node remains
an application workload dependency where games require it. Direct cgroup v2
delegation removes per-sandbox service-manager transactions. Systemd installation
and a delegated container are supported operating choices, not additional resident
services required by the core runtime.

Operator profiles own execution identities, mounts, networking and hard resource
bounds. Unix peers and loopback tokens authenticate callers; caller labels cannot
grant stronger authority. Namespaces, disabled nested user namespaces, empty guest
capability sets, no_new_privs and workload seccomp provide same-host hardening.
They retain a shared kernel, not a microVM trust boundary. Admission remains held
until containment is confirmed; missing capabilities never select an unrestricted
subprocess. Egress allowlists use bounded daemon proxies and private sandbox networks.

Ordinary directory storage keeps non-XFS hosts usable. Periodic accounting has
scan delay and can overshoot; XFS project quotas supply enforcement when explicitly
selected. Quota allocation requires ownership of the whole filesystem. These are
different storage contracts, not interchangeable capacity evidence.

Templates share immutable file identities under private writable overlays.
Execution identity participates in their digest, avoiding world-writable bases
or per-session ownership rewriting. Named contexts share a parent's mount instead
of concurrently mounting the same upper. Unsupported overlay capabilities fail
at startup; there is no copy fallback for a template operation.

### Reusable environments and evaluation

[DSec](https://arxiv.org/html/2609.22978v1) motivates reducing repeated environment
preparation. Its cluster results are not estimates for this service. Filesystem
capture, immutable publication and ordered recipes reuse the template and diff
primitives. A checkpoint captures files, not process state, shell variables or
cwd. Layer order, execution identity and deletion semantics belong to the immutable
identity; active and uncertain users retain their pins. Expected identities and
receipts reconcile lost publication replies without replaying effects.

The matched reader and write-heavy comparisons support sharing immutable
dependencies and avoiding repeated imports. They also retain copy-up costs;
read-cache savings are not a general improvement for write-heavy commands.
Optional operator CPU weights provide the measured interactive/background
trade-off. Equal weights remain the default. Separate startup or execution limits
and topology controls did not justify added mechanisms.

Scripted integrity probes keep agent execution separate from reference answers,
grading, control channels and protected entrypoints. Grading reads output and
state independently, including interference and bounded resource failures.
[Inspect evaluations](../../evals/README.md) distinguish these deterministic boundary
checks from paid model-directed tasks. Paid evidence records estimates, bounds,
actual usage, unavailable prices and every attempt; scripted success is not a
claim of model usability.

### Measurement and deferred mechanisms

The [L2](../../executor-native/evidence/sandcrab-l2-2026-11-02/README.md),
[L7](../../executor-native/evidence/sandcrab-l7-2026-11-03/summary.json) and
[L9](../../executor-native/evidence/sandcrab-l9-2026-11-04/summary.json) checkpoints
own the full density comparisons and their missed targets. Other changes use
affected checks and targeted comparisons. A result binds its revision, workload,
resource budget and successful work; refusals, an empty queue or zero OOM alone
do not establish capacity. Aggregate summaries belong in Git, with raw samples
and failed attempts retained separately. Missed targets become tracked limitations
without repeated tuning in the same change.

The [native phase replay](../../acceptance/clients/native-phases.md) separates
consumer request cost from individual service operations, including admission,
startup, execution, capture, durable acknowledgement and cleanup. The
[diagnostics decision](2026-09-14-service-diagnostics.md) owns optional recording.
Comparisons retain unchanged controls, fixed workloads, successful throughput,
CPU and I/O pressure, and private versus file-backed memory.

Idle shell eviction remains deferred: the checkpoint idle-share estimates do not
reach the 40% trigger. Reconsider it only with that measured cost, protection for
background processes, disk-backed workspaces, cwd/export restoration and explicit
state-loss semantics. Freezing alone does not reclaim memory. The zero-swap
reference excludes swap-assisted pauses and VM-specific reclamation.

EROFS and remote lazy loading also remain deferred. The measured reusable
dependency workload does not establish large-environment extraction, local
storage or distribution as the dominating cost. Reconsider local EROFS first
if that cost appears, including its tools, kernel support, I/O and startup tail;
remote loading additionally needs evidence of a distribution bottleneck.

The [JVM comparison](../../acceptance/clients/native-jvm.md) leaves production
defaults unchanged. Ordinary settings missed its private-memory selection rule.
AOT qualified in the synthetic fixture but failed production-artifact startup
([issue 417](https://github.com/core607/poketto/issues/417)); it therefore releases
zero deployed memory for sandboxes. Artifact compatibility and affected
capabilities must pass before reconsidering that setting.

### Consumer delivery

Poketto consumes the verified release instead of maintaining an embedded provider
workspace. The [retrieval laboratory](../../experiments/retrieval-lab/README.md#connect-the-independent-sandcrab-service)
uses an isolated volume and Git bundle per question. Repository copies share an
authorized commit template and journal unsaved uppers. The
[offline importer](../../executor-service/native-migration.md) preserves retained
work through disposal or expiry; stopped writers, backups and the previous image
pin provide the recovery boundary. Provider metadata follows the 0.x rebuild rule.

Cleanup that needs a terminal receipt requests keepResult. Games retain application
admission until the service confirms containment. The consumer's
[release verifier](../../deploy/native_repository.py) checks the installed binary,
configuration, process, endpoint and protected-copy behavior before replacing
application containers. The operator owns privileged installation and upgrades.

## Alternatives

- Wait for all consumer migration and four stable weeks. This measures scheduling
  and elapsed time rather than independent readiness.
- Keep every existing operation or compile consumer integrations into Rust traits.
  This eases local adaptation but couples product authority and transactions to
  the generic distribution, and makes integrators rebuild the daemon.
- Implement E2B's API. Its routing and Connect RPC add DNS, certificate and protocol
  work for a local service; its documented [SDKs](https://github.com/e2b-dev/E2B#run-your-first-sandbox)
  target JavaScript and Python rather than the Java consumer.
- Contribute a bubblewrap backend to OpenSandbox. This keeps Python and Go services
  resident and depends on an outer resource boundary; a bounded SDK-compatible
  surface avoids those runtime costs.
- Require microVMs, gVisor, XFS, systemd or a container runtime everywhere. These
  change the target host or trust boundary. Optional quotas and delegation keep
  installation choices explicit; the [repository execution decision](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md)
  owns the separate gVisor trade-off.
- Optimize idle shells or introduce storage infrastructure before measuring the
  cost. The conditional triggers above prevent adding mechanisms without a
  demonstrated benefit to the small-host workload.

## Consequences

Consumers own more adaptation and consistency work. The provider owns helper
builds, licensing, native conformance and independent releases. Overlay and
delegation remain kernel requirements; periodic accounting remains weaker than
quotas, and same-host hardening retains shared-kernel risk. Release notices,
corresponding helper source and the threat model accompany the distribution.

## Verification

Provider readiness replays the three-command installation on Ubuntu 22.04,
Ubuntu 24.04 and Debian 12, with native conformance and published SDK gates.
The [consumer checks](../../executor-native/README.md), protected release receipt,
complete retrieval-corpus check, stageGameWorker and appImageIdentityCheck bind
the actual archive and consumer tree. The phase and JVM collectors retain their
measurement boundaries. These checks, rather than a waiting interval or a
synthetic fixture's success alone, support independent delivery.
