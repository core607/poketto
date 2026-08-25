---
name: find-simplifications
description: Use when asked to find simplification opportunities, clean up code, or shrink the maintenance surface, or when implementation reveals dead code, duplication, or overbuilding.
---

# Finding Simplifications

This skill is guidance, not a checklist. The output is **well-evidenced proposals** (notes or inline TODOs), not direct deletions — removals go through the normal change process.

Read [AGENTS.md](../../../AGENTS.md), the [requirements note](../../../notes/implemented/2026-08-25-requirements-and-architecture.md), and any decision record that owns the area before calling a structure unnecessary. A recorded decision is evidence to understand, not immunity from reconsideration.

## Strong candidate shapes

- A public method, event, option, data representation, helper, package, or test artifact has no production consumer.
- Tests or docs are the only consumers, and the behavior they pin is no longer required.
- Two stores, events, caches, or projections mirror the same authoritative fact.
- A compatibility path protects no released consumer during the pre-release phase.
- A separate abstraction or component exists only for speculative future needs.
- Lifecycle state uses several sentinels, promises, or flags to represent one operation or settlement point.
- Hand-written infrastructure duplicates a maintained dependency or platform primitive, and replacing it would delete owned implementation and tests rather than wrap the same complexity.

Typos, isolated naming preferences, and "this looks complex" without call-site evidence are not decision-record candidates.

## Survey before selecting

Start with the largest or most cross-cutting production surfaces, not only symbols a static tool reports. Search exact names, wire strings, configuration keys, event names, and both direct and dynamic registration paths with `rg`. Inspect every hit before classifying it.

Separate consumers into:

- production: runtime code, configuration, entrypoints, deployment scripts, and real compositions;
- verification: tests, fixtures, snapshots, and examples;
- prose: README, docs, comments, and decision records;
- ambiguous: examples or scripts that may be a shipped smoke path — inspect before deciding.

## Prove or reject each candidate

For each candidate, record:

1. the exact current surface and its owner;
2. production, verification, and prose consumers;
3. the maintenance cost or duplicated fact;
4. the smallest removal or fold that reduces the owned surface;
5. behavior, flexibility, or evidence lost;
6. tests, docs, schemas, notes, and generated artifacts that must change with it;
7. the evidence that would falsify the proposal.

Reject or downgrade a candidate when a real consumer exists, the change is actually a new product decision, a security or durability rule owns the complexity, or the churn does not reduce the public or maintained surface.

Tests pin behavior, not correctness. When required behavior changes, identify obsolete tests for removal or revision and preserve tests for the remaining contract.

## Trust and lifecycle checks

For every defensive copy, validator, queue, cache, and callback capture, name the trust boundary and the next owner. Validation belongs at untyped, persisted, process, network, model/tool, or user-input boundaries; typed same-process calls do not justify hostile-input machinery by default.

For asynchronous code, map readiness, cancellation, publication, rollback, and disposal to owners and settlement points. Several mechanisms representing the same liveness fact are a simplification candidate; separate mechanisms remain justified when they protect distinct publication, rollback, callback-containment, or quiescence guarantees.

## Dependency substitutions

Prefer a platform primitive or maintained dependency only when it covers the exact required behavior, is healthy for the project's deployment floor, has an acceptable transitive footprint, and produces net deletion after glue is counted. A wrapper that relocates the same complexity is not a simplification.

## Choose the output

- Write or update a proposed decision record for a durable change to behavior, architecture, process, data, or a cross-file contract. Include the strongest alternative and observable acceptance criteria.
- Use a named `TODO`, `FIXME`, or `XXX` only for a small local action whose desired change is already decided. State the reason and action; do not park an unresolved design decision in a comment.
- Report "no strong candidate" when evidence does not clear the bar. Do not pad the result.

For the handoff, name areas surveyed, exclusions, candidates accepted or rejected, and representative evidence. Implementation begins only after the proposal is accepted.
