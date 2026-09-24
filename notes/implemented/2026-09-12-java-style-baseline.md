# Java Style Baseline

Date: 2026-09-12
Status: Implemented

## Problem

Java style drifted silently across agent sessions: each brought its own habits, AGENTS.md carried no Java rule, and review attention went to formatting, braces, imports, and method size instead of semantics. A formatter alone would fix layout but say nothing about how Java is written.

Measured on `src/main` before this change (207 files, 21,207 lines): 1,018 `if`/`else`/`for`/`while` bodies without braces, 217 inline fully qualified type names, 12 wildcard imports in 9 files, 29 methods over 60 code lines, and 5 files over 600 lines, the largest `IsolatedRepositoryExecutor.java` at 1,691. Grep also found 71 lines building literal-key maps, 42 hand-checked `JsonNode.path(...).isX()` calls, 123 exceptions thrown without a message, and 71 of 280 `catch` clauses naming `RuntimeException` or `Exception`.

## Decision

- Spotless with palantir-java-format owns formatting, and `check` depends on `spotlessCheck`; `./gradlew spotlessApply` rewrites sources into the canonical form. palantir-java-format keeps four-space indentation and a 120-column limit, which matched the existing code closely enough that adoption was one mechanical commit. The formatter version is pinned in the build script and upgraded deliberately.
- [Java style](../../docs/java-style.md), routed from AGENTS.md for Java changes and reviews, owns eleven rules covering braces, records over literal-key maps, validating records over hand-written `JsonNode` checks, single-condition guards, exception messages and causes, specific catches, imports over qualified names, `var` only with a visible type, records and sealed types for data, method and file length limits, and Spotless-owned formatting.
- Checkstyle 14.1.0 runs on every Java source set as part of `check` with exactly five modules: `NeedBraces`, `AvoidStarImport`, `UnusedImports`, `MethodLength` at 60 code lines with blank lines and comments excluded, and `FileLength` at 600 lines. The configuration lives in `config/checkstyle/`. Wildcard imports are banned everywhere, static ones included, because the mechanical pass left none anywhere to keep.
- `config/checkstyle/suppressions.xml` exempts the test and integration-test source sets from the two length checks as a whole, because test methods are scenarios and test classes are suites. Production code has no exemption. The files and methods already over a limit when the gate landed (6 files and 37 methods) were registered one file and one method at a time and removed as their code was split; `xpath-suppressions.xml` is now empty, and new code gets no entry. The gate is never disabled with `ignoreFailures`.
- One mechanical pass rewrote the tree before the gate landed: OpenRewrite's `NeedBraces`, `ShortenFullyQualifiedTypeReferences`, and `RemoveUnusedImports` with a style that never folds imports into wildcards, followed by `spotlessApply`. OpenRewrite is not part of the build; its Java parser failed on JDK 26, so the run used a Gradle daemon on JDK 25 while the build toolchain stays on Java 26.
- The rules on literal-key maps, `JsonNode` checks, exception messages and causes, catch specificity, guard clauses, and `var` are not gated and were not applied to existing code. They bind new and changed code.

## Alternatives

Checkstyle can report formatting violations but does not fix them, so every finding would cost a manual edit and a rule debate. A formatter makes the canonical form executable.

google-java-format tracks new Java syntax sooner, but its two-space style, like an Eclipse profile, would rewrite every file for no semantic gain and still add no braces and no length limit; formatters lay out code and do not change statements. palantir-java-format stays.

Relying on review alone was the status quo that produced the counts above. Review catches intent; a machine catches the thousandth missing brace.

Adopting a broader Checkstyle profile such as the Sun or Google set would open a rule debate on every file. Five modules gate what the rules make mechanical and nothing else.

Keeping OpenRewrite in the build would give continuous auto-fixing but add a plugin whose parser lags the JDK; the one-off run already showed that on JDK 26.

## Consequences

Formatting disagreements end at the formatter; hand-tuned layout that disagrees with it does not survive `spotlessApply`. Braces added about 1,900 lines to `src/main`.

The pinned formatter and Checkstyle each parse Java with their own grammar. A language feature either cannot parse fails `spotlessCheck` or `checkstyleMain` visibly and is resolved by upgrading that pin.

Length is a symptom of repeated logic as often as of size; [shared checks](2026-09-12-shared-checks.md) records the first split of the exemption register and the repeated checks underneath the lengths.

## Verification

`spotlessCheck`, `checkstyleMain`, `checkstyleTest`, and `checkstyleIntegrationTest` enforce the gates, and `check` runs all four.
