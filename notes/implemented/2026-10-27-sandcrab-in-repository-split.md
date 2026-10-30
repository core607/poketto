# Sandcrab In-Repository Split

Date: 2026-10-27

## Problem

Phase 1 of the [sandcrab proposal](../proposed/2026-10-26-sandcrab-sandbox-service.md) needs the sandbox mechanisms usable without Poketto. The supervisor mixed leases, execution, artifacts and admission with Poketto's seeds, Git baselines, moves and bundle provisioning. Both Python supervisors imported the Python launcher; the Rust supervisor invoked it through the system interpreter. Seed, baseline and provisioning code lacked unit tests.

## Decision

The [Rust workspace](../../executor-service/rust/Cargo.toml) separates generic crates from Poketto's:

- `sandcrab-core`: strict JSON, identifiers, protected paths, systemd units and cgroups, the shell channel, the host-call mailbox, captures, installs and artifacts. Poketto's bridge operations and its `work/repository` tree, with `.git` reserved, are parameters.
- `sandcrab-shell`: the persistent bash driver; consumers select the command-correlation variable.
- `sandcrab-launch`: tool verification (root-owned, unwritable binaries and ancestors without symlinks; the `apply-seccomp` SHA-256 pin; bubblewrap 0.6.1 or newer), policy translation and the exec into bubblewrap.
- `sandcrab-daemon`: signed requests and replay, leases, execution, host calls, captures, installs, artifacts, admission and the socket server. Product operations arrive through an `Extension`; the runtime holds mounted storage through a `Volume` and an extension's staged transfer through `Staged`.
- `poketto-supervisor`: provisioning from bundle exports or seeds, Git baselines, discards and moves. `poketto-launch` maps a record's mode to its fixed command; the baseline installer stays a Python program run inside the sandbox.

[The process runner](../../executor-service/rust/sandcrab-daemon/src/job.rs) shares finite command timeout, bounded output and confirmed cgroup containment across native consumers. Separating this mechanism avoids duplicating supervisor cleanup; a failed containment check retains admission until recovery. [Pocket games](2026-10-09-pocket-games.md) owns its application integration and separate execution budget.

Records and policies keep their format, so both launchers run the same record in the [launcher comparison](../../executor-native/README.md#launcher-comparison). The Rust supervisor verifies the tools and programs before serving and runs the `poketto-launch` of its own release generation; configuration cannot name another.

Phase 3 removes the traits; the proposal still rejects trait integration for the public interface. Until then the daemon keeps the signed frame schema, the `commit`, `gitCommit` and `scope` labels it returns, and the `poketto-exec-` unit name check.

## Alternatives

- **Keep the Python launcher until fresh starts limit capacity.** It avoids reimplementing privileged validation, and its startup, 40.8 ms of CPU in a 2026-10-20 development-container measurement, did not limit production. Unifying the paths, not capacity, is the reason to replace it.
- **A generic launcher whose record carries the command and environment.** Poketto's modes would leave the launcher, but the previous launcher could not run that record, so the comparison would lose its common input. It can follow the comparison.
- **A configured launcher path.** Configuration could pair a supervisor with another release's launcher; the derived path keeps both in one generation and leaves an existing configuration valid after a rollback.

## Consequences

The Python launcher passed on the ignored SIGPIPE it inherited from systemd; Rust's exec restores the default. Shell-driver commands already had the default; only the initializer's shell and its Git children change. A duplicate key in a launch record fails the launch.

Installations install the matching updater before deploying the new file set ([deploy reference](../../deploy/README.md)); the supervisor ignores the obsolete `launcher` key.

## Verification

`executorRustCheck` runs the launcher tests on real root-owned files, the provisioning, seed and baseline tests, and the translation of each worker mode's policy in [policies.json](../../executor-service/rust/poketto-launch/testdata/policies.json). [test_launcher.py](../../executor-service/test_launcher.py) keeps that fixture equal to the Python worker's policies. The [native games scenario](../../executor-native/README.md) exercises the generic service through its Java consumer.

Production-host receipts: the [launcher comparison](../../executor-native/evidence/2026-10-27-sandcrab-launcher-comparison.json) found identical snapshots and toolkit results and refused tampered tools; startup CPU, memory and task ratios were 0.57, 1.13 and 1.0; the [native probes](../../executor-native/evidence/2026-10-27-sandcrab-native-probe.json) passed the adapter and game scenarios. The [50-agent rounds](../../acceptance/evidence/2026-10-27-sandcrab-50-agent-rounds.json) with unchanged bounds kept goodput, lowered p95 from 0.56 s to 0.41 s and refusals from 78 to 67, and reduced supervisor and sandbox CPU; application CPU, with no application change, rose only while the application was warming up after a restart.
