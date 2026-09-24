---
name: prose-standard
description: Use for substantive repository prose writing, rewriting, or audits, including contracts in comments. Typo and mechanical edits follow the baseline in AGENTS.md.
---

# Prose Standard

Write enough to preserve the reader's contract, then remove repetition, narration, and decoration. The acceptance test is not merely that the text is short: a reader should understand every obligation and consequence in one pass.

This skill owns editorial judgment. [AGENTS.md](../../../AGENTS.md) owns where content belongs.

## Preserve the complete proposition

Before rewriting, identify every factual clause. Preserve each relevant actor and action; condition, timing, and order; must/may/never modality; ownership and side effect; failure mode, negative guarantee, exception, and consequence.

Shorter prose is worse when it drops one of those facts. Remove decoration and repeated rationale, not behavior that a caller or maintainer relies on. Keep a concise local contract at the point of use; link architecture, alternatives, history, algorithms, and long examples to their authoritative home.

## Required coverage by location

Add prose when code and structure do not communicate a required fact. Do not write merely to fill a section.

- **README and current-state docs:** configuration, behavior, failures, limits, extension points, and operating facts.
- **Public API and Javadoc:** non-obvious parameters, return distinctions, exceptions, side effects, ownership, timing, cancellation, and durability.
- **Internal comments:** non-local invariants, race ordering, resource ownership, security rules, and surprising failure behavior. Delete control-flow narration and code restatement.
- **Tests:** explain only why a fixture, real entry path, indirect observation, or platform accommodation is necessary. The test body already shows its steps.
- **Decision records:** rationale, alternatives, consequences, current mechanism, the tests or checks that pin the decision, and named gaps. Implemented notes describe shipped reality in the present tense without restating test cases or plans.
- **Prompts, diagnostics, and visible strings:** wording is behavior. Name the failing subject, violated rule, and correction when it is not obvious.
- **Skills and agent rules:** name the real failure each rule prevents. A rule that cannot name one is deleted, not reworded.

## Style and ownership

- Prefer short sentences and one main idea per sentence, but keep clauses together when splitting would hide their relationship.
- Use direct technical terms. Do not use metaphors or courtroom language such as "verdict", "filed", or "case closed".
- Separate facts from judgments. Facts must be verifiable; judgments name whose judgment they are.
- Use emphasis only for the clause that changes behavior; at most one bold phrase per section.
- Do not restate facts obvious from adjacent code, tables, or configuration.
- Each fact has one authoritative home. Other surfaces retain their necessary local contract and link to that home for detail.
- Every repository citation must resolve. Never cite a chat, uncommitted plan, review round, or private machine path as project authority.
- Current-state documents state current behavior. Route change narration such as "used to", "no longer", or "this PR adds" to the commit message or pull request, or to the decision record that owns a reversal, keeping a regression fact as a present counterfactual: without the guard, X fails.
- When an English/Chinese pair exists, update both sides in the same change. Make the smallest counterpart edit that preserves reviewed text outside the changed passage.

## Workflow

Read the owning code, requirement, or decision needed to establish the affected claims. Make only changes authorized by the task; a review-only request reports findings without editing. Update the authoritative source before generated or copied derivatives.

Read the result independently of the old text. Every relevant obligation, exception, failure, and reference must remain complete; classification of each passage is useful for a broad audit, not required for a local edit.

When two versions both preserve the complete proposition, prefer clearer ownership and fewer repeated facts. Do not manufacture edits merely to reduce a word count.
