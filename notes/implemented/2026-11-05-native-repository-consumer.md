# Native repository consumer

Date: 2026-11-05

## Problem

Repository execution combined sandbox containment with Poketto's authorization,
working-copy journals, publication scope and Git writes. Evolving them as one
runtime made the sandbox's API and release depend on application-specific state.

## Decision

Poketto consumes a checksum-pinned Sandcrab release through its generic Unix API.
The application owns authorization, account-copy identity, repository commands,
publication checks, transfers and host-side Git authority. The daemon receives
sandbox profiles, templates, sessions, files and host-call bytes.

The independent distribution owns sandbox source, SDK conformance, generic
evals, benchmarks and releases. Poketto carries only its checksum pin, adapters,
operator configuration and consumer acceptance. Cross-boundary changes require
a release before adoption; rebuilding provider code inside the application image
would bypass that boundary. The protected verifier runs the exact consumer head
against the pinned archive. Application delivery verifies the installed service
and never replaces its executable or the privileged deployment updater.

Workload tools follow host distribution packages and operator upgrades. Sharing
read-only tools avoids per-copy installation and a second vendored interpreter
or native-library tree; these workload dependencies do not become daemon dependencies.

A new copy shares an immutable template of its authorized commit and reading
scope. Each template-backed command uses a fresh shell, and the application records the verified
writable layer before acknowledging it. Independent application processes recover
from that journal. An imported volume keeps its binding until disposal or expiry
and can reuse its live native lease between commands.
The [runtime contract](../../executor-service/native-repository.md) owns the
directory layout, profiles, commands and deployment prerequisites.

Public copies retain their selected projection. A changed publication fences
that copy until explicit disposal; it never promotes the copy's reading scope.
This retires automatic inspection and replacement of clean public copies.
The [projection decision](2026-09-09-public-execution-projection.md) owns the
sanitization and authorization rules.

Synchronization and moves retain ordinary candidate files and host-owned plans.
The caller applies desired changes and explicitly accepts path hashes or absence;
acceptance checks each path separately. Poketto advances only accepted baselines,
and a move publishes its retained remote candidate after every path is accepted.
This replaces automatic frozen installation and rollback of working files. The
[synchronization](2026-09-15-workspace-synchronization.md) and
[move](2026-09-09-atomic-content-moves.md) decisions retain the shared inventory,
authorization and remote-recovery rules.

The [offline importer](../../executor-service/native-migration.md) preserves
existing data and uncertain receipts. An interrupted host save is reconciled by
its durable receipt before local baseline installation can be repeated. Privileged
service installation and application deployment have separate owners.

## Alternatives

Keeping the signed application worker would retain a second execution protocol
and implementation. Moving copy journals, publication rules or Git writes into
Sandcrab would make other consumers inherit Poketto's semantics. Keeping a live
shell for every copy would retain runtime memory independently of active work.
Keeping signed-worker reflink seeds would duplicate template ownership; shared
immutable templates instead require the daemon to retain each referenced base.
Reintroducing automatic local installation would require a separately justified
atomic editing contract in the consumer, rather than application commands in the
generic daemon.

## Consequences

Durability belongs to the consumer's bounded XFS journals and verified archives.
Capturing and restoring changes adds work to each command and preserves files
across process loss. Template shell state ends with the command; imported-volume
shell state ends with its lease. Template ownership
and imported-volume ownership require different disposal paths.

The signed application's [per-lease design](../archived/2026-09-16-per-lease-command-sandboxes.md)
remains historical rationale. Current callers use the native adapter directly.
The [seed-copy design](../archived/2026-10-18-seed-copies.md) records the earlier
trade-off between independent reflinks and shared lower layers.

## Verification

`NativeRepositoryCopyClaimsTests`, `NativeDurabilityBarriersTests` and the native
save, synchronization and move tests pin the consumer's transitions.
[Workspace and game probes](../../executor-native/README.md) exercise the
production adapters and kernel boundaries with the released executable.
[Authenticated client acceptance](../../acceptance/clients/README.md) covers the
HTTP/MCP entrance and repository authority.
