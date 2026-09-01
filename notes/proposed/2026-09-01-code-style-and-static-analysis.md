# Automated Code Style and Static Analysis

Date: 2026-09-01
Status: Proposed

## Problem

This repository is written mostly by agents, and AGENTS.md invites agents from any vendor to contribute. The Java sources share a visible convention — four-space indentation, wrapping near a hundred columns, one statement per line, imports ordered — but nothing states or enforces it. The convention travels only by imitation of neighboring files.

Imitation has already failed in places: sources in the `content` module exceed the width their neighbors keep. The condition is mechanically detectable, and no task detects it. `check` depends on unit tests, repository checks, and integration tests; none of them inspects Java source form.

The cost lands in two places. Review attention that the [review skill](../../.agents/skills/review/SKILL.md) directs at correctness, lifecycle, security, and evidence gets spent on wrapping instead. And a later contributor who reformats while editing produces a diff whose behavior change is hidden inside reformatting noise.

Style is also the class of question an agent cannot settle by reading the code, because the code disagrees with itself. A formatter answers it without a decision.

## Proposal

### One formatter, applied rather than argued

A formatter task rewrites Java sources deterministically, and a verification task fails when the tree does not match its output. An agent that hits a style question runs the formatter instead of inferring a rule from neighbors.

The selected format must sit close to the convention the sources already keep, so that adopting it is a mechanical rewrite rather than a redesign of how the code looks. The formatter version is pinned like every other tool in the build, because an unpinned formatter turns an upstream release into an unrelated failing build.

### Correctness rules, not taste rules

Beyond form, a small set of static checks may fail the build, and each one must name the defect class it prevents — an ignored return value, an unclosed resource, an inconsistent `equals` and `hashCode` pair, an unreachable or unused declaration. A rule that only encodes preference is rejected: [prose-standard](../../.agents/skills/prose-standard/SKILL.md) and human review own judgment, and a build gate cannot hold an opinion.

A rule that reports without failing is noise. Each rule either fails `check` or is not enabled.

### The reformatting change stands alone

Adopting a formatter rewrites many files at once. That rewrite lands as its own change containing no behavior edit, so history stays reviewable and the damage to `git blame` happens once at a known commit rather than spreading across later feature work.

### Scope

The gate covers Java sources. Markdown, skills, and bilingual pairs keep `repoCheck` as their owner, and workflow files keep whatever linter the CI change that introduced them established. This proposal adds no second owner for those surfaces.

## Implementation scope and dependencies

This proposal depends on the implemented [development baseline](../implemented/2026-08-26-development-baseline.md) for the Gradle build, the Java toolchain, and the `check` aggregate.

The first implementation includes the formatter plugin at a pinned version, its wiring into `check`, the justified static-analysis rule set, the isolated reformatting change, the command-table entry in AGENTS.md, and evidence that the gate rejects unformatted input.

It excludes IDE configuration files, a Markdown or Kotlin formatter, coverage thresholds, mutation testing, architectural rules already enforced by Spring Modulith, and any rule adopted without a named defect class.

## Alternatives considered

**Keep style as an unwritten convention.** It costs no tooling and worked while the codebase was small enough to read in one sitting. It depends on every future agent inferring the rule correctly from surrounding files, and the existing width drift shows that inference already failing.

**Adopt a violation reporter without a formatter.** A reporter needs no rewriting commit and never touches code it should not. It converts every violation into manual hand-wrapping by whoever is holding the task, which is the work a formatter removes entirely.

**Enable a large preset of rules at once.** Presets are quick to turn on and cover more ground immediately. They mix defect detection with taste, so the first run produces a long suppression list, and suppressions accumulate faster than anyone reads them. A justified minimum keeps each rule's cost visible and lets the set grow on evidence.

**Enforce formatting through a local commit hook.** A hook gives feedback at the earliest possible point. It is not installed by a fresh clone, cannot be enforced, and is invisible to an agent that commits through a different path, so the authoritative gate still has to live in `check`.

**Reformat gradually, file by file, as files are edited.** No large commit and no blame event. The tree stays permanently half-formatted, the gate cannot be turned on until the last file is done, and every feature diff carries the reformatting of the files it happens to touch.

## Acceptance

- A deliberately misformatted source fails `check`, and the formatter task repairs it.
- Running the formatter twice in a row produces no change on the second run.
- The formatter parses every source at the project's Java language level. A formatter that cannot is disqualified before adoption rather than accommodated by lowering the language level.
- The reformatting change alters no behavior: the test suites pass before and after, and the change contains no edit other than formatting.
- Every enabled static-analysis rule names the defect class it prevents, and no enabled rule reports without failing.
- `repoCheck` remains the only owner of Markdown and skill invariants.

## Risks

Java formatters have lagged new language levels before, and some require JVM export flags to run at all. Compatibility with the project's toolchain has to be demonstrated before adoption; failing to demonstrate it is a reason to defer the gate, never a reason to move the language level to suit a tool.

A one-time reformat rewrites `git blame` for every line it touches. Confining it to one isolated commit bounds the cost and makes the noise easy to skip when reading history.

A gate that fails on cosmetics can stop an agent in the middle of a task and consume its attention. The apply task must be documented as the first response so that the interruption costs one command rather than a manual pass.

Static analysis produces false positives. Each suppression is a small permanent cost, so a rule whose suppressions outnumber its findings should be removed rather than kept and worked around.
