# Poketto

**A Git-native content workspace for humans and agents.**

Access the same content repository through the browser and MCP. Agents use shell, Python and Git in isolated environments; Poketto controls authorization, persistence and publication.

[中文](README.zh.md) · [Live instance](https://poketto.top) · [Get started](docs/usage.md) · [Architecture](notes/implemented/2026-08-25-requirements-and-architecture.md)

## Project highlights

- CodeAct workflow: `repo_exec` and the workspace CLI expose content operations; agents compose tasks using general-purpose tools.
- Git as content authority: text, directories and media references are inspectable and versioned. Browser and agent writes share the same revision-checked path.
- Persistent copies, isolated commands: copies are shared by account, space and reading scope, with hard disk quotas. Commands run serially per copy under separate resource limits.
- Host-controlled writes: repository credentials stay outside the sandbox. The host handles selected saves, conflict checks and uncertain-result recovery.
- Separate public and private views: directories express publication state, publishing requires permission, and public readers receive an independent projection without private files or original history.
- Separate text and originals: Git records media paths and versions; independent storage holds immutable originals, referenced through relative paths.

## Direction

Build on multi-user content spaces toward SaaS hosting and agent content communities. Private spaces support ongoing authoring; public views support presentation, discovery and distribution.

## Get started

- Website: [poketto.top](https://poketto.top). Authoring and agent access require an account and space authorization; registration is currently invitation-based.
- MCP: `https://poketto.top/mcp`. Connect from an OAuth-capable MCP client, select a space and grant permissions.
- Usage and self-hosting: [setup, connections and command reference](docs/usage.md).

Deployment requires Linux, PostgreSQL, a private Git repository and a separate execution service. The stack uses Java, Spring Boot and Next.js. The project is under active development; interfaces and repository formats may change, and operators manage backups.

[Contributing](AGENTS.md) · [Client acceptance](acceptance/clients/README.md) · [Sandbox verification](executor-native/README.md) · [Delivery scope](notes/implemented/2026-09-15-multiuser-daily-use-acceptance.md)

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
