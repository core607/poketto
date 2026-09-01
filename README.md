# Poketto

A repository-native personal knowledge service whose public face is a blog. Its accepted target keeps each workspace's Markdown, repository-managed images, and history in remote Git while storing images uploaded through Poketto in ManagedBlobStore. The two image types share safe rendering but never synchronize. The same content serves public publishing and, over MCP, the long-term memory of trusted AI agents. Poketto targets one cloud server first while keeping an [optional serverless profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md) behind configuration-selected infrastructure.

[中文说明](README.zh.md)

## Status

Development. The executable baseline, workspace isolation, content repository foundation, and document writes are implemented. The current self-hosted baseline uses local workspace repositories. The accepted target keeps the single-server deployment primary but gives every production workspace [remote Git repository authority](notes/proposed/2026-09-01-remote-repository-authority.md), recognizes [repository-native Markdown and read-only sibling-image galleries](notes/proposed/2026-09-01-repository-native-publishing-and-assets.md), and stores Poketto uploads in an authoritative [local ManagedBlobStore while treating repository-image copies as disposable](notes/proposed/2026-09-01-repository-asset-blob-store.md). [Consumer accounts](notes/proposed/2026-09-01-consumer-accounts-and-personal-workspaces.md), [repository-native retrieval](notes/proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md), rendering, and MCP entry points remain proposed. Serverless stays optional and waits for real OSS, shared-database, and remote-SRT infrastructure. The [requirements](notes/implemented/2026-08-25-requirements-and-architecture.md) record the implemented baseline; proposals identify target decisions that have not shipped.

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
