---
name: archive-notes
description: Use when adding notes, auditing the notes directory, archiving, or pruning. The four-state rules live in the "Decision records" section of AGENTS.md; this skill covers only the workflow.
---

# Maintaining Decision Records

This skill is guidance, not a checklist. Read the lifecycle rules in [AGENTS.md](../../../AGENTS.md) before acting; this file owns the workflow, not the policy.

## Write a usable record

A decision record stands without the conversation that produced it. It names the problem; the proposal or shipped decision; alternatives actually considered and why they lost; and acceptance criteria plus risks for proposals, or consequences plus verification for implemented decisions.

The directory carries status. A proposal that still needs implementation starts in `proposed/`; a settled decision needing no implementation may start in `implemented/`. Moving a proposal to `implemented/` rewrites plans and acceptance criteria into present-tense shipped behavior, consequences, and evidence.

## Audit when adding a note

Every new note triggers a scoped search for active notes covering the same decision, mechanism, or rejected alternative. Classify every match while writing the new note:

- retain an independently useful implemented decision and cross-link it;
- keep a partially superseded note and update only paths, names, or facts that remain part of the same decision;
- never rewrite an old note into its opposite — a reversal lives in the new note;
- move an obsolete proposal to `rejected/` with an honest reason;
- delete a rejected note only when its losing idea is no longer plausible or tempting;
- archive a fully shipped implemented note only when its rationale no longer guides future work.

Do not defer a known match to a future corpus cleanup.

## Archive test

Ask whether the note's alternatives, ownership boundaries, negative guarantees, durability or wire semantics, security rules, or reintroduction conditions may guide a future change. If yes, it stays active regardless of age or length.

Proposed notes are never archived: reject an abandoned proposal. Rejected notes are guardrails, not history storage; delete them when they no longer prevent a meaningful mistake.

## No editing on the way out

Archiving is a move plus `Archived: YYYY-MM-DD` immediately below the note's date or status metadata, nothing else. Move every language counterpart when one exists. If the body needs correction, it is not ready to archive.

Before the move, search active prose for inbound links. Redirect them to current authority, retain an archived link only when the historical snapshot is intentionally cited, or remove it. Do not repair outbound links inside an archived note.

After the move, archived content is frozen: no edits, translation, reformatting, movement, or deletion. It remains historical evidence, never current authority.

## Validate and report

Check directory/status agreement, required sections, same-topic links, inbound links, counterparts when present, and `git diff --check`. Use repository validators when they exist; until then, report that these invariants were inspected manually rather than claiming a machine gate.

Report active implemented notes kept, notes archived, proposals rejected, rejected notes deleted, and genuinely borderline retention decisions.
