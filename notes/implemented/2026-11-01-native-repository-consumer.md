# Native Repository Consumer

Date: 2026-11-01

## Problem

A sandbox service cannot serve unrelated projects if its protocol requires Poketto accounts, repository revisions, publication proofs and signed editing operations. Keeping a second application executor would also leave two implementations of admission, authorization and failure recovery. The [Sandcrab proposal](../proposed/2026-10-26-sandcrab-sandbox-service.md) chooses a public service contract and consumer adaptation; it retains the production cutover, performance and extraction requirements.

## Decision

Poketto consumes public native sessions, volumes, files and opaque host calls. Sandcrab owns process containment and operator profiles. Poketto owns account identity, current grants, Git initialization, publication, remote writes and copy retention. A generic volume identifier carries no repository authority, and the daemon interprets neither Git bundles nor host-call bodies.

The application journal remains outside writable volumes. It retains immutable originals, per-path save baselines, operation identities and uncertain outcomes. Selected saves use captured bytes and the domain writer; local Git state cannot acknowledge a remote save. Native file transfers do not supply the former worker's atomic editing operations. Synchronization and moves therefore prepare retained originals and candidates, then accept explicit path/content expectations through application-owned plans. Interruption preserves the plan and its exact remote attempt instead of replaying a command.

Runtime lifetime and file retention are separate. Normal commands reuse their native shell; a contained runtime can be replaced without replacing its volume or originals. Unknown allocation or containment retains its reservation until reconciled. Current authorization gates commands, host operations and returned data, including retained artifacts. A transport disconnect does not own the account's files.

Public copies retain their original scope and projection. A changed publication refuses further execution and artifact access while preserving the volume. `new` selects current publication within the account quota; the stale copy still counts. Only explicit disposal or the retention policy removes it. This supersedes the inspection/rebuild choice in [public execution projection](2026-09-09-public-execution-projection.md): mutable Git configuration, index flags and caches cannot authorize deletion. If no unclaimed copy is available at the account quota, `new` refuses instead of redirecting into a claimed default. [Concurrent copies](2026-10-18-concurrent-agent-copies.md) retains the claim and idle-copy reuse policy.

The signed Java executor and its dedicated probe are removed. Shared journals, baselines and data-only legacy import remain: code retirement does not authorize discarding existing work. The offline importer requires established writer containment and storage locks, preserves work and home plus the journal's original baselines, and changes the native binding only after verified import. Unsupported or uncertain source states require reconciliation. The retained legacy service's Rust/Python removal remains part of the proposal's complete consumer and cutover acceptance.

## Alternatives

- **Keep the signed application path beside native execution.** It duplicates lifecycle and authorization ownership and makes an internal protocol another supported integration surface. Existing data is protected through explicit import, not a second runtime adapter.
- **Expose every old editing operation as a daemon extension.** That preserves application-specific transactions inside general infrastructure. Application plans accept more explicit client steps in exchange for a public service that needs no Poketto semantics or compiled extension.
- **Inspect Git and rebuild a supposedly clean public copy under its ID.** This preserved IDs and avoided retaining stale copies, but a false clean destroys work. Repository configuration is writable, and even ordinary tool caches complicate the definition of clean. Refusal plus a separate current projection makes deletion an explicit owner action.
- **Keep Git-specific seeds in the service.** Reflink reuse remains a useful storage mechanism, but interpreting commits and repository permissions belongs to the consumer. Native initialization imports an ordinary bundle instead of asking for the signed worker's seed operation.

## Consequences and verification

Poketto bears adaptation costs and must present uncertainty and explicit editing steps to clients. Stale public work remains inaccessible while its proof is invalid and consumes quota until disposal or expiry. The public projection's approved-fields-only construction, parentless Git history and current-publication checks remain unchanged. [Per-lease sandboxes](2026-09-16-per-lease-command-sandboxes.md), [seed copies](2026-10-18-seed-copies.md) and [local supervisor](2026-09-05-local-execution-supervisor.md) retain their legacy service details; their transport and initialization choices do not define the native consumer.

`NativeRepositoryExecutorTests`, `NativeRepositoryCopyClaimsTests`, `NativeRepositoryPublicTests` and `NativeRepositoryLifecycleTests` pin consumer ownership and admission. `NativeRepositoryMetricsTests` keeps monitoring examples tied to the native exporter. [Native acceptance](../../executor-native/sandcrab.md), [durability](../../executor-native/durability.md) and [HTTP consumer checks](../../acceptance/clients/README.md) exercise the real service and cleanup. These checks do not establish production migration, comparable 50-agent performance, full-corpus retrieval acceptance or model usability.
