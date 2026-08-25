---
name: ui-evidence
description: Use when a change touches user-visible interface (pages, styles, interaction) and a PR is being prepared.
---

# Evidence for UI Changes

A PR that changes user-visible interface must attach evidence produced by a **real run of the exact changed tree**. Evidence demonstrates the user claim; it is not decoration and does not replace behavior tests.

## Stage the run

1. Require a clean worktree and record the exact commit SHA or, before commit, the exact diff state being demonstrated.
2. Build and start the real application through its shipped entry path. Use fresh application, browser, and test data state so an earlier run cannot create the result.
3. Use real downstream components for the behavior being claimed. Mock only an external or nondeterministic boundary when the PR explicitly claims that mocked mode, and state the limitation.
4. If the real server, required credential, or browser control is unavailable, report the blocker. Do not substitute a static mockup or unrelated old run.

## Choose the smallest truthful artifact

- Use a screenshot for a stable visual state or layout comparison.
- Use a short recording when interaction, timing, focus, transition, error recovery, or multiple states are the claim.
- Capture only states needed to tell one story. Keep viewport, data, and environment consistent across the artifact.
- One isolated run produces one evidence artifact. Do not splice separate runs, use stale images, assemble collages, or stage fake product data merely to make the result look complete.

Wait for a concrete UI condition before capture: a unique label, enabled action, completed response, stable URL, or exact visible result. A fixed delay alone does not prove the state was reached.

## Protect data and provenance

Capture no credentials, private notes, personal data, unrelated tabs, local machine paths, or transient notifications. Use benign demonstration content and inspect the final artifact itself for leaks.

Record beside the artifact:

- demonstrated commit or diff state;
- command and mode used to start the app;
- real, fixture, or mocked dependencies;
- browser and relevant viewport;
- exact flow and result shown;
- known limitation of what the artifact does not prove.

## Verify and attach

Open the final screenshot or recording and inspect legibility, ordering, final state, and sensitive content. Confirm the artifact lives outside the product branch's permanent history unless the repository establishes an approved assets workflow.

Attach it to the PR, then re-read the live PR head. Re-record if the head changed in a way that could affect the demonstrated behavior. Report the artifact path or URL and provenance together.
