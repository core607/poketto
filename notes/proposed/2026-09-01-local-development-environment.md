# Local Development Environment

Date: 2026-09-01
Status: Proposed

## Problem

The [development baseline](../implemented/2026-08-26-development-baseline.md) established the build, module enforcement, test suites, repository checks, and continuous integration. It did not establish a way to run the application.

Starting the application requires a reachable PostgreSQL data source and an absolute `POKETTO_DATA_DIR`. Neither exists in a fresh clone. The only database definition in the repository, `infra/postgres`, is built by `buildPostgresTestImage` and consumed through Testcontainers, so it serves `integrationTest` and nothing else. A contributor who wants to run the application assembles a server, a role, a database, and a data directory from one README paragraph.

That leaves the first run as undocumented work every contributor redoes differently, and it makes a locally reproduced defect depend on how that person's database was created. Chinese tokenization is the sharpest case: a stock PostgreSQL has no zhparser, so search behavior observed locally can differ from the behavior `check` and production observe. Agents arriving from a task brief have no command to run at all.

The [continuous delivery proposal](2026-08-26-continuous-delivery.md) owns production images, the production Compose project, and the deployment entrance. It does not describe a development runtime, and a development runtime must not become a second production definition.

## Proposal

### One database definition for tests and development

Development runs the image already defined in `infra/postgres`, at the tag the build's `poketto.postgres.image` property names. Tests and development therefore share one PostgreSQL version, one zhparser build, and one dictionary set. A second development-only database definition is rejected because divergence between it and `integrationTest` would appear as an unreproducible search defect rather than as a configuration difference.

A Compose project owned by development starts that image with a development role, database, and named volume. It contains no application service: the application runs from the IDE or `bootRun` against it, which is what makes a debugger and an incremental rebuild useful.

### Configuration and the data directory

A committed `.env.example` lists every variable the application requires, with values that work on a developer machine and grant access to nothing else. The ignore rules already admit that file and exclude `.env`.

`POKETTO_DATA_DIR` defaults to an ignored path inside the working tree. One obvious location makes workspace repositories easy to inspect while debugging, and makes destroying them safe. The repository checks that already forbid committed credentials extend to this path so a workspace repository cannot be committed by accident.

### Commands and the first run

The database lifecycle is reachable from the command surface AGENTS.md already documents rather than from a separate guide. A clean clone reaches a running application through one documented sequence: start the database, copy the example configuration, run the application.

### Disposability

The phase clause permits destructive rebuilds, so local recovery is deletion rather than migration. The documented reset destroys the database volume, the data directory, and the workspace repositories together, because a data directory that survives a dropped database describes workspaces the catalog no longer holds.

## Implementation scope and dependencies

This proposal depends on the implemented [development baseline](../implemented/2026-08-26-development-baseline.md) and the implemented [workspace and tenant boundary](../implemented/2026-08-27-workspace-tenancy.md), whose data directory layout it exposes.

The first implementation includes the development Compose project, `.env.example`, the lifecycle and reset commands, the ignore and repository-check coverage for the default data directory, the command-table and README entries, and a rehearsal from a clean clone.

It excludes the production Compose project and deployment entrance, TLS, reverse proxies, seeded demonstration content, a hosted profile, and any change to what `check` verifies.

## Alternatives considered

**Start PostgreSQL through Testcontainers for `bootRun` as well.** No Compose file would be needed and the database would always match the tests. The container and its data would not survive the process, so every run would lose local content and pay container startup, which defeats keeping a corpus to develop against.

**Use a stock `postgres:17` image for development.** It pulls faster and needs no local build. It has no zhparser, so tokenization and search behavior observed during development would not be the behavior under test, which is the specific failure this proposal exists to prevent.

**Document the steps in the README without automation.** Prose costs nothing to add. It also cannot be executed or verified, so it drifts from the build silently; a clean-clone rehearsal has nothing to fail against.

**Ship a devcontainer.** It would fix the JDK, Gradle, and Docker toolchain as well as the database, removing more first-run variance. It binds the repository to one editor ecosystem and adds an image to maintain, which needs evidence of a real toolchain problem first.

**Extend the production Compose project with a development override.** One file would describe both. Production and development want opposite defaults for exposure, credentials, and data lifetime, and a shared file makes a development convenience one edit away from production.

## Acceptance

- A clean clone reaches a running application through the documented sequence, with no manual server, role, database, or directory creation.
- The development database reports the same PostgreSQL version and zhparser build as `integrationTest`, from one image definition and one tag.
- `.env.example` contains no credential that grants access beyond the local machine, and `.env` and the default data directory remain ignored and rejected by repository checks.
- The documented reset destroys database, data directory, and workspace repositories, and repeating the first-run sequence afterwards produces a working application.
- Unit tests and `repoCheck` still run without Docker.
- The development Compose project defines no application service and is not referenced by any deployment artifact.

## Risks

Docker becomes necessary for ordinary development rather than only for `integrationTest`. The baseline's guarantee that unit tests, integration-test compilation, and `repoCheck` run without it must survive, or contributors without Docker lose the whole loop instead of one suite.

A default data directory inside the working tree is one ignore-rule mistake away from entering history, and it holds workspace content rather than build output. Ignore rules and repository checks are the guard, and both must name it explicitly.

The Compose project and the build can disagree about the image tag once they name it separately. Whichever mechanism ends up owning the tag, the other must read it rather than repeat it.
