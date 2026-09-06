# Stock PostgreSQL for Relational State

Date: 2026-09-05
Status: Implemented

## Problem

The [development baseline](2026-08-26-development-baseline.md) built a custom PostgreSQL image with zhparser for planned content indexing. No projection schema or search implementation exists. [Repository-native retrieval](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) and the [phase-one boundary](../proposed/2026-09-05-phase-one-daily-use.md) select committed-tree retrieval instead, leaving native image compilation and two empty modules without an application consumer.

## Decision

Integration tests use the official PostgreSQL 17.11-bookworm image pinned by digest in `build.gradle.kts`. The deployment example selects the same digest. Testcontainers receives a digest-only image reference because its image-name parser rejects the combined tag-and-digest form. The version is documented beside that reference; the digest owns reproducibility.

The deployment example also advances the previous PostgreSQL 17 image pin to the verified 17.11-bookworm manifest. This updates the next deployment's image input; it does not upgrade an already running database. The deployment scripts accept and transfer the complete tag-and-digest reference without parsing a version prefix.

Remove `infra/postgres/Dockerfile`, the `buildPostgresTestImage` task, and the empty `projection` and `search` application modules. PostgreSQL remains the workspace catalog and future relational identity/asset state store. This change adds no repository search implementation and changes no schema or stored workspace data.

`integrationTest` still starts a real database and exercises Flyway, default workspace creation, initializer reuse, health, and the public entrance. Database coverage checks PostgreSQL 17 and UTF-8 text support rather than an unused tokenizer. Docker remains required for `integrationTest` and aggregate `check`.

## Alternatives

Keeping zhparser until retrieval ships would continue compiling and testing a native extension that no runtime path uses. Removing only its smoke test would leave that unnecessary image dependency in every integration build. A mutable official tag would reduce maintenance but make test and deployment input drift; retain the verified digest.

## Related records

The same-topic audit retains the [development baseline](2026-08-26-development-baseline.md), [workspace tenancy](2026-08-27-workspace-tenancy.md), and [continuous delivery](2026-09-03-continuous-delivery.md) for their build, data-ownership, and deployment decisions. Their current image/test references point here. The broader retrieval proposal remains proposed because sandboxing, search, and protocol delivery are not completed by removing an unused extension.

## Verification and consequences

The implementation slice passed `spotlessApply check --max-workers=1`: 105 unit/module tests, five real PostgreSQL integration tests, deployment script suites, repository validation, and formatting. `git diff --check` passed. The image digest was checked against Docker's official PostgreSQL image metadata and registry manifest.

The [CI verification log for b456974](https://github.com/core607/poketto/actions/runs/33934425237/job/101219454286) independently records `test`, `integrationTest`, and `deployScriptTests` within the successful `check` run. Local reproduction requires Java 26 and Docker, then `./gradlew check`; the deployment fixtures read the database pin directly from `deploy/.env.example`.

A cold test run pulls a prebuilt official image rather than compiling SCWS and zhparser. A later persistent search design would require a new decision and evidence; this change does not reserve a database projection or tokenizer for it.
