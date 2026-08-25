---
name: doc-standards
description: Use when writing, moving, or auditing documents, or when asked to "trim the docs", "review the docs", "where does this belong", or when a document runs too long. For prose judgment see prose-standard.
---

# Document Placement and Audit

This skill is guidance, not a box-ticking checklist.

Use [prose-standard](../prose-standard/SKILL.md) for required factual coverage and editorial judgment. Decision records follow the lifecycle rules in [AGENTS.md](../../../AGENTS.md); archived records are outside routine audits.

## Placement routing

- Rules → AGENTS.md (one to three lines, rationale behind a link)
- Decision rationale and rejected alternatives → notes/
- Reusable workflows → .agents/skills/
- Current-state description → docs/ or the relevant README (created after repo creation)
- Incident accounts → postmortem (enabled when created; the only tier where narrative belongs)
- Runbooks tied to a specific machine or environment → never into this repository; they live in the operator's private storage

Each fact has exactly one home; everywhere else links to it. When unsure, ask: what question brings a reader here?

## Set structure before wording

1. Name the document's own subject and its direct children.
2. Keep full detail about that subject. Describe direct children only by purpose, responsibility, and high-level behavior; link to the child for lower-level detail.
3. Classify the document by use. A tutorial leads through ordered work to an observable outcome; a reference supports lookup within a defined scope. Split substantial mixed forms.
4. For a tutorial, establish prerequisites before dependent concepts and move optional advanced material to a later tutorial or reference.

Generated documents are edited through their source or generator. A human-maintained inventory is a defect when code or a generator can produce it.

## Audit checklist

1. The same rule stated in more than one place.
2. History narration: "previously", "no longer", "changed in this pass". Current-state documents state the current state; change history belongs in notes.
3. Status annotations: "implemented!", "future:". Status rots; let the directory structure and the code speak.
4. Hand-copied inventories of what code or a generator can produce.
5. Reasoning transcripts: implementation play-by-play, proofs of obvious branches, rejected local alternatives.
6. Rationale repeated across sibling entries instead of stated once at its home.
7. Paragraph walls: one paragraph carrying several rules plus parenthetical asides. Split, or demote the detail.
8. Emphasis inflation: bold everywhere means nothing stands out. Reserve bold for the clause that changes behavior.
9. Personal information: author identity beyond the public account, local filesystem paths, machine and environment details, personal motivation, names of people around the author. This repository is public; scan every document for these before it lands.

## Move or rename a document

Search for inbound links before moving it. Apply the move, counterpart move when one exists, and every inbound-link repair atomically. Do not leave redirects or compatibility copies during the pre-release phase unless an external published URL requires one.

Verify repository-relative paths and fragments against the resulting tree. Do not repair outbound links inside frozen archived notes.

## When a document runs long

Relocate first: move content to its owner and leave a linking line when readers still need the route. Condense only after placement is correct. Accept extra length when the document's own subject genuinely needs it; no numeric ceilings exist yet.

## Validate and report

For any document change, run the available repository link, pairing, formatting, and generated-freshness checks plus `git diff --check`. If a required check does not exist yet, state that limitation; do not invent a passing substitute.

Report documents moved, authoritative homes chosen, deliberate long-form keeps, paired counterparts changed, and the exact checks run.
