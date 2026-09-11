# Java Style Baseline

Date: 2026-09-12
Status: Implemented

## Problem

The [code style gate](2026-09-02-code-style-gate.md) makes formatting executable but says nothing about how Java is written, and AGENTS.md carried no Java rule. Each agent session brought its own habits, so review attention went to braces, imports, and method size instead of semantics.

Measured on `src/main` before this change (207 files, 21,207 lines):

| Pattern | Count | How measured |
|---|---|---|
| `if`/`else`/`for`/`while` bodies without braces | 1,018 | Checkstyle `NeedBraces` |
| Fully qualified type names written inline | 217 | grep for a package path followed by a type name, outside imports |
| Wildcard imports | 12 in 9 files | Checkstyle `AvoidStarImport` |
| Methods over 60 code lines | 29 | Checkstyle `MethodLength`, blank lines and comments excluded |
| Files over 600 lines | 5, largest `IsolatedRepositoryExecutor.java` at 1,691 | Checkstyle `FileLength` |
| Lines building maps with `Map.of("key", ...)` | 71 | grep |
| Lines hand-checking `JsonNode.path(...).isX()` | 42 | grep |
| Exceptions thrown without a message | 123 | grep for `throw new X()` |
| `catch` clauses naming `RuntimeException` or `Exception` | 71 of 280 | grep |

## Decision

- AGENTS.md gains a "Java style" section with eleven rules covering braces, records over literal-key maps, validating records over hand-written `JsonNode` checks, single-condition guards, exception messages and causes, specific catches, imports over qualified names, `var` only with a visible type, records and sealed types for data, method and file length limits, and Spotless-owned formatting.
- Checkstyle 14.1.0 runs on every Java source set as part of `check` with exactly five modules: `NeedBraces`, `AvoidStarImport`, `UnusedImports`, `MethodLength` at 60 code lines with blank lines and comments excluded, and `FileLength` at 600 lines. The configuration lives in `config/checkstyle/`. Wildcard imports are banned everywhere, static ones included, because the mechanical pass left none anywhere to keep.
- `config/checkstyle/suppressions.xml` exempts the test and integration-test source sets from the two length checks as a whole, and names the six production files that were already over 600 lines. `config/checkstyle/xpath-suppressions.xml` exempts each over-limit method by name, so a new long method inside an exempted file still fails. Splitting removes an entry; new code gets none. The gate is never disabled with `ignoreFailures`.
- One mechanical pass rewrote the tree before the gate landed: OpenRewrite's `NeedBraces`, `ShortenFullyQualifiedTypeReferences`, and `RemoveUnusedImports` with a style that never folds imports into wildcards, followed by `spotlessApply`. OpenRewrite is not part of the build. The run needed a Gradle daemon on JDK 25 because the OpenRewrite Java parser fails on JDK 26; the build toolchain stays on Java 26.
- The rules on literal-key maps, `JsonNode` checks, exception messages and causes, catch specificity, guard clauses, and `var` are not gated and were not applied to existing code. They bind new and changed code, and the executor protocol rewrite that applies them is a separate task.

## Alternatives

Switching the formatter to google-java-format or an Eclipse profile would rewrite every file for no semantic gain and still add no braces and no length limit; formatters lay out code and do not change statements. palantir-java-format stays.

Relying on review alone was the status quo that produced the counts above. Review catches intent; a machine catches the thousandth missing brace.

Adopting a broader Checkstyle profile such as the Sun or Google set would open a rule debate on every file. Five modules gate what the rules make mechanical and nothing else.

Keeping OpenRewrite in the build would give continuous auto-fixing but add a plugin whose parser lags the JDK; the one-off run already showed that on JDK 26.

## Consequences

Braces added about 1,900 lines: `src/main` grew from 21,207 to 23,167 lines and `IsolatedRepositoryExecutor.java` from 1,691 to 1,936. After the pass Checkstyle reports zero brace, wildcard-import, and unused-import violations in all source sets.

The remaining violations are the suppressed length limits: 6 production files over 600 lines, and 37 named methods over 60 code lines across 20 files. The method count is the post-brace figure. Adding braces pushed 8 methods over the limit that were under it before, and one more file over the file limit, so the debt register is larger than the measurement in the problem statement.

An exemption names a method, not a signature, so where an exempted name is overloaded its siblings are exempted too. That covers 4 methods that are currently under the limit: the second `initialize` in `LocalPortableContentExports.java` and the second `fetchMedia`, `open`, and `readCapture` in `IsolatedRepositoryExecutor.java`. Matching a signature in XPath would need the parameter list spelled out in the suppression, which rots against ordinary refactoring; the name is the readable granularity.

The suppression list is a debt register. The largest entry, `IsolatedRepositoryExecutor.java`, is split when the worker protocol moves to records; the others shrink when their owners are touched.

Checkstyle parses each Java release with its own grammar. A language feature the pinned version cannot parse fails `checkstyleMain` visibly and is resolved by upgrading the pin, as with the formatter.

## Verification

- `./gradlew checkstyleMain checkstyleTest checkstyleIntegrationTest` passes on the rewritten tree. It fails on an inserted braceless `if`, on a wildcard import added to `src/main`, and on a new 72-line method added to `IsolatedRepositoryExecutor.java`, whose twelve grandfathered methods keep passing in the same run.
- `./gradlew check` runs the Checkstyle tasks.
- The mechanical pass changed 176 files; a token comparison that ignores braces, imports, package prefixes, and whitespace found no other difference, and the unit, module-boundary, and integration suites pass unchanged.
