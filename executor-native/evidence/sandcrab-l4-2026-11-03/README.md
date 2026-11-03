# Bundled helper evidence

These are local development receipts. [The candidate manifest](local/candidate1.json)
records the uncommitted source and artifact hashes over `ebf3022d`.
[The index](index.json) hashes the committed receipts; [the gate record](local/local-gates.json)
retains successful and failed attempts;
original logs and source snapshots remain with the operator. Final-head CI,
fresh-environment readiness and canonical 5 GB measurements remain pending.

The native candidate is `6d524d2581ab04c95641cec9111f418d66a8a200a00acf38a0defedeccded44a`,
4,236,192 bytes. Its bundled helper is
`661da77e268a68e1f83e28fd1365459b1fb196d70ec1fc17d516b9881650357d`, 141,736 bytes.
Both have no ELF interpreter or dynamic dependency. The helper's current version,
source digests and recipe belong to the
[bundle owner](../../../executor-service/rust/sandcrab-launch/bwrap/README.md).

| Receipt | Observation |
|---|---|
| [Native](local/native1.json) | 48 checks and cleanup pass, including damaged-helper refusal at startup and per launch |
| [Published SDK clients](local/sdk1.json) | 21 checks and cleanup pass with Python/Kotlin SDK 1.1.0 |
| [Flat Java/game consumers](local/java-games2.json) | Repository and native game consumers, combined acceptance and cleanup pass |
| [Managed Java/game consumers](local/java-games3.json) | Immutable repository generation and separate game bundle pass combined acceptance and cleanup |
| [Launcher](local/launcher1.json) | 38 snapshot assertions and three direct launches; its gate log retains integrity refusals and cleanup |
| [Footprint smoke](local/footprint-smoke1.json) | One idle session and finite run; all collectors and cleanup complete |

The footprint smoke uses freshly prepared workload tools without a distribution
bwrap. That tree occupies 9,098,423 bytes; the bundled helper is counted separately.
The inventory has six executable surfaces without project quotas and nine with
them. This smoke establishes neither capacity nor the canonical cold-start target.
SDK conformance and Java acceptance use real clients and sandboxes with synthetic
authority/data. No paid model batch is included.

[Java/game attempt 1](local/java-games1.json) fails before readiness because its
controller omits bwrap while copying the worker into a temporary root. Cleanup
passes. The corrected controller carries the complete helper bundle and records
its identity in attempt 2.

## Retained helper prototypes

[Prototype attempt 1](local/prototype-native1.json) uses an unpatched static helper
and fails because musl's loopback lookup creates an AF_UNIX socket under the
inherited denial. [The startup diagnostic](local/prototype-startup1.json) captures
the finite-run failure but aborts on a controller argument error before a session
outcome; it is not session acceptance. Both retain cleanup.

[The patched prototype](local/prototype-native2.json) passes 45 native checks with
the L3 CI binary. Its helper `3e4397cd` differs from the formal fixed-directory
helper only in 20 GNU build-ID bytes; [the comparison](local/helper-comparison.json)
retains the byte/section and package observations. The formal builder keeps its
checksum rejection; the private diagnostic bypass is not a release artifact.
