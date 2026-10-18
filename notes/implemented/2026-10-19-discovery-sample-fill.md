# Filling short discovery samples

Date: 2026-10-19

## Problem

A strict four-card space quota makes a catalog with only two spaces stop at eight
cards even when those spaces contain many public articles. It limits browsing
depth without providing additional space diversity.

## Decision

The [initial selection](2026-09-23-authoring-and-discovery-experience.md) remains
the first allocation. Short samples receive random, distinct supplements in
rounds across the selected spaces, with the round order shuffled to avoid favoring
catalog order. Exhausted spaces yield their remaining allocation. The
[usage reference](../../docs/usage.md) owns the count and page limits.

This partially supersedes the strict per-space quota in the initial-selection
record. The [batch contract](2026-09-14-public-discovery-batches.md) still owns
replay, withdrawal, metadata retention and expiry. Supplements use the same
snapshot and exact tag filter as the initial sample; an existing batch is never
refilled after withdrawal.

## Alternatives and consequences

Increasing every space's fixed quota grows large-catalog batches unnecessarily.
Starting another batch on each page repeats favored articles and breaks stable
navigation. Adaptive filling instead lets larger spaces contribute more only
when there are few eligible contributions. It cannot promise full pages when
content runs out or publication changes.

Snapshots and selected article references live only during batch construction;
retained batches still contain card metadata, and covers are prepared per page.

## Verification

`DiscoveryBatchSizeTests` pins allocation, tag filtering, exhaustion, continuation,
the total bound and withdrawal without shifting positions. The batch and initial-
selection records remain the owners of their independent decisions.
