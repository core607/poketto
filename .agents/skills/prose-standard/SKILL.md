---
name: prose-standard
description: Use before writing or editing any document in Poketto (notes, README, comments, commit message bodies). Defines the prose baseline and the acceptance test.
---

# Prose Standard

Write enough to preserve the reader's contract, then remove repetition, narration, and decoration. The acceptance test is not merely that the text is short: a reader should understand every obligation and consequence in one pass.

This skill owns editorial judgment. Use [doc-standards](../doc-standards/SKILL.md) for placement and structure, and [trim-cot-leakage](../trim-cot-leakage/SKILL.md) for prose tied to an authoring session.

## Preserve the complete proposition

Before rewriting, identify every factual clause. Preserve each relevant actor and action; condition, timing, and order; must/may/never modality; ownership and side effect; failure mode, negative guarantee, exception, and consequence.

Shorter prose is worse when it drops one of those facts. Remove decoration and repeated rationale, not behavior that a caller or maintainer relies on. Keep a concise local contract at the point of use; link architecture, alternatives, history, algorithms, and long examples to their authoritative home.

## Required coverage by location

Add prose when code and structure do not communicate a required fact. Do not write merely to fill a section.

- **README and current-state docs:** configuration, behavior, failures, limits, extension points, and operating facts.
- **Public API and Javadoc:** non-obvious parameters, return distinctions, exceptions, side effects, ownership, timing, cancellation, and durability.
- **Internal comments:** non-local invariants, race ordering, resource ownership, security rules, and surprising failure behavior. Delete control-flow narration and code restatement.
- **Tests:** explain only why a fixture, real entry path, indirect observation, or platform accommodation is necessary. The test body already shows its steps.
- **Decision records:** rationale, alternatives, consequences, current mechanism, verification evidence, and named gaps. Implemented notes describe shipped reality in the present tense.
- **Prompts, diagnostics, and visible strings:** wording is behavior. Name the failing subject, violated rule, and correction when it is not obvious.
- **Skills and agent rules:** preserve behavioral guardrails, authorization boundaries, stopping conditions, and the distinction between guidance and a fixed script.

## Style and ownership

- Prefer short sentences and one main idea per sentence, but keep clauses together when splitting would hide their relationship.
- Use direct technical terms. Do not use metaphors or courtroom language such as "verdict", "filed", or "case closed".
- Separate facts from judgments. Facts must be verifiable; judgments name whose judgment they are.
- Use emphasis only for the clause that changes behavior; at most one bold phrase per section.
- Do not restate facts obvious from adjacent code, tables, or configuration.
- Each fact has one authoritative home. Other surfaces retain their necessary local contract and link to that home for detail.
- Every repository citation must resolve. Never cite a chat, uncommitted plan, review round, or private machine path as project authority.
- When an English/Chinese pair exists, update both sides in the same change. Make the smallest counterpart edit that preserves reviewed text outside the changed passage.

## Workflow

1. Confirm the requested scope and applicable [AGENTS.md](../../../AGENTS.md) files.
2. Read the owning code, requirement, or decision before judging its prose.
3. Classify each passage as keep, add, trim, restructure, relocate, or defer.
4. Make only changes authorized by the task; review requests report findings without editing.
5. Update the authoritative source before generated or copied derivatives.
6. Re-read the result without the old text. Confirm that every obligation, exception, failure, and reference remains complete.

When two versions both preserve the complete proposition, prefer clearer ownership and fewer repeated facts. Do not manufacture edits merely to reduce a word count.
