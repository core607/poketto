# Sandcrab Sandbox Service

Date: 2026-10-26

## Problem

Repository execution and games share Rust mechanisms ([in-repository split](../implemented/2026-10-27-sandcrab-in-repository-split.md), [shared process runner](../implemented/2026-10-27-sandcrab-in-repository-split.md)). The [retrieval lab](../../experiments/retrieval-lab/README.md) signs leases through TypeScript.

The [retained-session service](../../executor-service/rust/sandcrab-daemon/src/lib.rs) still requires Poketto identities, revisions and signed requests. [Native runs, sessions, volumes, files, command events and host calls](../../executor-service/rust/sandcrab/README.md) are independent. [OpenSandbox integration](../../executor-service/rust/sandcrab/opensandbox.md) documents the implemented SDK subset. Repository adoption remains in scope; replicating repository operations would retain product coupling.

The service must fit the default two-core, 4 GB deployment target ([continuous delivery](../implemented/2026-09-03-continuous-delivery.md)) without requiring KVM or a container runtime. Other projects need a usable execution service without adopting Poketto's content model.

## Proposal

### Scope and boundary

**Sandcrab defines its public contract; Poketto adapts.** Independent consumers and extensibility govern the interface, not preservation of Poketto's operations, journals or copy behavior. Every primitive needs a use outside Poketto.

Sandcrab is an Apache-2.0 Rust daemon and CLI in one static x64 Linux binary, using cgroup v2, systemd and [direct sandbox launch](../implemented/2026-10-18-direct-sandbox-launch.md). Its same-kernel boundary targets trusted or semi-trusted code and is weaker than a microVM.

Integration requires neither Rust nor product identifiers. Native HTTP, CLI and OpenAPI share lifecycle, errors and ownership. Unix sockets authenticate peer UIDs; loopback TCP uses tokens. Operator-owned TOML profiles set mounts, limits, accounts, tools and network policy, initially "none". Callers select allowed profiles, never host paths, UIDs or weaker isolation. Labels grant no authority.

### Primitives

- `run`: one command in a fresh sandbox, destroyed afterwards.
- Sessions: own a workspace and an aggregate process budget, with open, execute, renew and close. Named shell contexts retain independent state and share that workspace; closing a context preserves the files.
- Volumes: quota-bounded directories, empty or populated from ordinary file archives, which can be cloned by reflink and exclusively attached to sessions. Git bundle interpretation belongs to a consumer's initialization job.
- Files: uploads and downloads as raw or tar streams.
- Host calls: a program inside the sandbox sends an opaque request that the daemon relays to the caller and answers with the caller's reply; the daemon interprets neither.
- Freeze: a flag on a file or capture request. The daemon freezes the unit for that request only, so a caller that fails mid-operation cannot leave a session frozen.

