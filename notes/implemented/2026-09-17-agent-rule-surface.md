# Agent rule surface

Date: 2026-09-17

## Problem

The agent rule surface had grown to nine skills totalling 455 lines plus an 83-line AGENTS.md, and much of it assumed a per-change approval step that the repository does not have. Branch protection on `main` requires no approving review, the CI matrix runs every job for any non-documentation change, and the AI Review workflow reads `AGENTS.md` and the review skill from `main` to post its findings. Much of the rule surface was nevertheless written for an approver who inspects each screenshot or grants permission for each step.

Three specific costs had accumulated.

**Per-action authorization contradicted how work is actually assigned.** AGENTS.md required explicit user authorization for each push and each pull request, and three skills repeated a variant of "do not act without authorization". Real tasks arrive as one instruction covering many pushes, such as the 2026-09-16 optimization series of eleven pull requests. Every skill then had to be argued past its own gate.

**The rule for keeping rules was a ratchet.** AGENTS.md told an editor to "preserve every rule that changes a decision, permission, stopping condition, or required evidence", and the prose standard repeated it. Any guardrail satisfies that description, so the surface could only grow.

**Guidance was duplicated and partly stale.** The same instruction to run `repoCheck` with `git diff --check` appeared in four skills, "this skill is guidance, not a checklist" in four, and the report-only rule in five places. Two skills still said to report manual inspection "until a validator exists" although `repoCheck` had validated links and bilingual pairs since 2026-08-26. AGENTS.md still said to restore platform enforcement before the repository became public, which had already happened. The pre-push table hand-copied the surface-to-command mapping that `.github/workflows/ci.yml` owns, which the document standard itself called a defect.

## Decision

Five skills are deleted: `ui-evidence`, `archive-notes`, `doc-standards`, `trim-cot-leakage`, and `translate-docs`. Four remain: `prose-standard`, `review`, `find-simplifications`, and `pre-push-checks`. On 2026-09-25, [note lifecycle and document budgets](2026-09-25-note-lifecycle-and-document-budgets.md) added a fifth, `maintain-notes`.

The rules that were load-bearing moved rather than disappearing. AGENTS.md absorbs the placement routing, including the rule that machine-specific runbooks never enter this repository; the archiving mechanics and the same-topic audit, both one line; and the resolvability rule, which is what makes a note readable by an agent that lacks the authoring conversation. The prose standard absorbs the ban on change narration in current-state documents. The bilingual rule survives as the single sentence it always was in practice: when one side changes, make the smallest corresponding change to the other side.

Two rule changes in AGENTS.md:

- Within an assigned task, pushing, opening the pull request, answering the review and driving it to merge need no per-step approval. Destructive actions and decisions the user must make still stop the loop.
- A skill rule is kept only while its editor can name the real failure it prevents, and is deleted otherwise. This is the same test already applied to rejected notes.

The same change added an arrival-guide sentence about who reads a change; [the commit a review belongs to](2026-09-17-reviewed-commit-status.md) removed it and records why.

`pre-push-checks` now points at the CI workflow for the authoritative command per surface and keeps only what that file cannot say: focused-test selection, the Windows storage replay, the native isolation probe, and that a user-visible change runs the real browser entrance and reports what it showed.

Screenshot evidence for user-visible changes is discontinued. The real run against the changed tree remains required; its result is stated in the pull request. The acceptance README no longer mandates screenshots alongside each browser scenario.

The four-state note lifecycle and the decision-record format are unchanged, deliberately. A note's value is that a future agent, without the conversation that produced it, can reconstruct why. Compressing notes into a rule list would destroy exactly that.

[Note lifecycle and document budgets](2026-09-25-note-lifecycle-and-document-budgets.md) later added ways for a note to leave the active tree without changing the record format.

## Alternatives

**Keep every skill and deduplicate the text.** Rejected: the duplication is a symptom. Four of the five deleted skills exist to serve a human reader or a per-action permission model, so editing them preserves the cost that produced them.

**Merge the deleted skills into the survivors instead of deleting them.** Partially taken. Only the rules with a namable failure moved. Folding whole documents in would have reproduced the ratchet inside a smaller number of files.

**Delete the notes format as well and keep a flat register of invariants.** Considered on 2026-09-17 and rejected by the user: an agent that believes a one-line rule will be enough later is wrong about its own future comprehension. The register idea survives as a possible addition, not a replacement.

**Relax the archive criterion so the corpus can shrink.** Deferred. The criterion asks only whether a note's rationale may guide a future change, never where that guidance is now authoritative, so nothing becomes archivable while its subsystem lives. Changing it is a real policy change and needs its own note; [note lifecycle and document budgets](2026-09-25-note-lifecycle-and-document-budgets.md) is that note.

## Consequences

The rule surface drops from nine skills to four. `repoCheck` enforces the skill inventory against `AGENTS.md`, so the table and the directory cannot drift; it now also requires the content-foundation bilingual pair, which existed but was ungoverned, and no longer checks the deleted invocation-policy file.

The AI Review workflow reads `AGENTS.md` and `.agents/skills/review/SKILL.md` from `main`. Its rule surface therefore changes when this merges: the review skill keeps its Poketto invariants and loses one duplicated report-only sentence.

The risk accepted is that a deleted rule was preventing a failure nobody named. The mitigation is that the deletions are recoverable from history and that the two real gates, the CI matrix and the AI reviewer, are unchanged by this record.
