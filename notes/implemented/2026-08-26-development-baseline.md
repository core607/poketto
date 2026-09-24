# Development Baseline

Date: 2026-08-26
Status: Implemented

## Problem

Poketto had settled product boundaries but no executable build, module enforcement, database test environment, repository checks, or continuous integration. The baseline needed to establish those capabilities without implementing product behavior.

The requirements named `write` as an application module, but that boundary also owned document reads, history, identity, revisions, Markdown validation, and the content git repository. A verb-named write-only boundary would have left those responsibilities without a coherent owner.

## Decision

- Use the Gradle Wrapper pinned to Gradle 9.7.1, Kotlin build scripts, Java 26, Spring Boot 4.1.1, and Spring Modulith 2.1.0.
- Build one Spring Boot artifact. Application modules are direct packages beneath `io.github.core607.poketto`, not Gradle subprojects. The browser frontend is a separate Next.js artifact: Spring's `web` module owns business HTTP contracts, and frontend components own pages and rendering ([blog and browser interface](2026-09-06-blog-browser-interface.md)).
- Replace the planned `write` module with `content`. The application modules are `assets`, `auth`, `capture`, `community`, `content`, `executor`, `mcp`, `qa`, `spaces`, `web`, and `workspace`.
- Keep public contracts at each module boundary and implementation details below `internal`. Add ports or mapping layers only at a real protocol, infrastructure, or trust boundary.
- Remote Git is the content authority. PostgreSQL stores relational application state, not a content projection or search index ([stock PostgreSQL](2026-09-05-stock-postgresql.md)); the query-side projection first planned here, with its `projection` and `search` modules, was never built.
- Keep fast tests in the standard test suite and Docker-backed database coverage in a separate `integrationTest` suite, which runs against the official PostgreSQL 17 image pinned by digest.
- Make `repoCheck` validate repository-local invariants: Markdown links, required bilingual pairs, English-only agent surfaces, skill metadata and inventory, credential-ignore rules, and the [document budgets](../../config/document-budgets.properties). Make `check` aggregate unit tests, repository checks, integration tests, the frontend build, the deployment, executor and gateway suites, and the Spotless and Checkstyle gates of the [Java style baseline](2026-09-12-java-style-baseline.md).
- GitHub Actions runs those tasks as parallel lanes. A pull request that changes only Markdown or `LICENSE` runs the `docs` lane (`repoCheck` and a whitespace check), because Markdown never reaches a compiler, container, or deployment script; one unrecognized path makes the whole change code. Any other change, and every push to `main`, runs the `java`, `database`, `web`, `image`, `stack`, and `retrieval-lab` lanes. The required `verify` job always runs and passes only when every lane its classification requires succeeded; a skipped lane counts as a failure ([metadata-edit verification](2026-09-12-metadata-edit-verification.md)).
- Pull-request CI publishes nothing and changes no repository settings; [continuous delivery](2026-09-03-continuous-delivery.md) owns publication from `main`.

The [requirements note](2026-08-25-requirements-and-architecture.md) remains the authority for product behavior.

## Alternatives

Maven provides a predictable lifecycle and a smaller learning surface, but repository-specific checks and separately addressable test suites require more plugin wiring. Gradle gives those checks first-class task boundaries while exposing a small command surface through the Wrapper.

A Gradle multi-project build would provide stronger artifact boundaries, but Poketto ships as one process and has no independently released modules. Spring Modulith package verification supplies the needed boundary checks without multiplying build files and dependency edges.

Global `api`, `service`, `dto`, and `mapping` packages were rejected as top-level boundaries. They group code by technical role, so one capability spreads across the repository. Similar roles may still appear inside a module when that module needs them.

Keeping the name `write` was rejected because the owning capability includes reads and history as well as mutations. `content` names the source-of-truth boundary rather than one operation on it.

## Consequences

Pinned build versions and a digest-pinned database image make upgrades deliberate. Docker is required for `integrationTest` and therefore for the full `check`; unit tests, integration-test compilation, and `repoCheck` remain available without it.

Gradle permits arbitrary build logic. Repository checks stay in one small script, and build conventions remain in the root project until repeated complexity justifies a separate build-logic component.

## Verification

`./gradlew test` runs the application-context and Spring Modulith boundary tests (`ModularityTests`), `./gradlew integrationTestClasses` compiles the database suite without Docker, and `./gradlew check` aggregates the rest. `.github/review/test_review.py` pins that every `check` task still belongs to a CI lane and that `verify` requires each lane it waits on.
