# Code Style Gate

Date: 2026-09-02
Status: Implemented

## Problem

The [development baseline](2026-08-26-development-baseline.md) verifies behavior, module boundaries, documents, and credentials, but nothing verifies Java source style. Style drifts silently across agent sessions, and review attention goes to formatting instead of semantics.

## Decision

Add Spotless with palantir-java-format to the build and make `check` depend on `spotlessCheck`. `./gradlew spotlessApply` rewrites sources into the canonical form; CI rejects unformatted code the same way it rejects failing tests.

palantir-java-format keeps four-space indentation and a 120-column limit, which matches the existing code closely enough that adoption is one bounded mechanical commit. The formatter version is pinned in the build script; upgrades are deliberate.

## Alternatives

Checkstyle reports violations without fixing them, so every finding costs a manual edit and a rule debate. A formatter makes the canonical form executable.

google-java-format is maintained by Google and tracks new Java syntax sooner, but its two-space style would rewrite the entire repository away from its current shape for no semantic gain.

## Consequences

Formatting disagreements end at the formatter; hand-tuned layout that disagrees with it does not survive `spotlessApply`. A future Java language feature may outrun the pinned formatter's parser; the failure is visible in `spotlessCheck` and resolved by upgrading the pin.

## Verification

- `./gradlew spotlessCheck` fails on an unformatted source file and passes on the formatted tree.
- `./gradlew check` runs `spotlessCheck`.
