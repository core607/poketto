# Sandcrab Sandbox Service

Date: 2026-10-26

## Problem

Poketto dispatches sandboxed work through three implementations. Repository execution uses the static Rust supervisor ([Rust repository supervisor](../implemented/2026-10-21-rust-repository-supervisor.md)). Games use [game_worker.py](../../executor-service/game_worker.py), which builds its service, session, systemd backend and server from classes in [worker.py](../../executor-service/worker.py). The [retrieval lab](../../experiments/retrieval-lab/README.md) signs leases with its own TypeScript client. Every path starts sandboxes through the Python [launcher.py](../../executor-service/launcher.py). A further consumer, a corpus command for [creator QA](../implemented/2026-10-09-creator-qa.md), would need a fourth path or another Python service.

The duplication reaches verification. Most executor-service Python tests import the Python modules, while the Rust supervisor is covered by its unit tests, the differential frame comparisons in [test_supervisor.py](../../executor-service/test_supervisor.py) and the native probes. Seed, baseline and provisioning code in the supervisor has no unit test of its own.

One dispatch in [lib.rs](../../executor-service/rust/poketto-supervisor/src/lib.rs) mixes generic leases, execution, artifacts and admission with Poketto's seeds, Git baselines, moves and bundle provisioning, so neither part is reusable alone. The launcher's Python startup used 40.8 ms of CPU in a 2026-10-20 development-container measurement, which did not show it limiting production capacity.

No maintained sandbox service fits the default two-core, 4 GB deployment target ([continuous delivery](../implemented/2026-09-03-continuous-delivery.md)), whose current production host exposes no KVM device. E2B and microsandbox need KVM, OpenSandbox needs Docker or Kubernetes, and bubblewrap, nsjail, Anthropic's sandbox-runtime and the hakoniwa crate offer no service interface.

## Proposal

### Scope and boundary

Sandcrab is an Apache-2.0 Rust service: one static binary that runs as the daemon and as its CLI, on x64 Linux with cgroup v2 and systemd, without KVM or a container runtime. Each sandbox is a systemd transient unit with bubblewrap, the pinned seccomp filter and the resource limits of the [direct sandbox launch](../implemented/2026-10-18-direct-sandbox-launch.md). It shares the host kernel. Its threat model states that it is weaker than a microVM and targets trusted or semi-trusted code on small hosts.

Integration never requires Rust. The contract is an OpenAPI document served over HTTP/1.1 on a Unix socket, with optional loopback TCP and a token for clients that cannot use a socket. Operators define profiles in a TOML file: mounts, limits, execution account, tools and network policy, which is "none" in the first release. **A caller names a profile and cannot choose a UID, a host path or weaker isolation.**

### Primitives

- `run`: one command in a fresh sandbox, destroyed afterwards.
- Sessions: a persistent shell with open, execute, renew and close.
- Volumes: quota-bounded directories that can be cloned by reflink and mounted into sessions.
- Files: uploads and downloads as raw or tar streams.
- Host calls: a program inside the sandbox sends an opaque request that the daemon relays to the caller and answers with the caller's reply; the daemon interprets neither.
- Freeze: a flag on a file or capture request. The daemon freezes the unit for that request only, so a caller that fails mid-operation cannot leave a session frozen.

Today captures and media transfers move 64 KiB of base64 per signed request, each preceded by an authorization check ([SessionFileTransfers](../../src/main/java/io/github/core607/poketto/executor/internal/SessionFileTransfers.java)), so a 50 MB file takes 800 requests. Sandcrab streams bodies instead. Command output and host calls arrive as server-sent events on one response, replacing `BRIDGE_POLL`. One request can run a command and return selected files as tar. Connections are reused.

### Compatibility

