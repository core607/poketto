---
name: trim-cot-leakage
description: Use when auditing or fixing prose that only someone present in the authoring session could understand. Symptoms include "(decision 3)", "removed in this round", "rejected in review", "the previous version used to". Common in the output of every model family.
---

# Trim Chain-of-Thought Leakage

Chain-of-thought leakage is prose whose vantage is the authoring session rather than the repository. It cites artifacts only that session could see, narrates a change instead of the current state, or argues with a reviewer who has left.

Use [prose-standard](../prose-standard/SKILL.md) before trimming. Deletion is not the goal: preserve every factual clause that still matters, restate it from the repository's vantage, and delete only the transcript around it.

## The resolvability test

Could a reader who sees only the current repository — no chat logs, no PR threads — resolve every reference and verify every claim?

- Yes: it is not session leakage. It may still be misplaced change narration in a README, current-state doc, Javadoc, or comment; route that history to a decision record or postmortem.
- No: restate the facts worth keeping from the repository's vantage, and delete the rest.

## Common forms and repairs

- **Dead session references:** "decision 3", "audit B2", "the earlier discussion", or an uncommitted plan section. Link the committed owner by path, or delete the citation and keep its factual clause.
- **PR and branch vantage:** "this PR adds", "a later change", "the previous commit". State the current mechanism; put deferred work in a named TODO or issue.
- **Change narration:** "used to", "no longer", "now", or "renamed". Current-state documents state current behavior. A useful regression fact becomes a present counterfactual: "without the guard, X fails".
- **Review choreography:** "rejected in review" or "changed per reviewer". Decision records may retain the alternative and rationale, never the reviewer or round.
- **Reviewer-addressed justification:** "this is safe because...". State the invariant that makes it safe, or delete the comment when code already proves it.
- **Control-flow and test walkthroughs:** "first...then...finally" or a prose replay of the test body. Delete the narration; retain only a non-obvious invariant or observation reason.
- **Hedges and ownerless plans:** "probably fine for now" or "should be enough". Replace with the real bound and failure behavior, promote concrete deferred work to a named TODO, or delete it.
- **Language slips:** working-language fragments or private separators inside otherwise English prose. Translate or remove them.

## Sanctioned keeps

Do not delete evidence merely because it mentions history. Keep resolvable issue references; required suppression and empty-catch explanations; measured bounds with their provenance; runtime old/new states; present-tense counterfactual regression pins; external standards; and alternatives inside a decision record.

Do not overcorrect. Trimming must not turn an obligation into an endorsement, a hypothetical into a shipped feature, a measured value into an unexplained constant, or a sentence containing one narrated clause into deletion of its other true clauses.

## Workflow

1. Confirm the requested scope. Exclude `notes/archived/`, generated artifacts, fixtures, and snapshots unless the task names an exact target.
2. Search with `rg --hidden`, including `.agents/`; exclude `.git/`, archived notes, and this skill's own quoted examples.
3. Probe for session ordinals, "this PR/branch", "used to/no longer/now", review vocabulary, hedges, and unresolvable section references. Every hit needs semantic judgment; a zero-hit search does not replace reading dense prose.
4. Enumerate the factual clauses before editing. Update the authoritative source before a copied or generated derivative.
5. Re-run the searches, resolve every remaining citation, and run the checks for the touched document surface.

Reversals and their reasons belong in decision records. Current-state documents state current behavior. Postmortems are the only home for incident chronology.
