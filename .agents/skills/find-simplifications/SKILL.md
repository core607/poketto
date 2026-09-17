---
name: find-simplifications
description: Use when assessing simplification opportunities or implementing a specific cleanup of the maintained code surface.
---

# Finding Simplifications

Read the decisions that own a candidate before calling its structure unnecessary; follow the document routes in [AGENTS.md](../../../AGENTS.md). A recorded decision is evidence to understand, not immunity from reconsideration, and a note describing what one change did is not a decision that the remainder is intentional.

## Strong candidate shapes

- A public method, event, option, data representation, helper, package, or test artifact has no production consumer.
- Tests or docs are the only consumers, and the behavior they pin is no longer required.
- Two stores, events, caches, or projections mirror the same authoritative fact.
- A compatibility path protects no released consumer during the pre-release phase.
- A separate abstraction or component exists only for speculative future needs.
- Lifecycle state uses several sentinels, promises, or flags to represent one operation or settlement point.
- Hand-written infrastructure duplicates a maintained dependency or platform primitive, and replacing it would delete owned implementation and tests rather than wrap the same complexity.
- A standing exemption, suppression, or opt-out marks work that was never finished.

Typos, isolated naming preferences, and "this looks complex" without call-site evidence are not candidates.

## Establish consumers before removing

Search exact names, wire strings, configuration keys, event names, and direct and dynamic registration paths with `rg`, and inspect every hit. Separate production consumers (runtime code, configuration, entrypoints, deployment scripts) from verification and prose, and inspect anything ambiguous, such as a script that may be a shipped smoke path, before deciding.

Tests pin behavior, not correctness. When required behavior changes, remove or revise the obsolete tests and keep the ones covering the remaining contract.

## Reject a candidate when

- a real production consumer exists;
- the change is actually a new product decision, which needs a proposed note first;
- a security or durability rule owns the complexity;
- the churn does not reduce the maintained surface, including a dependency swap whose glue relocates the same complexity.

## Output

A durable change to behavior, architecture, process, data, or a cross-file contract gets a proposed decision record with the strongest alternative and observable acceptance criteria. A small local action whose desired change is already decided gets a named `TODO` stating reason and action; never park an unresolved design decision in a comment. Report "no strong candidate" when the evidence does not clear the bar rather than padding the result.