Captures already stream archives ([wire reference](../../executor-service/README.md#wire-version-1)); incoming moves and materialization remain chunked. Public file transfers stream bounded bodies, staging uploads before briefly freezing to validate and install. Commands and host calls use SSE over reusable connections. Cancellation, expiry and shutdown retain admission until containment. Errors distinguish refusal from uncertain execution, with explicit recovery behavior.

Atomic batches and frozen jobs need independent uses and failure contracts before implementation; consumer orchestration may replace old operations. Durable operation IDs bind plan digests across reconnects and restarts, separately from transport IDs. Git transactions and reconciliation stay outside Sandcrab.

### Compatibility

The adapter implements the OpenSandbox `release-1.1.0` [lifecycle](https://github.com/alibaba/OpenSandbox/blob/release-1.1.0/specs/sandbox-lifecycle.yml) and [execd](https://github.com/alibaba/OpenSandbox/blob/release-1.1.0/specs/execd-api.yaml) subset: create, get, list, delete, renew, endpoint lookup, SSE commands, sessions, files and directories. Evidence pins the specification commit and Python/Kotlin SDK versions. Native clients need neither SDK. `image.uri` selects a profile; images, caller-selected UIDs and unsupported features are rejected. Extensions use a separate prefix; optional MCP defaults off.

Compatibility is checked through [both published SDKs](../../evals/sdk/README.md), not endpoint resemblance. Authentication adapters may support Ed25519 for deployments that need it; the existing signed Poketto envelope does not define the native API.

### Poketto as the first consumer

Poketto owns repositories, publication, writes and recovery. It may redesign copy semantics, updating owning decisions, docs and tests together. Cutover preserves existing data, including unsaved work, and current authorization. Repository and game accounts, units and slices remain separate ([pocket games](../implemented/2026-10-09-pocket-games.md)). Creator QA is an optional consumer.

The [native repository owner](../../src/main/java/io/github/core607/poketto/executor/internal/NativeRepositoryExecutor.java) bounds roots by copy, scope and current grant, with per-command authority and cancellation. Normal completion retains background processes; revocation and idle expiry close roots. Public-proof checks cover idle/busy roots, output, artifacts and media. Changed copies remain; `new` may allocate current publication within quota. Mutable Git state cannot authorize deletion, replacing [public projection](../implemented/2026-09-09-public-execution-projection.md)'s inspection/rebuild choice at cutover.

The durable journal owns baselines independently of writable Git HEAD. Per-path captures feed the atomic remote writer. Media import/link propose explicit index edits; artifacts survive runtime replacement. Local status has an explicit remote query, and bounded previews preserve complete receipts ([usage](../../docs/usage.md#mcp-and-isolated-execution)). Preparation retains private originals and candidates beside the account journal; users edit working files, then accept explicit path/content expectations. This avoids treating uploads without compare-and-replace as safe automatic edits. Partial confirmations survive restart; one plan retains backups until explicit release or copy disposal/expiry. Moves retain the domain plan and exact remote attempt; path confirmations occur separately, and later edits remain unsaved ([move ownership](../implemented/2026-09-09-atomic-content-moves.md)). Migration, activation and real consumer/performance acceptance remain cutover requirements.

**Migration inventory.** Volumes and consumer initialization replace OPEN/ATTACH/DISCARD; frozen snapshots replace CAPTURE and ARTIFACT_CREATE; opaque host calls replace BRIDGE_POLL/COMPLETE. Consumers retain artifacts. MATERIALIZE, MOVE and BASELINE become application workflows; new atomic/frozen-job primitives still require independent uses and recovery contracts.

### Phases

0. Validation without production code, recorded below.
1. In-repository split, shipped as the [in-repository split](../implemented/2026-10-27-sandcrab-in-repository-split.md).
2. Finite jobs, shipped as [shared process runner](../implemented/2026-10-27-sandcrab-in-repository-split.md).
3. Public service and adoption. Runs, sessions, volumes, files, command SSE and host calls provide profiles, CLI, authenticated loopback TCP and OpenAPI; [Inspect](../../evals/README.md) evaluates independent API and programming tasks. Games use the public API; adapt repository execution, remove its compiled extension and product fields, and retire the old protocol without shims. The operation map inventories migration, not mandatory public operations.
4. Extraction. Move generic crates with history, publish checksums, threat model, examples and evals, then pin a release in Poketto. Phases 1 to 3 must ship before four consecutive weeks without cross-boundary changes. Record the last deployed change; another restarts the interval. The operator selects repository identity, visibility and initial release version before publication.

### Phase 0 findings

Recorded on 2026-10-27 against OpenSandbox `main` and the tags `release-1.1.0` and `release-1.2.0-rc.2`.

**SDK reachability.** Kotlin constructs `protocol://endpoint` and forwards endpoint headers, allowing path-routed execd and token authentication. Its OkHttp integration exposes a connection pool, not Unix transport. Python accepts an httpx transport. Neither observation proves SDK conformance.

**Specification changes.** The compared tags changed command/session error fields and sandbox lifecycle/network options. Versioned conformance must detect these differences; unsupported lifecycle and network options remain rejected.

**Benchmark.** The [transport experiment](../../experiments/sandcrab-transport/README.md) compares frames, HTTP and streamed transfers. Its [developer-container receipt](../../acceptance/evidence/2026-10-27-sandcrab-transport-benchmark.json) supports streaming over chunk loops; it is not production-capacity evidence.

## Acceptance criteria

- Phase 3:
  - A fresh installation runs documented CLI and HTTP examples without Poketto configuration, identifiers, Git content or a compiled extension. A batch code runner and a build/test agent exercise the same public service as Poketto.
  - Contract, lifecycle, failure-injection and native isolation tests verify the public entry paths and cleanup. A sandbox failure never falls back to an unrestricted subprocess. Protocol fixtures alone do not prove containment.
  - [Inspect](https://inspect.aisi.org.uk/) runs agent evals through the [shared entrance](2026-10-28-agent-evaluation-entrance.md), with an independent Sandcrab suite using native interfaces. Use fixed clients/models, repeated natural-language tasks and independent output/file/state grading. Cover execution, sessions, files, cancellation and recovery. Record configuration, code/corpus revisions, failures, calls, latency, usage and known/unknown costs, retaining all attempts. Separate scripted and model-directed results. Estimate and bound paid batches; publish no private content or credentials.
  - The native adapter probe, the toolkit acceptance, the game supervisor's tests and the `load` lane pass.
  - A 50-agent round on one host with unchanged bounds, before and after, reports goodput, p95 latency, refusals and CPU split by application container, supervisor and sandbox units, and none gets worse.
  - Fix warm-up, repetition and comparison rules before collecting the load comparison; retain every round and both client and server timings. Do not replace a failed metric with another or rerun selectively for a passing sample.
- Phase 4 publishes the OpenAPI document, threat model, exact SDK conformance runs and generic-consumer eval results. Poketto runs against the checksummed release and passes its adoption checks.

## Alternatives

- **Keep the three paths and host the QA corpus in the game worker.** It ships fastest, but leaves two supervisor implementations in production.
- **Compile integrations into the daemon through Rust traits.** Policy stays close to the mechanism, but every integrator must write and build Rust.
- **Preserve every Poketto semantic as a generic extension.** It reduces adapter changes but makes unrelated projects inherit its transactions, identities and lifecycle. Consumer adaptation is the chosen cost of an independently useful public service.
- **Implement the E2B API.** Its SDK addresses envd at `https://49983-{sandboxId}.{domain}` or, in debug mode, at one localhost port; a local daemon would need wildcard DNS and certificates. envd uses Connect RPC, and there is no official Java SDK.
- **Contribute a bubblewrap runtime to OpenSandbox.** No API to maintain, but a Python server and a Go execd stay resident on a 4 GB host, and its bubblewrap sessions ([OSEP-0013](https://github.com/alibaba/OpenSandbox/blob/main/oseps/0013-isolated-execution-api.md)) are an inner layer that leaves cgroup limits to an outer container.
- **Use microVMs.** Stronger isolation, but E2B, including its single-host package, and microsandbox need KVM.
- **Run every command in gVisor.** [Repository-native retrieval and sandboxed execution](../implemented/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) records why it lost for the single-host deployment.

## Consequences

Poketto absorbs adaptation costs, including simpler copy semantics. Sandcrab owns isolation, API, examples and evals; independent consumers expose integration costs that product-specific probes miss. After extraction, cross-boundary changes require a Sandcrab release before adoption. OpenSandbox adds versioned conformance work without promising its entire API.

Per-session slices add kernel bookkeeping so shells share resource and file-freeze boundaries. Per-shell budgets would multiply the caller's allowance; shell-owned workspaces would make context closure destroy unrelated files.