Sandcrab implements a pinned subset of the OpenSandbox [lifecycle](https://github.com/alibaba/OpenSandbox/blob/main/specs/sandbox-lifecycle.yml) and [execd](https://github.com/alibaba/OpenSandbox/blob/main/specs/execd-api.yaml) specifications: create, get, list, delete, renew and endpoint lookup; commands with SSE output; sessions; files and directories. Its endpoints are served by path from the daemon itself. `image.uri` names a profile, requests for container images or caller-selected UIDs are rejected, and unimplemented features return an explicit unsupported error. Volumes, host calls and freezing live under a separate extension prefix. An MCP endpoint is optional and disabled by default.

Peer UID checks on the Unix socket authenticate callers by default. Ed25519-signed requests remain available, and Poketto keeps them because its application runs in a container that does not share the supervisor's UID.

### Poketto as the first consumer

Poketto composes copies, seeds, publication checks, saves, moves and journals in Java from these primitives. Repository execution, games and the creator QA corpus become profiles on separate daemon instances, each with its own service unit, execution account and slice, which preserves the separation that [pocket games](../implemented/2026-10-09-pocket-games.md) requires.

### Phases

0. Validation, without production code. Check that the OpenSandbox Kotlin SDK reaches execd through the daemon over loopback. Map saves, moves and bridge requests onto the primitives and count the operations that need extensions. Measure the specification changes from 1.1.0 to 1.2.0. Benchmark one signed frame against one keep-alive HTTP request.
1. In-repository split. Separate `sandcrab-core`, `sandcrab-launch` and the daemon from the Poketto crates under [executor-service/rust](../../executor-service/rust/Cargo.toml). The Rust launcher keeps every current check: root-owned, unwritable binaries and ancestors without symlinks; the `apply-seccomp` SHA-256 pin; bubblewrap 0.6.1 or newer. Both supervisors call its verification instead of importing launcher.py, and the baseline installer stays a Python program inside the sandbox. Tests target the Rust implementation directly.
2. Finite jobs. Games and the QA corpus command run as jobs; game_worker.py retires, and worker.py loses its production role.
3. Repository operations move to the primitives until the daemon contains no Poketto code.
4. Extraction. Move the crates to their own repository with history, publish releases with checksums and benchmarks, and pin a release in Poketto. This phase starts only after phases 1 to 3 have shipped and four weeks have passed without a change that crosses the boundary.

## Acceptance criteria

- Phase 0 is recorded in this note before phase 1 starts, including any operation that cannot be expressed without an extension.
- Phase 1:
  - The cases of [test_launcher.py](../../executor-service/test_launcher.py) pass against the Rust launcher.
  - The [launcher comparison](../../executor-native/README.md#launcher-comparison) finds identical containment for the Python and Rust launchers and reports their resource ratio.
- Phases 1 to 3:
  - The native adapter probe, the toolkit acceptance, the game worker's tests and the `load` lane pass.
  - A 50-agent round on one host with unchanged bounds, before and after, reports goodput, p95 latency, refusals and CPU split by application container, supervisor and sandbox units, and none gets worse.
- Phase 2 runs the native games scenario through the Rust daemon and removes game_worker.py.
- Phase 4 publishes the OpenAPI document, the threat model and a conformance run of the OpenSandbox Python and Kotlin SDKs against the supported subset.

## Alternatives

- **Keep the three paths and host the QA corpus in the game worker.** It ships fastest, but leaves two supervisor implementations in production.
- **Compile integrations into the daemon through Rust traits.** Policy stays close to the mechanism, but every integrator must write and build Rust.
- **Implement the E2B API.** Its SDK addresses envd at `https://49983-{sandboxId}.{domain}` or, in debug mode, at one localhost port; a local daemon would need wildcard DNS and certificates. envd uses Connect RPC, and there is no official Java SDK.
- **Contribute a bubblewrap runtime to OpenSandbox.** No API to maintain, but a Python server and a Go execd stay resident on a 4 GB host, and its bubblewrap sessions ([OSEP-0013](https://github.com/alibaba/OpenSandbox/blob/main/oseps/0013-isolated-execution-api.md)) are an inner layer that leaves cgroup limits to an outer container.
- **Use microVMs.** Stronger isolation, but E2B, including its single-host package, and microsandbox need KVM.
- **Run every command in gVisor.** [Repository-native retrieval and sandboxed execution](../implemented/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) records why it lost for the single-host deployment.
- **Keep the Python launcher until fresh starts limit capacity.** That avoids a second implementation of privileged validation; unifying the paths, not capacity, is the reason to replace it.

## Consequences

Sandcrab publicly offers a same-kernel boundary, so security reports, the threat model and pinned tool upgrades become maintenance duties. Repository operations take more requests unless streaming and combined requests replace the current chunk loops. After extraction, a change across the boundary needs a sandcrab release before Poketto can use it, and released interfaces carry a compatibility expectation that Poketto's own pre-1.0 rules do not. Following OpenSandbox costs a review of each specification release. Poketto loses no isolation property: profiles, accounts, slices and fail-closed launch carry over unchanged.
