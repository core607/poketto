# Poketto

A self-hosted personal knowledge base whose public face is a blog. The same Markdown content serves public publishing and, over MCP, the long-term memory of trusted AI agents.

[中文说明](README.zh.md)

## Status

Development. Requirements and architecture are settled ([requirements](notes/implemented/2026-08-25-requirements-and-architecture.md)), and the executable baseline is in place. Product features have not been implemented yet.

## Who this is for

- People who want their notes, clippings, and blog to be one set of Markdown files in a git repository, with the database reduced to a rebuildable search projection.
- People who want their AI assistants to read and write that content over MCP with scoped API keys, instead of handing out shell access.
- People who run things on one small server and prefer fewer components over more.
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

```sh
./gradlew test repoCheck
./gradlew check
./gradlew bootRun
```

On Windows, use `.\gradlew.bat`. See [AGENTS.md](AGENTS.md#commands) for the command table and contribution rules.

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
