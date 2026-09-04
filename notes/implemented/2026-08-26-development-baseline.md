# Development Baseline

Date: 2026-08-26
Status: Implemented

[Stock PostgreSQL](2026-09-05-stock-postgresql.md) replaces the custom integration image and its tokenizer smoke test with a pinned official image and UTF-8 verification. The build and workspace ownership decisions in this record remain applicable.

## Problem

Poketto had settled product boundaries but no executable build, module enforcement, database test environment, repository checks, or continuous integration. The baseline needed to establish those capabilities without implementing product behavior.

The requirements named `write` as an application module, but that boundary also owned document reads, history, identity, revisions, Markdown validation, and the content git repository. A verb-named write-only boundary would have left those responsibilities without a coherent owner.

## Decision

- Use the Gradle Wrapper pinned to Gradle 9.7.1, Kotlin build scripts, Java 26, Spring Boot 4.1.1, and Spring Modulith 2.1.0.
- Build one Spring Boot artifact. Application modules are direct packages beneath `io.github.core607.poketto`, not Gradle subprojects.
- Replace the planned `write` module with `content`. The initial modules are `content`, `projection`, `search`, `auth`, `qa`, `mcp`, and `web`.
- Keep public contracts at each module boundary and implementation details below `internal`. Add ports or mapping layers only at a real protocol, infrastructure, or trust boundary.
- Treat the content git repository as the command-side source of truth and PostgreSQL as a rebuildable query-side projection. Spring events may wake projection work but do not replace commit replay and checkpoints.
- Keep fast tests in the standard test suite and Docker-backed database coverage in a separate `integrationTest` suite.
- Build the integration database from a pinned PostgreSQL 17 image with pinned SCWS and zhparser sources.
- Make `repoCheck` validate repository-local document and skill invariants. Make `check` depend on unit tests, repository checks, integration tests, and the [code style gate](2026-09-02-code-style-gate.md).
- Run `check` in GitHub Actions without publishing artifacts or changing repository settings.

The [requirements note](2026-08-25-requirements-and-architecture.md) remains the authority for product behavior.

## Alternatives

Maven provides a predictable lifecycle and a smaller learning surface, but repository-specific checks and separately addressable test suites require more plugin wiring. Gradle gives those checks first-class task boundaries while exposing a small command surface through the Wrapper.

A Gradle multi-project build would provide stronger artifact boundaries, but Poketto ships as one process and has no independently released modules. Spring Modulith package verification supplies the needed boundary checks without multiplying build files and dependency edges.

Global `api`, `service`, `dto`, and `mapping` packages were rejected as top-level boundaries. They group code by technical role, so one capability spreads across the repository. Similar roles may still appear inside a module when that module needs them.

Keeping the name `write` was rejected because the owning capability includes reads and history as well as mutations. `content` names the source-of-truth boundary rather than one operation on it.

## Consequences

The custom database image compiles native code, so a cold integration run is slower than one using a stock PostgreSQL image. Pinning every source makes upgrades deliberate. Docker is required for `integrationTest` and therefore for the full `check`; unit tests, integration-test compilation, and `repoCheck` remain available without it.

Gradle permits arbitrary build logic. Repository checks stay in one small script, and build conventions remain in the root project until repeated complexity justifies a separate build-logic component.

## Verification

- `./gradlew test` runs the application-context and Spring Modulith boundary tests.
- `./gradlew integrationTestClasses` compiles the database integration suite without requiring Docker.
- `./gradlew integrationTest` uses [pinned official PostgreSQL](2026-09-05-stock-postgresql.md) and verifies PostgreSQL 17, UTF-8 text support, workspace initialization, and application entry paths.
- `./gradlew repoCheck` validates Markdown links, required bilingual pairs, English-only agent surfaces, skill metadata and inventory, the translate-docs invocation policy, and credential-ignore rules.
- `./gradlew check` aggregates all required local and CI evidence.
