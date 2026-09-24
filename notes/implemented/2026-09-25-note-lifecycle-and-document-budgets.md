# Note lifecycle and document budgets

Date: 2026-09-25

## Problem

On 2026-09-25 notes/ held 92 implemented notes, 2 proposals, 5 rejected notes and no archived note: about 122,000 words and three quarters of the repository's Markdown by size. Standing documents had grown as well: `docs/usage.md` reached about 10,000 words and `executor-service/README.md` about 6,500.

Three paths added notes and none removed them.

- **Creation.** AGENTS.md asked for a note whenever "a non-trivial product, architecture, process, or data-format decision has rationale or trade-offs a future maintainer may revisit". Nearly every feature matched.
- **Proposals.** Agreed work starts as a proposed note, and implementing it moved the note into implemented/. Every shipped task therefore left a permanent record, including tasks whose only content was what the feature does.
- **Review.** The review skill required that "a non-trivial change adds or updates the owning decision record", and the AI Review workflow loads that skill from `main`.

An implemented note could only stay active or be archived. The archive test, whether a note's rationale may guide a future change, is never failed while its subsystem lives, as the [agent rules record](2026-09-17-agent-rules-for-an-unreviewed-repository.md) found when it deferred the question. A fully superseded note had no way out, and a reversal added one more note. Many implemented notes also restate their tests case by case under Verification. Nothing measured document size; Java already has a 600-line file limit.

## Decision

**Creation.** A note is written or updated only for lasting rationale that code, tests and current docs do not explain: a real alternative, a trade-off, or an ownership, security, durability or reintroduction rule. What a feature does belongs in docs/ or its README. Local UI and mechanical changes need no note, and updating the note that already owns a decision satisfies the rule.

**Proposals.** Agreed work still lands as a proposed note, which serves as the implementer's brief. The implementing change either moves it into implemented/ rewritten as what shipped, or deletes it when nothing in it outlasts the work, after documenting the behavior in its current-state home.

**Removal.** The [maintain-notes](../../.agents/skills/maintain-notes/SKILL.md) skill classifies each note as kept, deleted, consolidated, archived or rejected. The classes are adapted from the note rules of [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness/blob/master/.agents/skills/dsh-archive-agent-notes/SKILL.md):

- A note that records only a local UI adjustment, a mechanical change or a feature description is deleted.
- A fully superseded note is consolidated: its current owner absorbs every unique rationale, alternative, consequence and named gap before the old note is deleted.
- A feature removed everywhere folds into its removal note.
- A complete decision unlikely to guide future work is archived.

The skill carries calibrated examples from this repository, and every new note triggers the audit in the same change.

**Content.** Verification names the tests or checks that pin a decision; it does not restate their cases. AGENTS.md now lists what each documentation home does not hold, and the review skill treats a note written for a local UI or mechanical change, or a proposal kept after implementation without lasting rationale, as a finding.

**Budgets.** [config/document-budgets.properties](../../config/document-budgets.properties) sets word ceilings for the standing documents, the skills, and the active notes as a whole; archived notes are outside it. `repoCheck` counts words as runs of non-space characters, each Han character counting as one, and fails when a ceiling is exceeded or names a missing path. A failure is answered in this order:

1. Move content to its home.
2. Condense it.
3. For notes, run the audit.

A ceiling is raised only when the words are needed, with the reason in the pull request. The first ceilings sit about 5% above each document's size on this date, so nothing grows while the existing notes and documents are reduced; each reduction lowers its ceiling in the same change.

## Alternatives

**Merge the corpus into about twenty topic notes.** One rewrite would shrink the corpus fastest. It would also decide, in a single pass, what later readers need from 900,000 characters, the misjudgment that the [agent rules record](2026-09-17-agent-rules-for-an-unreviewed-repository.md) rejected for compression into a rule list. Per-note classification keeps each surviving decision's own alternatives and merges only when one note fully supersedes another.

**Adopt the deepseek-harness archive whole.** Its archive seals every archived note with a hash manifest, splits notes into class folders and pairs each with a translation and metadata file. That machinery protects a corpus of over a thousand notes written by many agents; here it would add a verifier for about a hundred files. The classification and consolidation rules are adopted without the seal.

**Treat Git history as the archive and delete freely.** Deleting would shrink the tree immediately, but rationale that still guides a change would survive only in history, where the next agent does not look. Deletion is reserved for notes without lasting rationale, and consolidation must carry rationale forward.

**Rules without a budget.** The same-topic audit and the archive rule already existed and never removed a note. The budget makes growth visible in `repoCheck`, which CI runs on every pull request, including documentation-only ones.

**A ceiling per note instead of a total.** It would bound each note's length but not how many notes accumulate.

## Consequences

Fewer notes are written, and what a feature does moves into the usage documents that readers already consult. A failing budget forces an audit or a visible, justified raise instead of silent growth.

The cost is judgment at two points that used to be automatic: whether a change deserves a note, and whether an implemented proposal still holds lasting rationale. A deleted proposal's text remains in Git history and its pull request. The word count differs from `wc -w` on Chinese text, so ceilings are meaningful only against `repoCheck`'s own count.

## Verification

`repoCheck` enforces the ceilings and the skill inventory; a ceiling set below its document's size fails the check with the counted words.
