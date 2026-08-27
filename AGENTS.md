# AGENTS.md

Poketto: a self-hosted personal knowledge base whose public face is a blog. Files are the source of truth, PostgreSQL is a derived projection, and trusted AI agents connect over MCP. The [requirements note](notes/implemented/2026-08-25-requirements-and-architecture.md) owns the product boundaries and architecture.

The public README and requirements note have .zh.md counterparts; edit both sides in the same change (see [translate-docs](.agents/skills/translate-docs/SKILL.md)). Agent instructions and skills are English-only.

## Arrival guide

Current phase: development. The executable baseline is established; product capabilities are implemented only from an assigned, settled task.

An agent arriving for the first time:

1. Read this file, then the requirements note. Read the [development baseline](notes/implemented/2026-08-26-development-baseline.md) when changing the build, module boundaries, database test image, or CI.
2. In your first reply, confirm your understanding of the project and current task before changing it. Do not infer a product task from the roadmap or implement ideas still under discussion.
3. Work on a short-lived branch. Keep product code inside the owning application module and add evidence that exercises the changed behavior.
4. Never push or create a pull request without the user's explicit authorization; never push directly to main.

## Phase clause (delete this section at the first release)

There are no external users during development: rename and refactor freely; write no compatibility shims. The database schema and the content-repo format may change destructively; rebuild instead of migrating.

## Commands

Use the Gradle Wrapper; on Windows replace `./gradlew` with `.\gradlew.bat`. Java 26 is required. `integrationTest` and `check` also require a working Docker-compatible daemon.

| Command | Purpose |
|---|---|
| `./gradlew bootRun` | Start the application locally |
| `./gradlew test` | Run fast unit, context, and module-boundary tests |
| `./gradlew integrationTest` | Build the PostgreSQL 17 + zhparser image and run database integration tests |
| `./gradlew repoCheck` | Validate repository documents, skills, and credential-ignore rules |
| `./gradlew check` | Run the complete local and CI verification suite |

## Decision records (notes/)

- The directory encodes status: proposed/ holds proposals awaiting implementation, implemented/ holds settled decisions, rejected/ holds declined proposals, archived/ holds retired records.
- A decision that needs no implementation goes directly into implemented/. One that needs implementation starts in proposed/; whoever implements it moves it into implemented/ in the same change and updates it to describe what was actually built.
- Proposals must land in the repository: something agreed in conversation exists only once written as a proposed note. A future implementer may not have this conversation; the repository is the shared memory.
- Every note must stand alone: a reader who sees only the repository must be able to resolve every reference (see [trim-cot-leakage](.agents/skills/trim-cot-leakage/SKILL.md)). A well-written proposed note can serve directly as a subagent's task brief.
- File name: yyyy-mm-dd-topic.md. Add or update a note when a non-trivial product, architecture, process, or data-format decision has rationale or trade-offs a future maintainer may revisit. Purely mechanical edits and self-contained refinements to an existing standing rule or skill are exempt. A note records the problem, decision or proposal, real alternatives, and consequences or risks.
- Never rewrite an old note into a different decision. A reversal gets a new cross-linked note; an implemented note may still update paths, names, and other facts while its decision remains the same.
- A rejected note is kept only while it still prevents the same proposal from being raised again; delete it once it no longer does. A stale proposal moves to rejected/, never to archived/.
- archived/ takes only implemented notes whose decision has fully shipped and whose rationale no longer guides future work. Judge by guidance value only, never by length, age, or count. Archived notes are frozen: no edits, no moves, no authority over current behavior.
- When adding a note, run the same-topic audit described in [archive-notes](.agents/skills/archive-notes/SKILL.md).

## Rules

- Agent instructions and skills are English-only. The requirements note and public README retain .zh.md counterparts; other notes are English by default and do not require a Chinese counterpart.
- Read [prose-standard](.agents/skills/prose-standard/SKILL.md) before writing any document.
- Never replace an explicitly required repository or platform check with an invented manual equivalent. If that required capability is unavailable, stop and report it.
- Commit messages use conventional commits (feat / fix / docs / test / chore / refactor / ci / build); commit in small steps.
- Treat main as protected and never push directly; changes go through short-lived branches and PRs. Restore platform enforcement before the repository becomes public.
- No credentials in the repository, ever. `.env` is the first line of .gitignore.

## Skills (.agents/skills/)

Skills own reusable workflows and specialized decision standards. Keep each entrypoint concise, but preserve every rule that changes a decision, permission, stopping condition, or required evidence. Mark infrastructure-dependent commands "to be filled" until the owning tool exists. Strengthen an existing owner before adding a new skill; add one when a distinct workflow repeatedly fails.

| Skill | Purpose |
|---|---|
| [prose-standard](.agents/skills/prose-standard/SKILL.md) | Prose baseline; read before writing any document |
| [trim-cot-leakage](.agents/skills/trim-cot-leakage/SKILL.md) | Remove references only the authoring session could resolve |
| [doc-standards](.agents/skills/doc-standards/SKILL.md) | Where content belongs + the document audit checklist |
| [review](.agents/skills/review/SKILL.md) | Semantic review of a change: correctness, lifecycle, security, evidence; report only |
| [find-simplifications](.agents/skills/find-simplifications/SKILL.md) | Find simplification candidates; propose, never delete directly |
| [pre-push-checks](.agents/skills/pre-push-checks/SKILL.md) | Choose the smallest set of checks covering the outgoing change |
| [archive-notes](.agents/skills/archive-notes/SKILL.md) | Workflow for the four-state notes lifecycle |
| [translate-docs](.agents/skills/translate-docs/SKILL.md) | Bilingual document maintenance; user-invoked only |
| [ui-evidence](.agents/skills/ui-evidence/SKILL.md) | PRs changing user-visible UI attach evidence from a real run |

## Editing this file

Keep every rule self-contained: one line for the rule, a link for the rationale; condense whenever clarity survives. Update the relevant sections when the phase changes (development starts, command table established, first release).
