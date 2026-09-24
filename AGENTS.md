# AGENTS.md

Poketto is a Git-native content workspace: members and their agents author each space's repository through the browser and MCP, and published spaces serve public websites. Remote Git is content authority; workspace repositories cached locally are disposable. The [requirements note](notes/implemented/2026-08-25-requirements-and-architecture.md) owns implemented product boundaries; proposed notes own accepted targets awaiting implementation and must not be described as shipped.

The public README, [usage reference](docs/usage.md), requirements note and [content foundation](notes/implemented/2026-08-26-content-foundation.md) have .zh.md counterparts; make the smallest corresponding edit to both sides in the same change.

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

Use the Gradle Wrapper; on Windows replace `./gradlew` with `.\gradlew.bat`. Java 26 is required. `integrationTest` and `check` also require a working Docker-compatible daemon. The frontend and `check` additionally require Node.js 24.19.0 and npm 12.0.2. `check` requires Python 3.10+ and OpenSSL 3+ on PATH; Linux executor tests additionally require venv and pip support.

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

- The directory encodes status: proposed/ holds proposals awaiting implementation, implemented/ holds settled decisions, rejected/ holds declined proposals, archived/ holds frozen history. A decision that needs no implementation goes directly into implemented/.
- Write or update a note only for lasting rationale that code, tests, and current docs do not explain: a real alternative, a trade-off, or an ownership, security, durability, or reintroduction rule. What a feature does belongs in docs/ or its README; local UI and mechanical changes need no note. Updating the note that owns the decision satisfies the rule ([rationale](notes/implemented/2026-09-25-note-lifecycle-and-document-budgets.md)).
- Proposals must land in the repository: work agreed in conversation exists only once written as a proposed note, because a future implementer may not have that conversation. Whoever implements it, in the same change, either moves it into implemented/ rewritten as what was built, or deletes it when it holds no lasting rationale once the behavior is documented where it belongs.
- Every note must stand alone: a reader who sees only the repository must be able to resolve every reference, so cite committed paths and never a chat, a plan, a review round, or a private machine path. A well-written proposed note can serve directly as a subagent's task brief.
- File name: yyyy-mm-dd-topic.md. A note records the problem, the decision or proposal, real alternatives, and consequences; its verification names the tests or checks that pin the decision without restating their cases.
- Never rewrite an old note into a different decision. A reversal gets a new cross-linked note or consolidates the old one into it; an implemented note keeps its paths, names, and other facts current.
- Every new note triggers a same-topic audit resolved in the same change, never deferred. [maintain-notes](.agents/skills/maintain-notes/SKILL.md) classifies notes for deletion, consolidation into their current owner, archiving, or rejection.
- archived/ is frozen: archiving is a move plus an `Archived: YYYY-MM-DD` line below the note's date, with inbound links redirected first; afterwards the note takes no edits and has no authority over current behavior.
- [Document budgets](config/document-budgets.properties) cap the active notes and the standing documents, and `repoCheck` enforces them. When one fails, move content to its home, condense it, or audit the notes; raise a ceiling only when the words are needed, and say why in the pull request.

## Where content belongs

Each fact has exactly one home; everywhere else links to it.

| Home | Holds | Does not hold |
|---|---|---|
| This file | Rules, one to three lines each, with the rationale behind a link | Examples, procedures, anything restated from its home |
| notes/ | Decision rationale, rejected alternatives, consequences | What a feature does, plans or acceptance checklists after shipping, restated test cases, authoring-session narration |
| .agents/skills/ | Reusable workflows and decision standards | Product behavior and contracts |
| docs/ and READMEs | Current behavior, configuration, and operation | Rationale, change history, implementation walkthroughs |

Runbooks tied to a specific machine or environment never enter this repository; they live in the operator's private storage.

## Rules

- Agent instructions and skills are English-only. Notes are English by default unless covered by the bilingual pairs declared above.
- Preserve factual obligations, conditions, exceptions, and consequences; remove repetition and authoring-session narration. Use [prose-standard](.agents/skills/prose-standard/SKILL.md) for substantive writing, rewriting, or prose audits.
- Never replace an explicitly required repository or platform check with an invented manual equivalent. If that required capability is unavailable, report it and block actions and completion claims that depend on it; continue only authorized work whose outcome does not depend on that check, without claiming the check passed.
- Commit messages use conventional commits (feat / fix / docs / test / chore / refactor / ci / build); commit in small steps.
- Work on short-lived branches; never push directly to main. Within an assigned task, push, open the pull request, answer the review and drive it to merge without asking for approval at each step; stop only for a destructive action or a decision the user must make.
- No credentials in the repository, ever. `.env` is the first line of .gitignore.

## Skills (.agents/skills/)

Explicit user instructions take precedence over general skill guidance within higher-priority constraints. A skill does not authorize unrelated work. If a skill blocks requested work, cite the exact file and instruction and distinguish its requirement from your interpretation.

Skills own reusable workflows and specialized decision standards. Keep a rule only while you can name the real failure it prevents, and delete it otherwise; a guardrail that has never caught anything is cost without cover. Strengthen an existing owner before adding a new skill; add one when a distinct workflow repeatedly fails.

`.agents/skills/` is the single source; Claude Code discovers project skills only below `.claude/skills/`, so that directory holds generated stubs. Never edit stubs by hand: run `./gradlew syncClaudeSkills` after changing a skill's frontmatter, and `repoCheck` fails when stubs drift.

| Skill | Purpose |
|---|---|
| [prose-standard](.agents/skills/prose-standard/SKILL.md) | Substantive writing, rewriting, and prose audits |
| [review](.agents/skills/review/SKILL.md) | Semantic review of a change: correctness, lifecycle, security, evidence |
| [find-simplifications](.agents/skills/find-simplifications/SKILL.md) | Find simplification candidates |
| [pre-push-checks](.agents/skills/pre-push-checks/SKILL.md) | Choose the checks that cover the outgoing change |
| [maintain-notes](.agents/skills/maintain-notes/SKILL.md) | Write, audit, delete, consolidate, and archive decision records |

## Editing this file

Keep every rule self-contained: one line for the rule, a link for the rationale; condense whenever clarity survives. Update the relevant sections when the phase changes (development starts, command table established, 1.0).
