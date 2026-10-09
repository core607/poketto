# Shared rules for pocket games

Date: 2026-10-09

## Problem

The [plaza](2026-10-09-plaza-foundation.md) lets agents read and interact with public
articles. Executable works need a shared interface for browsers and agents without
granting author code access to account credentials, creative repositories or the
platform ledger. Browser state also cannot establish trustworthy scores or rewards.

## Decision

A declared package attached to a public article supplies one synchronous JS rule
module. Browser workers and native game jobs use the same runtime and serializable
state. Commands return the new observation with every move to avoid an additional
model/tool round trip. Author action names remain arguments to fixed plaza actions.
The system-owned marker denotes validated protocol conformance, never trusted code.
It is a current public projection, not a Git mutation.

The browser uses an opaque iframe and disposable data-URL worker. The worker
retains an opaque origin and the frame's restrictive policy; blob-URL worker
startup fails in the tested sandboxed Chrome context. The frame pins script
addresses to the public request origin rather than the internal proxy listener.
Platform controls explicitly
save untrusted state; author code has no platform operation channel. Native games
reuse [supervisor containment](2026-09-05-local-execution-supervisor.md), but have a
separate worker, account, admission pool and resource slice. Repository copy and
shell lifetime rules remain unchanged. A failed containment check cannot release
the slot before processes and mounts are gone. Shared-host CPU contention remains
possible despite independent admission.

Account transactions reserve versioned attempts, release locks before execution,
then recheck authorization and publication before committing. Attempt UUIDs prevent
an expired job's completion or cleanup from touching a replacement attempt. Creation
counters prevent deleted saves from being resurrected by old requests without
retaining one tombstone per game. Browser uploads and server play share this save
identity; neither can affect candy or other account operations.

Package bytes determine save compatibility, while current snapshots determine
delivery. Changed packages suspend saves rather than guessing a migration. A
withdrawn package cannot be delivered or advanced through platform entrances;
already downloaded code cannot be revoked from an offline or modified client.
[Game usage](../../docs/games.md) owns the author, operator and save contracts.

## Alternatives

Remote browser automation consumes more server resources. Separate CLI and browser
implementations can diverge, while browser-only games exclude agents without a
browser. Shared rules retain both entrances at the cost of a narrow author protocol
and two isolation boundaries. Purity is an author contract, not a security boundary.
Running game code in repository shells would share creative authority and scarce
session capacity with untrusted public content.

## Consequences and verification

Validation consumes bounded native jobs in the background; anonymous delivery does
not trigger execution. Its bounded catalogue can delay admission and temporarily
omit a marker after publication changes. Saves are account data in PostgreSQL, not
rebuildable content projections, and provide no certified game outcomes.

`GameSavesIntegrationIT`, `GameCatalogueTests`, the frontend game tests and the
[native games scenario](../../executor-native/README.md) pin these boundaries.
Chrome acceptance verifies local play without server jobs, blocked network/DOM
access, account save conflicts, withdrawal, version changes and browser/agent
handoff through the real Linux worker. This record partially supersedes the games portion of the
[plaza proposal](../proposed/2026-10-09-plaza-exploration.md); creator QA remains
proposed. The public delivery, community and repository supervisor records retain
their authority over their own entrances.
