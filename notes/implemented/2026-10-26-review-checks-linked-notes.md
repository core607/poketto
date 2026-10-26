# Review Checks Linked Notes

Date: 2026-10-26

## Problem

Implemented notes restate current facts that code and READMEs also hold, such as protocol markers, file paths and runtime names. Nothing made a note change with the code. The same-topic audit runs only when a change adds a note and finds notes by topic, not by the mechanism a change replaces. The AI review reports nothing about documents ([core review sessions](2026-09-10-review-scope-and-sessions.md)). Between 2026-10-19 and 2026-10-21 the CodeAct protocol marker, the sandbox launcher and the supervisor implementation changed, while six notes kept describing `codeActProtocol: 1`, the SRT launcher and `worker.py` as the confinement. An agent that read them without other context reported the old design as current.

## Decision

Notes record why; code and the owning README hold current values, and notes link versions, limits and paths to that owner instead of restating them. A change that makes a note statement false corrects it in the same change, whether or not it adds a note, and a reader confirms a note's mechanism in the code or README before relying on it ([AGENTS.md](../../AGENTS.md)).

For a pull request with a core change, [review.py](../../.github/review/review.py) collects every paragraph of an implemented or proposed note that links to a file the pull request changes. Notes the pull request edits are skipped. The paragraphs travel with each review request as untrusted data, bounded at 24 KB; notes beyond the bound are counted, not truncated. If the notes cannot be read, the review runs without them and the manifest records that. The [prompt](../../.github/review/prompt.md) asks for an N item when the change makes a quoted statement false, with the statement, the current fact and its source. N items are neither blockers nor suggestions, re-review checks them like blockers, and the author corrects the note or replies that it still holds ([pre-push-checks](../../.agents/skills/pre-push-checks/SKILL.md)). Documentation-only pushes stay exempt, and no other documentation finding is reported.

The review call budget becomes `min(30, 12 + 2 * changed_files)`. Of the 35 complete reviews retained from 2026-10-19 to 2026-10-23, none stopped for lack of calls, but four off-peak reviews of three to nine files used every call, while reviews of 12 to 38 files did not; the note check adds reads. The peak limit stays at three calls; all twelve peak reviews, including two of 28 and 29 files, completed.

A partially superseded note carries a `Partially superseded by` line under its date ([maintain-notes](../../.agents/skills/maintain-notes/SKILL.md)).

## Alternatives

**A list of retired terms in `repoCheck`.** It would have caught "SRT", but it fails when a later mechanism reuses a name, and someone must remember to register each term.

**A mechanical gate on linked files.** Over the 40 changes merged before 2026-10-26, failing every note that links a changed file would have flagged a median of 7 and up to 24 notes per change, 53% of them only because `executor-service/README.md` changed. A gate that loud gets acknowledged without being read; the review reads the paragraphs and judges them.

**Review every changed document.** It reverses the exemption of documentation-only pushes, which exists to save model calls.

**Move every current fact out of notes.** Notes would then never drift, but the facts need the contributor-facing home that [documentation layers](../proposed/2026-10-26-documentation-layers.md) proposes; until then this check covers them.

## Consequences

Each review request grows by at most 24 KB, and a small pull request may spend up to four more calls. A note that names a mechanism without linking its file stays invisible to the check, so linking instead of restating is what lets the check reach it. The review still judges, so a stale statement can pass unreported.

## Verification

`LinkedNoteTests` in [test_review.py](../../.github/review/test_review.py) pins collection, exclusion, the bound, the request content and the audit's `note` severity; the scaled-budget test pins the new formula.
