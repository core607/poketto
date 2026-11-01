# Poketto's Sandcrab integration

Poketto uses the public [Sandcrab service](rust/sandcrab/README.md) for process containment, sessions, volumes, file transfer and opaque host calls. The application owns accounts, current grants, repository initialization, baselines and remote writes. The [consumer decision](../notes/implemented/2026-11-01-native-repository-consumer.md) owns that boundary. The daemon does not interpret Git bundles or require Poketto identifiers.

## Runtime

The native reference owns Linux requirements, operator profiles, authentication, resource bounds, installation and recovery. Build the pinned static binary with `./gradlew buildExecutorRust`; the output is `build/executor-service/bin/sandcrab`. [Independent deployment](../deploy/README.md#native-service-dependency) verifies the installed service. Runtime and tool installation remain explicit operator work.

[prepare-tools.sh](prepare-tools.sh) creates a new protected toolchain directory. [install-sandbox-tools.sh](install-sandbox-tools.sh) adds the distribution-maintained image, HTML/XML, PDF, Office, archive and font toolkit. On an existing host, inspect its `apt-get --simulate install` package list first. The installer changes no host-wide command aliases, performs no system upgrade and restarts no service. Validate the tools through the native entrance and the deployed connector.

The application, execution account and root supervisor have separate identities. Root owns the binary, profiles and tools; neither unprivileged account can replace them. Unix peer credentials authorize the application account. The application verifies the root-owned endpoint before connecting. Package executable Python sources with LF line endings. Missing isolation capabilities or failed tool validation reject work.

## Capacity budgets

The native profile and enclosing slice bound commands, temporary storage, tasks and CPU. XFS bounds durable volumes independently. Application copy count, command admission, original archives and transfer storage have separate limits; the [usage configuration](../docs/usage.md) and native profile own their settings. Byte ceilings do not reserve each copy's full allowance. Unconfirmed allocations and containment retain their identities and admission until reconciled.

Size a slice for its supervisor, retained relays, connection handling, helpers, live sandboxes and charged cache pages. Count threads as tasks and include workload-created background processes. Attribute `memory.current` with process PSS and `memory.stat`; executable RSS alone does not describe the pool. Keep headroom for other services and the operating system.

[resource_pool.py](resource_pool.py) verifies effective cgroup limits and service membership for deployment. The example installation uses:

```sh
python3 /opt/sandcrab/resource_pool.py --service sandcrab-repository.service
```

`OOMScoreAdjust=-900` biases victim selection without immunity to limits. `OOMPolicy=stop` contains the service after a member OOM; durable volumes survive. Verify parent-pool and service-local OOM independently, including their kernel victims and cleanup.

## Native service dependency

The application does not install or repair its sandbox service. Follow the [deployment reference](../deploy/README.md#native-service-dependency) for its identity and read-only health checks. Preserve existing retained data during the [offline native import](native-migration.md). A code upgrade does not authorize deleting unsaved work, journals or original baselines.

## Account copy storage

The account journal and immutable original archives remain outside command-writable volumes. [Account working copies](../notes/implemented/2026-09-14-account-working-copies.md) owns retention and durability; the native storage profile owns volume quotas. Authoritative baselines come from the application journal, never from a sandbox's Git HEAD, index or configuration. Selected saves capture exact bytes and use the domain writer. Missing or uncertain state fails closed; command success does not acknowledge a remote write.

Normal commands share a shell and background processes. Timeout, output exhaustion, cancellation and revocation end the affected runtime through confirmed containment. The retained copy can survive runtime replacement; its files are not replayed or rebuilt implicitly. The application owns retention and disposal after native containment, and cleanup never undoes remote Git commits.

## Working-copy identity

`repo_exec` requires `expectedCopyId`. `"new"` selects an available copy in the account, workspace and reading scope, creating a default or fork within quota. Busy or recently used copies can require a fork; when quota is full and no unclaimed copy is available, the request refuses. Afterwards pass the returned `copyId`. Matching Git commits and transport reconnection do not establish copy identity.

A copy retains its reading scope when a grant gains private-read permission; a public-only grant cannot select a full copy. Current grants authorize every command, host operation and returned artifact. A changed publication refuses access to the old public copy while retaining its work; `new` can select current publication within quota. Results expose retention, resumed state and an interrupted command when known.

`EXECUTION_REFUSED` has `executed: false`, the known copy ID and a recovery reason. `EXECUTION_UNCONFIRMED` means work may be partial: inspect the named copy and reconcile remote uncertainty with `poketto recover` instead of replaying the command. [Usage](../docs/usage.md#mcp-and-isolated-execution) owns the complete request and recovery contract.

`repo_discard` requires the exact copy ID, current execution permission and account ownership. A busy writer prevents deletion. `DISCARDED` and `ABSENT` confirm completion; an unconfirmed result retains the same identity for reconciliation. Remote Git remains unchanged.

## Sandbox CLI

The application supplies its `poketto` client inside an admitted copy. It uses Sandcrab's opaque host-call channel, which grants no content authority by itself. [Usage](../docs/usage.md#mcp-and-isolated-execution) owns commands, explicit plan acceptance, selected saves, media proposals and portable exports. The generic daemon contains no application CLI or repository extension.

## Returned artifacts

Artifacts are immutable application-owned snapshots scoped to an account copy and reading scope. They survive replacement of its native shell, expire after five minutes and are released on copy disposal or application shutdown. Each copy retains at most sixteen artifacts and 256 MiB, with 128 MiB per file, within the application's transfer capacity. Commands cannot modify the protected snapshot bytes.

Every read rechecks current authorization and public publication where applicable. A broader credential cannot enlarge the artifact's original reading scope. `get_artifact` previews validated raster images, pages UTF-8 text or returns binary resource pages; `format=bytes` selects binary pages. Offsets count bytes, a null `nextOffset` means EOF, and image preview requires offset zero. Invalid image bytes or digests fail preview. Artifacts do not publish content, upload managed originals or create URLs. [Usage](../docs/usage.md#mcp-and-isolated-execution) owns page bounds and response examples.

## Verification

`./gradlew executorRustCheck executorServiceTests` checks the native dependency graph, Rust formatting, Clippy and locked tests, plus Linux shell framing, resource-pool validation and OOM evidence checks. Windows uses the pinned Linux test image. These tests need no signed protocol fixture or Python packages beyond the standard library.

[Native consumer and toolkit acceptance](../executor-native/README.md), [durability](../executor-native/durability.md), [HTTP clients](../acceptance/clients/README.md) and [independent evals](../evals/README.md) exercise actual native services. Require the scenario assertions and confirmed cleanup, retaining failed attempts and exact runtime identities. Protocol peers, native fixtures and production acceptance establish different scopes.

The [resource pool probe](resource_pool_probe.py) independently checks real systemd/cgroup limits and tmpfs charging in an owned 96 MiB pool. Run it beside `resource_pool.py` and `native_pool.py` with a new `--output` path. Its [recorded result](resource-pool-evidence.json) is fixture evidence, not sandbox or production-capacity acceptance.
