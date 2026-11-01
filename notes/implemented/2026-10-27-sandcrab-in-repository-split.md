# Sandcrab In-Repository Split

Date: 2026-10-27
Partially superseded by: [Native repository consumer](2026-11-01-native-repository-consumer.md).

## Problem

Phase 1 of the [sandcrab proposal](../proposed/2026-10-26-sandcrab-sandbox-service.md) needs the sandbox mechanisms usable without Poketto. The supervisor mixed leases, execution, artifacts and admission with Poketto's seeds, Git baselines, moves and bundle provisioning. Both Python supervisors imported the Python launcher; the Rust supervisor invoked it through the system interpreter. Seed, baseline and provisioning code lacked unit tests.

## Decision

The [Rust workspace](../../executor-service/rust/Cargo.toml) keeps generic mechanisms behind the public service boundary:

- `sandcrab-core`: strict JSON, identifiers, protected paths, installation checks, systemd units and cgroups, finite process execution, the shell channel and opaque host-call mailboxes.
- `sandcrab-shell`: the persistent bash driver and command correlation.
- `sandcrab-launch`: tool verification (root-owned, unwritable binaries and ancestors without symlinks; the `apply-seccomp` SHA-256 pin; bubblewrap 0.6.1 or newer), policy translation and the exec into bubblewrap.
- `sandcrab`: the public daemon and CLI, including internal launch/file helpers. Operator profiles select bounds and tools; application repositories and grants remain outside the service.

[The process runner](../../executor-service/rust/sandcrab-core/src/job.rs) shares finite command timeout, bounded output and confirmed cgroup containment across native consumers. Separating this mechanism avoids duplicating supervisor cleanup; a failed containment check retains admission until recovery. The native service depends on shared mechanism crates without importing the signed protocol. Finite-job admission belongs to the native service. [Pocket games](2026-10-09-pocket-games.md) owns its application integration and separate execution budget.

The [native consumer](2026-11-01-native-repository-consumer.md) owns removal of the signed daemon, compiled repository extension and legacy launch records. One static binary supplies the native service and its internal helpers, preventing a supervisor from selecting helpers from another release generation.

## Alternatives

- **Keep the Python launcher until fresh starts limit capacity.** It avoids reimplementing privileged validation, and its startup, 40.8 ms of CPU in a 2026-10-20 development-container measurement, did not limit production. Unifying the paths, not capacity, is the reason to replace it.
- **Change launch records during the initial split.** Keeping the original records preserved a common input for the dated launcher comparison. The public native service subsequently replaces that protocol under the successor decision; historical comparison receipts retain their original scope.
- **A configured launcher path.** Configuration could pair a supervisor with another release's launcher; the derived path keeps both in one generation.

## Consequences

Rust's exec restores default SIGPIPE instead of inheriting an ignored signal from the host. A duplicate key in a launch record fails before execution.

Installations verify the independently installed native service through the [deploy reference](../../deploy/README.md). Runtime and tool installation remain operator-owned.

## Verification

`executorRustCheck` rejects product crates in the native dependency graph and runs the shared mechanism and native service tests. [Launcher tests](../../executor-service/rust/sandcrab-launch/src/tests.rs) pin tool validation and policy translation. The [native consumers](../../executor-native/README.md) exercise the public API, toolkit and game integration with confirmed containment and cleanup.

Production-host receipts: the [launcher comparison](../../executor-native/evidence/2026-10-27-sandcrab-launcher-comparison.json) found identical snapshots and toolkit results and refused tampered tools; startup CPU, memory and task ratios were 0.57, 1.13 and 1.0; the [native probes](../../executor-native/evidence/2026-10-27-sandcrab-native-probe.json) passed the adapter and game scenarios. The [50-agent rounds](../../acceptance/evidence/2026-10-27-sandcrab-50-agent-rounds.json) with unchanged bounds kept goodput, lowered p95 from 0.56 s to 0.41 s and refusals from 78 to 67, and reduced supervisor and sandbox CPU; application CPU, with no application change, rose only while the application was warming up after a restart.
