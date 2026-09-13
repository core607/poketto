# AGENTS.md

Poketto is a repository-native personal knowledge service whose public face is a blog. Remote Git is content authority; workspace repositories cached locally are disposable. The [requirements note](notes/implemented/2026-08-25-requirements-and-architecture.md) owns implemented product boundaries; proposed notes own accepted targets awaiting implementation and must not be described as shipped.

The public README, requirements note and [usage reference](docs/usage.md) have .zh.md counterparts; make the smallest corresponding edit to both sides in the same change. The extended [translate-docs](.agents/skills/translate-docs/SKILL.md) workflow is user-invoked only.

## Arrival guide

Current phase: development. Implement product capabilities only from an assigned, settled task; do not infer work from the roadmap or implement ideas still under discussion.

Read the owning code and documents needed for the task. Local wording and mechanical edits need only directly relevant context. Use these routes when their subject is affected:

- Product behavior, permissions, or data ownership: the [requirements note](notes/implemented/2026-08-25-requirements-and-architecture.md) and the owning decisions, including any record the change implements or supersedes.
- Content and media: the [CodeAct contract](notes/implemented/2026-09-09-codeact-content-and-media.md).
- Build, module boundaries, database test image, or CI: the [development baseline](notes/implemented/2026-08-26-development-baseline.md).
- Java changes or reviews: the [Java style rules](docs/java-style.md).

Keep product code inside the owning application module. Within the authorized scope, finish implementation, applicable verification, and fixes for regressions introduced by the change. Continue until the requested outcome is verified or a concrete blocker needs user input; a first implementation alone is not completion.

## Phase clause (delete this section at 1.0)

Before 1.0 there is no compatibility promise; a 0.x release names a commit and describes its changes without adding one. Rename and refactor freely; write no compatibility shims. The database schema and the content-repo format may change destructively; rebuild instead of migrating.

## Commands

Use the Gradle Wrapper; on Windows replace `./gradlew` with `.\gradlew.bat`. Java 26 is required. `integrationTest` and `check` also require a working Docker-compatible daemon. The frontend and `check` additionally require Node.js 24.19.0 and npm 12.0.2. `check` requires Python 3.10+; Linux executor tests additionally require venv and pip support.

| Command | Purpose |
|---|---|
| `./gradlew bootRun` | Start the application locally |
| `./gradlew test` | Run fast unit, context, and module-boundary tests |
| `./gradlew integrationTest` | Run database integration tests against pinned official PostgreSQL 17 |
| `./gradlew frontendCheck` | Run pinned frontend formatting, type checks, tests, and production build |
| `./gradlew repoCheck` | Validate repository documents, skills, and credential-ignore rules |
| `./gradlew syncClaudeSkills` | Regenerate the Claude Code skill stubs in .claude/skills |
| `./gradlew check` | Run the complete local and CI verification suite |

Use [pre-push-checks](.agents/skills/pre-push-checks/SKILL.md) for delivery verification and specialized commands. Select evidence for the changed surface; a full local suite is not the default.

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

- Agent instructions and skills are English-only. Notes are English by default unless covered by the bilingual pairs declared above.
- Preserve factual obligations, conditions, exceptions, and consequences; remove repetition and authoring-session narration. Use [prose-standard](.agents/skills/prose-standard/SKILL.md) for substantive writing, rewriting, or prose audits.
- Never replace an explicitly required repository or platform check with an invented manual equivalent. If that required capability is unavailable, report it and block actions and completion claims that depend on it; continue only authorized work whose outcome does not depend on that check, without claiming the check passed.
- Commit messages use conventional commits (feat / fix / docs / test / chore / refactor / ci / build); commit in small steps.
- Work on short-lived branches; never push directly to main. Pushing and creating a PR each require explicit user authorization for that action and scope. Restore platform enforcement before the repository becomes public.
- No credentials in the repository, ever. `.env` is the first line of .gitignore.

## Skills (.agents/skills/)

Explicit user instructions take precedence over general skill guidance within higher-priority constraints. Carry forward authorization already given for the same action and scope; a skill does not authorize unrelated work, a push, or PR creation. If a skill blocks requested work, cite the exact file and instruction and distinguish its requirement from your interpretation.

Skills own reusable workflows and specialized decision standards. Keep each entrypoint concise, but preserve every rule that changes a decision, permission, stopping condition, or required evidence. Mark infrastructure-dependent commands "to be filled" until the owning tool exists. Strengthen an existing owner before adding a new skill; add one when a distinct workflow repeatedly fails.

`.agents/skills/` is the single source; Claude Code discovers project skills only below `.claude/skills/`, so that directory holds generated stubs. Never edit stubs by hand: run `./gradlew syncClaudeSkills` after changing a skill's frontmatter or invocation policy, and `repoCheck` fails when stubs drift.

| Skill | Purpose |
|---|---|
| [prose-standard](.agents/skills/prose-standard/SKILL.md) | Substantive writing, rewriting, and prose audits |
| [trim-cot-leakage](.agents/skills/trim-cot-leakage/SKILL.md) | Remove references only the authoring session could resolve |
| [doc-standards](.agents/skills/doc-standards/SKILL.md) | Where content belongs + the document audit checklist |
| [review](.agents/skills/review/SKILL.md) | Semantic review of a change: correctness, lifecycle, security, evidence; report only unless fixes are explicitly requested |
| [find-simplifications](.agents/skills/find-simplifications/SKILL.md) | Find simplification candidates; propose unless a specific cleanup is already authorized |
| [pre-push-checks](.agents/skills/pre-push-checks/SKILL.md) | Choose the smallest set of checks covering the outgoing change |
| [archive-notes](.agents/skills/archive-notes/SKILL.md) | Workflow for the four-state notes lifecycle |
| [translate-docs](.agents/skills/translate-docs/SKILL.md) | Bilingual document maintenance; user-invoked only |
| [ui-evidence](.agents/skills/ui-evidence/SKILL.md) | PRs changing user-visible UI attach evidence from a real run |

## Editing this file

Keep every rule self-contained: one line for the rule, a link for the rationale; condense whenever clarity survives. Update the relevant sections when the phase changes (development starts, command table established, 1.0).
