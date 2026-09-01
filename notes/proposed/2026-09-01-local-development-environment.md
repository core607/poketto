# Local Development Environment

Date: 2026-09-01
Status: Proposed

## Problem

The [development baseline](../implemented/2026-08-26-development-baseline.md) established the build, module enforcement, test suites, repository checks, and continuous integration. It did not establish a way to run the application.

Starting the application requires a reachable PostgreSQL data source and an absolute `POKETTO_DATA_DIR`. Neither exists in a fresh clone. The only database definition in the repository, `infra/postgres`, is a custom image built by `buildPostgresTestImage` and consumed through Testcontainers, so it serves `integrationTest` and nothing else. A contributor who wants to run the application assembles a server, a role, a database, and a data directory from one README paragraph, and an agent arriving from a task brief has no command to run at all.

That custom image is also leaving. [Repository-native retrieval](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) removes PostgreSQL content search, zhparser, and the custom database image from application code, schema, tests, CI, and deployment. [Continuous delivery](2026-08-26-continuous-delivery.md) already pins the official PostgreSQL 17 image for production for exactly that reason. A development environment built on the custom image would be built on a component two proposals schedule for deletion, and would need redesigning as soon as either lands.

The application is therefore unrunnable now, and the obvious way to make it runnable points at the wrong database image.

## Proposal

### The official PostgreSQL image, from the start

Development runs the official PostgreSQL 17 image, pinned by digest, which is the image production pins. A Compose project owned by development starts it with a development role, database, and named volume.

This differs from `integrationTest`, which still builds the custom zhparser image, and that divergence is safe to accept because nothing can observe it. No application code depends on zhparser: the `projection` and `search` packages contain only their package declarations, the single migration creates the workspace catalog, and the only consumer of the extension anywhere in the tree is one integration test asserting that the extension is installed. Chinese tokenization is not a behavior an application developer can currently reach, correctly or incorrectly.

The divergence ends when repository-native retrieval removes the custom image from `integrationTest`. Development needs no change at that point, because it is already on the image everything else converges to. Should a later decision reinstate a database-side tokenizer, development and tests converge on whatever image that decision selects; that is a new decision rather than a reason to aim this one at a deprecated image.

### A Compose project without an application service

The Compose project contains no application service. The application runs from an IDE or `bootRun` against the database, which is what makes a debugger, an incremental rebuild, and a restart in seconds useful. Production topology belongs to continuous delivery, and a development file that also described the application would become a second, competing deployment definition.

### Configuration and the data directory

A committed `.env.example` lists every variable the application requires, with values that work on a developer machine and grant access to nothing else. The ignore rules already admit that file and exclude `.env`.

`POKETTO_DATA_DIR` defaults to an ignored path inside the working tree. One obvious location makes workspace repositories easy to inspect while debugging and makes destroying them safe. The repository checks that forbid committed credentials extend to that path, so a workspace repository cannot enter history by accident.

### Commands and the first run

The database lifecycle is reachable from the command surface AGENTS.md already documents rather than from a separate guide. A clean clone reaches a running application through one documented sequence: start the database, copy the example configuration, run the application.

### Disposability

The phase clause permits destructive rebuilds, so local recovery is deletion rather than migration. The documented reset destroys the database volume, the data directory, and the workspace repositories together, because a data directory that outlives its database describes workspaces the catalog no longer holds.

## Implementation scope and dependencies

This proposal depends on the implemented [development baseline](../implemented/2026-08-26-development-baseline.md) and the implemented [workspace and tenant boundary](../implemented/2026-08-27-workspace-tenancy.md), whose data directory layout it exposes.

It is deliberately sequenced ahead of [repository-native retrieval](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) rather than behind it. The environment is needed before that implementation starts, and selecting the official image is what lets this decision survive it unchanged.

The first implementation includes the development Compose project, `.env.example`, the lifecycle and reset commands, ignore and repository-check coverage for the default data directory, the command-table and README entries, and a rehearsal from a clean clone.

It excludes the production Compose project and deployment entrance, the removal of the custom image and `buildPostgresTestImage` from the build, TLS, reverse proxies, seeded demonstration content, and any change to what `check` verifies.

## Alternatives considered

**Share the custom zhparser image with `integrationTest`.** Development and tests would run bit-identical databases today, and a tokenization defect would reproduce locally exactly as in CI. The benefit protects behavior no application code has, and the cost is a development environment resting on an image that two proposals remove, so it would need redesigning within one implementation cycle.

**Wait for repository-native retrieval before defining a development environment.** There would be no divergence at any point and one image forever. It leaves the application unrunnable for the whole of that implementation, which is the cost this proposal exists to remove, and that implementation is large enough that the wait is not short.

**Start PostgreSQL through Testcontainers for `bootRun` as well.** No Compose file, and the database would always match whatever the tests use. The container and its data would not survive the process, so every run would lose local content and pay container startup, which defeats keeping a corpus to develop against.

**Document the steps in the README without automation.** Prose costs nothing to add. It cannot be executed or verified, so it drifts from the build silently and a clean-clone rehearsal has nothing to fail against.

**Ship a devcontainer.** It would fix the JDK, Gradle, and Docker toolchain as well as the database. It binds the repository to one editor ecosystem and adds an image to maintain, which needs evidence of a real toolchain problem first.

**Extend the production Compose project with a development override.** One file would describe both. Production and development want opposite defaults for exposure, credentials, and data lifetime, so a shared file puts a development convenience one edit away from production.

## Acceptance

- A clean clone reaches a running application through the documented sequence, with no manual server, role, database, or directory creation.
- The development database is the official PostgreSQL 17 image at the digest production pins, and the development Compose project references no custom image.
- No application code path requires zhparser. The only test that does keeps its own image until repository-native retrieval removes it, and that removal requires no change to the development environment.
- `.env.example` contains no credential that grants access beyond the local machine, and `.env` and the default data directory remain ignored and rejected by repository checks.
- The documented reset destroys database, data directory, and workspace repositories, and repeating the first-run sequence afterwards produces a working application.
- Unit tests and `repoCheck` still run without Docker.
- The development Compose project defines no application service and is not referenced by any deployment artifact.

## Risks

Development and `integrationTest` run different database images until repository-native retrieval lands. A defect depending on the installed extension set would reproduce differently in the two places. No such defect can exist today, because no application code reaches the extension, but the window is real and closes only when that proposal is implemented; until then, a change that starts to depend on a database extension must say so and reopen this decision.

Docker becomes necessary for ordinary development rather than only for `integrationTest`. The baseline's guarantee that unit tests, integration-test compilation, and `repoCheck` run without it must survive, or contributors without Docker lose the whole loop instead of one suite.

A default data directory inside the working tree is one ignore-rule mistake away from entering history, and it holds workspace content rather than build output. Ignore rules and repository checks are the guard, and both must name it explicitly.

The Compose project and the deployment artifacts name the same official image digest separately. Whichever mechanism ends up owning that digest, the other must read it rather than repeat it, or the two drift into different PostgreSQL builds.
