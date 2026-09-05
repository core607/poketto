---
name: review
description: Use when reviewing a PR or asked to "look over this change". Applies to any reviewer, including agents from other vendors.
---

# Reviewing a Poketto Change

This skill is guidance, not a complete checklist. Review is a reporting task: **output findings only; do not fix things as you go**. Fixing is a separate task that must be explicitly requested.

## Establish the review scope

1. Verify the live base and exact head. Fetch them when necessary; do not trust an old branch name or PR summary.
2. Inspect committed, staged, unstaged, and untracked paths that belong to the requested change.
3. Read the diff and enough owning code, requirements, configuration, tests, and documentation to understand both sides of every changed interface.
4. Read [AGENTS.md](../../../AGENTS.md), the [requirements note](../../../notes/implemented/2026-08-25-requirements-and-architecture.md), applicable subtree rules, and every decision record the change claims to implement or supersede.

## Priorities

Correctness, lifecycle (are resources released), security, and broken required behavior come before style.
One substantiated blocking finding beats a list of nitpicks. If nothing substantive is wrong, say so; do not pad.

## Required checks

1. **Intent and decision:** the change stays inside the requested scope and settled requirements. A non-trivial change adds or updates the owning decision record, and the implementation matches that record rather than only its title.
2. **Correctness and interfaces:** trace producers and consumers, success and failure paths, empty and boundary values, cancellation, retry, idempotency, and cleanup. Defaults and public choices need current-consumer evidence.
3. **Authority and state:** identify the authoritative source for every retained value. Derived projections, caches, events, UI echoes, and acknowledgments update only after the owning operation's commit point.
4. **Concurrency and lifecycle:** check publication-before-ready races, cancellation during waits, callback containment, ownership transfer, rollback, complete detach, and disposal-to-quiescence where applicable.
5. **Security:** trace enforcement to the operation that executes it, including alternate callers that bypass schemas or wrappers. For affected Poketto paths, verify capability isolation, public/private search separation, SSRF redirect and DNS handling, Markdown sanitization/CSP, budget reservation and settlement, and secret handling against the requirements note.
6. **Bounds:** apply byte, token, item, and time limits to the complete emitted or retained result, including wrappers and metadata. Check tiny, exact, oversized-single-item, and multibyte cases.
7. **Evidence:** tests or replayable evidence must exercise the real entry path and observe external state, durable data, rendered output, or emitted events — not trust the agent's own report. An invalid case should fail for the intended rule.
8. **Documentation:** affected README, Javadoc, user docs, decision records, and bilingual public pairs update with the behavior. Generated artifacts change through their owner.

## Poketto invariants to trace

When the diff touches them, require direct evidence for these requirements rather than treating them as background prose:

- Remote `main` remains content authority. Public reads use one verified, unexpired snapshot; missing or disabled policy exposes nothing, and invalid policy closes service rather than retaining older authorization. PostgreSQL holds relational application state, not a content projection.
- Browser and MCP writes share base-commit and per-path revision/absence checks. Atomic batches preserve untouched source; conflicts do not overwrite, ambiguous acknowledgments require reconciliation, and Git acknowledgement is distinct from snapshot installation.
- Public search fixes its scope internally. Private reads and writes require current workspace authorization; changes to public content, references, or publishing policy also require `PUBLISH`.
- Image authorization binds the page snapshot and exact immutable version. Withdrawal stops new public grants; issued grants remain bounded by five minutes and snapshot expiry. Private reads recheck identity, uploads do not publish or write Git, and acknowledged managed originals survive cache cleanup.
- Execution copies are isolated by principal, workspace, and server-issued MCP session. Authoritative reads never use command-modified copies; cancellation, revocation, expiry, and shutdown terminate descendants. Missing sandbox capabilities never permit an ordinary subprocess fallback.
- The [phase-one record](../../../notes/proposed/2026-09-05-phase-one-daily-use.md) owns acceptance and exclusions. Do not require deferred backups or visitor Q&A as a release prerequisite, or equate protocol probes and synthetic fixtures with real browser, client, and HTTPS acceptance.

## Reporting findings

For each finding, state the defect, tightest location, impact, and evidence. Separate blockers from suggestions. Do not report an issue already guaranteed by a green, relevant gate unless the gate itself is incomplete or bypassed.

If no substantive finding remains, say so and name residual risks or checks not run. Never convert a review into an edit without new authorization.
