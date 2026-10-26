# Documentation Layers

Date: 2026-10-26

## Problem

Poketto has public documents and decision records, but no contributor document for the current system between them. The README, the [usage reference](../../docs/usage.md) and their Chinese pairs serve users and operators. Component READMEs such as the [worker reference](../../executor-service/README.md) cover a few components. The application modules under `src/main/java/io/github/core607/poketto/` (auth, content, workspace, spaces, plaza, qa, games, executor, mcp and others) have no current-state page, and no document maps how they fit together.

Notes fill the gap. Active notes hold about 100,000 words, against about 22,000 in docs/ and the READMEs together. AGENTS.md routes product behavior to the [requirements note](../implemented/2026-08-25-requirements-and-architecture.md), which one agent session read 51 times ([note lifecycle](../implemented/2026-09-25-note-lifecycle-and-document-budgets.md)). Notes written in the present tense therefore act as the current-state reference, and their facts drift when code changes ([review checks linked notes](../implemented/2026-10-26-review-checks-linked-notes.md)). Review rules are split between the Poketto invariants in the [review skill](../../.agents/skills/review/SKILL.md) and the notes they cite. One of more than a hundred implemented notes is archived, because a note that holds current facts never stops guiding work.

[deepseek-harness](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/AGENTS.md), whose note lifecycle Poketto adapted, separates an architecture map, per-subsystem references, generated catalogs checked against source, review-enforced rules in `docs/defensive-patterns/`, and Agent Notes for rationale. It keeps about 320 implemented notes active and about 930 frozen in its archive.

## Proposal

Each layer has one reader and one job. Contributor documents are English-only, like notes.

| Layer | Reader | Holds | Does not hold |
|---|---|---|---|
| README, usage reference | Users and operators | Product use, configuration, operation | Internal mechanisms |
| `docs/architecture.md` | Contributors and agents | Modules, data ownership, trust boundaries, entrances, executor topology | Rationale, implementation status |
| `docs/modules/<module>.md` and component READMEs | Contributors and agents | Each module's current contract: responsibilities, durable state, wire and configuration values, limits | Rationale, history |
| `docs/review-rules/` | The review bot and authors | Rules enforced in review: what must hold, how it is judged, its boundary, a correct and a wrong example | Semantics owned by module pages |
| notes/ | Agents changing direction | Why, alternatives, consequences | Current values, which they link |

1. **Map.** Write `docs/architecture.md`, under a word ceiling, and route product behavior in AGENTS.md to it and to module pages; notes are read when a change revisits a decision.
2. **Review rules.** Move the Poketto invariants out of the review skill into `docs/review-rules/`, one page per artifact, with a table from changed paths to pages. [review.py](../../.github/review/review.py) sends the pages whose paths a pull request touches, alongside the linked notes.
3. **Generated facts.** The HELLO protocol markers in the worker reference are generated from the Rust supervisor, and a check fails when they differ. Other facts join this mechanism when one of them drifts.
4. **Incremental migration.** A change that alters a module moves that module's current facts from its notes into its module page in the same change, links the notes there, and applies the archive test from [maintain-notes](../../.agents/skills/maintain-notes/SKILL.md). No change migrates modules it does not touch.

## Acceptance criteria

- Step 1: `docs/architecture.md` exists within its budget, links every module page or component README, and AGENTS.md routes to it.
- Step 2: the review skill keeps workflow and evidence standards only; the review request carries the rule pages for the changed paths, and `.github/review` tests pin the selection.
- Step 3: a test fails when a HELLO marker in the worker reference differs from the supervisor source.
- Step 4: each module page created by a later change lists the notes it replaces as the owner of current facts; the note budget falls as notes archive.

## Alternatives

- **Keep notes as the current-state reference and rely on the review check.** It needs no new documents, but the review sees only notes that link changed files, and present-tense notes stay the place agents look first.
- **Freeze every note now and rewrite current facts in one pass.** The [note lifecycle](../implemented/2026-09-25-note-lifecycle-and-document-budgets.md) record rejected a one-pass rewrite of the corpus because one judgment would decide what later readers need from all of it; migrating with each change keeps each judgment local.
- **Adopt deepseek-harness's full structure.** Its TypeScript type-equivalence checks, bilingual contributor documents and cookbook serve a corpus several times larger; Poketto takes the layers without that machinery.
- **Put current internals in the public README.** It mixes contributor detail into the document users read, and the usage reference is already near its ceiling.

## Consequences

Standing documents grow and need ceilings, and module pages must change with their code, which the review check covers through links. Until a module is migrated, its notes stay the reference for it. Rules move out of the review skill, so the review's trusted input changes and open review sessions restart once.
