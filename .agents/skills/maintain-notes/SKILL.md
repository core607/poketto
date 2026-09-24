---
name: maintain-notes
description: Use when writing, implementing, auditing, deleting, consolidating, or archiving decision records under notes/, or when the note budget in repoCheck fails.
---

# Maintaining Decision Records

This skill is guidance, not a checklist. [AGENTS.md](../../../AGENTS.md) owns the lifecycle rules; this skill owns the classification judgment. Judge each note by what it still guides: word count and age help find candidates but never decide them, and no count of notes is a target.

## Before writing a note

Apply the creation rule in AGENTS.md: a note holds lasting rationale that code, tests, and current docs do not explain. Search active notes for the same decision, mechanism, or rejected alternative. When one owns the decision, update it instead of adding a second note.

A note opens with `## Problem`, stated without the solution. `## Decision` describes shipped reality in the present tense; a proposal has `## Proposal` and acceptance criteria instead. `## Alternatives` records each real alternative and why it lost; alternatives are recorded, never invented. `## Consequences` states what the choice cost and what it bought. Verification names the tests or checks that pin the decision; the tests already state their cases.

## Implementing a proposal

Decide in the implementing change whether the proposal still carries lasting rationale.

- **It does:** move it into implemented/ and rewrite plans, acceptance criteria, and future tense into what shipped, its consequences, and the tests that pin it.
- **It does not:** document the behavior in docs/ or the owning README, repair inbound links, and delete the proposal. A task brief that only described the work has served its purpose once the work ships.

## Classify each note in scope

A new note audits the notes it touches; a failed note budget or an explicit request audits the whole active tree. Classify each note as one of these outcomes.

- **Implemented, keep:** its alternatives, ownership boundary, negative guarantee, durable or wire semantics, security rule, or reintroduction condition is likely to guide a future change. Length does not matter.
- **Implemented, delete:** it records only a local UI adjustment, a mechanical change, or a feature description that docs/ now owns. A bug fix, performance change, or behavior decision does not qualify merely because its implementation was small.
- **Implemented, consolidate:** a current owner fully supersedes it. The owner absorbs every unique rationale, alternative, consequence, verification, and named gap; inbound links move to the owner; then the old note is deleted. When a feature is gone from code, configuration, schema, durable and wire formats, and docs, its addition note folds into the removal note, which keeps the original motivation, why it stopped justifying itself, and the condition for reintroducing it.
- **Partial supersession:** keep both notes, cross-link them, and update the facts that remain current. Removing one transport, default, or implementation while durable data or other paths survive is partial.
- **Implemented, archive:** the decision is complete and unlikely to guide future work, but its rationale is worth keeping as history. Redirect inbound links first, then move it with the `Archived:` line; a note whose body still needs correction is not ready.
- **Proposed:** never archived. A proposal no longer worth pursuing moves to rejected/ with an honest reason.
- **Rejected, keep:** the losing idea is still tempting and the note explains why it loses.
- **Rejected, delete:** the idea is obsolete, superseded, or no longer plausible. Repair or remove inbound links.

Git history keeps deleted text, but a consolidation must not rely on it: the owner carries the rationale forward.

## Calibrated examples

- [Public author names](../../../notes/implemented/2026-09-14-public-author-names.md), a few hundred words, is kept. It reads like card presentation but owns a privacy rule: only `public_author` or the workspace signature is displayed, and a login, email, or Git author never is.
- [Album entrances and lightbox](../../../notes/implemented/2026-09-14-album-entrances-and-lightbox.md), equally short, is kept. Its lightbox paragraph is local UI, but it owns the rule that `index.md` shadows `README.md` at a folder route.
- [Agent rule surface](../../../notes/implemented/2026-09-17-agent-rule-surface.md) is partially superseded by [note lifecycle and document budgets](../../../notes/implemented/2026-09-25-note-lifecycle-and-document-budgets.md): its deferred archive alternative is now decided, while its skill-surface decision stands. Both stay, cross-linked.
- [Consumer accounts and personal workspaces](../../../notes/rejected/2026-09-01-consumer-accounts-and-personal-workspaces.md) is kept as a rejection. Explicit space creation shipped instead, and the note still records why creating a repository automatically at registration loses.

Add an example here when an audit settles a case that these do not already calibrate.

## Validate and report

Run `./gradlew repoCheck`: it resolves every link, including links into moved or deleted notes, and enforces the document budgets. Report the notes kept, deleted, consolidated, archived, and rejected, each borderline case with its outcome, and any ceiling the change lowered or raised.
