---
name: translate-docs
description: Use when maintaining English/Chinese document pairs (foo.md and foo.zh.md). Run the full workflow only when the user invokes this skill by name; routine edits follow one rule — a minimal counterpart update in the same change.
---

# Bilingual Document Maintenance

## Invocation boundary

Run this full workflow only when the user invokes `translate-docs` by name. Routine edits to any existing pair follow one rule without loading the extended workflow: **when one side changes, make the smallest corresponding change to the other side in the same change**.

Agent instructions and project skills are English-only and outside this workflow.

## Classify the work

- **Existing pair, one side changed:** make a minimal counterpart update.
- **New pair:** translate the whole document and verify it as a unit.
- **Both sides changed independently:** reconcile meaning deliberately; neither side wins by filename.
- **Rename or deletion:** apply the same operation to the counterpart and repair inbound links atomically.

Both languages carry equal authority. For a single update, the edited side is the source for that update; it is not a permanent master language.

## Fidelity rules

Preserve every actor, condition, modality, exception, failure, guarantee, and consequence. Preserve heading depth, section order, list kind and item order, table structure, code spans, emphasis meaning, link target and fragment, and fenced-code semantics.

Code, commands, identifiers, paths, error codes, and schema field names remain verbatim unless the owning technical source localizes them. Terminology follows existing repository usage; do not invent a translation silently when no established term exists.

A translation should read as native technical prose on its own. Sentence-by-sentence correspondence is not required, but adding, dropping, or weakening a proposition is forbidden.

## Existing-pair update

1. Inspect the exact authored diff and identify the smallest aligned units: heading, paragraph, list item, or table row.
2. Update only those units in the counterpart. Never re-translate an entire document to apply a local edit; doing so discards reviewed wording outside the change.
3. Compare the changed units clause by clause, then read the counterpart alone for naturalness.
4. Check that unchanged structure, links, code, and terminology remain intact.

## New pair

Read existing public terminology and style first. Translate section by section while keeping structure aligned, then perform a separate clause-by-clause fidelity pass and a standalone readability pass. Write only final text to the file.

## Finish

Verify that both files exist, counterpart links point to the correct locale, repository-relative targets resolve, and the structural inventory matches. Run the repository pairing and Markdown checks when they exist plus `git diff --check`; until pairing automation lands, report the manual checks without calling them a gate.

State which pairs were new, minimally updated, reconciled, renamed, or deleted, and identify any unresolved terminology.
