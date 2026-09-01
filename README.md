# Poketto

A repository-native personal knowledge service whose public face is a blog. Each workspace keeps its Markdown history in Git; the same content serves public publishing and, over MCP, the long-term memory of trusted AI agents. Poketto targets one cloud server first. A proposed [optional serverless profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md) is intended to run the same application artifacts with configured external services.

[中文说明](README.zh.md)

## Status

Development. The executable baseline, workspace isolation, content repository foundation, and document writes are implemented. The current baseline runs on one cloud server with local workspace repositories. [Local blob storage](notes/proposed/2026-09-01-local-content-addressed-blob-storage.md), [consumer accounts and personal workspaces](notes/proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md), [repository-native retrieval](notes/proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), rendering, and MCP entry points remain proposed. Serverless deployment also remains optional and waits for real external infrastructure. The [requirements](notes/implemented/2026-08-25-requirements-and-architecture.md) record the implemented baseline and existing product contracts; proposals identify the target decisions that have not shipped.

## Who this is for

- People who want their notes, clippings, and blog to remain Markdown with Git history while relational application state stays outside the content repository.
- People who want their AI assistants to read and write that content over MCP with scoped API keys, instead of handing out shell access.
- People who want a small-server deployment to remain a first-class production path without closing the path to isolated workspaces in an optional shared service.
- Anyone curious about a repository designed to be developed by agents — start with [AGENTS.md](AGENTS.md).

## Layout

```
AGENTS.md            Rules for agents working in this repository (start here)
.agents/skills/      Reusable workflows: prose, review, checks, notes lifecycle
build.gradle.kts     Gradle build and verification entry points
src/                 Application modules and their tests
infra/postgres/      Reproducible PostgreSQL 17 + zhparser test image
notes/               Decision records: proposed / implemented / rejected / archived
```

## Development

Use Java 26 and the checked-in Gradle Wrapper. Docker is required for database integration tests and the complete check; the faster unit and repository checks do not require it.

Application startup requires a PostgreSQL data source and an absolute `POKETTO_DATA_DIR`. Set `SPRING_DATASOURCE_URL`, any database credentials, and the data directory before `bootRun`. Flyway creates the workspace catalog; the application creates one durable default workspace and an unborn `main` content repository below `<data-dir>/workspaces/<workspace-id>/content` on first start.

```sh
./gradlew test repoCheck
./gradlew check
POKETTO_DATA_DIR=/srv/poketto ./gradlew bootRun
```

On Windows, set `$env:POKETTO_DATA_DIR` to an absolute path and use `.\gradlew.bat`. See [AGENTS.md](AGENTS.md#commands) for the command table and contribution rules.

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
