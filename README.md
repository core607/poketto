# Poketto

A personal knowledge workspace for you and your AI agents.

Keep notes, clippings, reading lists and project knowledge in files with Git history. Work on them in a browser or let an AI agent search, edit and organize them through MCP. Publish the content you choose as a blog.

[中文说明](README.zh.md) · [Development and operations](docs/usage.md) · [Architecture](notes/implemented/2026-08-25-requirements-and-architecture.md)

## File as truth

Markdown, directory structure and media references live in a remote Git repository. Its `main` branch is authoritative; Poketto keeps a disposable local cache and serves public pages from verified snapshots. Browser edits and agent saves use the same atomic Git writer, with revision checks to preserve concurrent work.

Uploaded images, audio, video, PDFs and other files live as immutable local originals. Git stores their logical paths and versions in `.poketto/assets.json`; documents use relative links. Originals are deduplicated within each workspace. Equal bytes in different workspaces retain separate storage and identities, and uploading a file does not publish it.

Content stays inspectable with ordinary file and Git tools. PostgreSQL holds accounts, permissions and other relational application state.

## CodeAct over MCP

Connect an MCP client to Poketto and give it a scoped API key. File access requires an enabled executor and the `EXECUTE_REPOSITORY` capability. `repo_exec` provides an isolated workspace with shell, Python and Git. The agent can inspect the directory tree, follow repository-owned `AGENTS.md` files, search existing material and edit files in place.

The host-mediated `poketto` CLI provides persistence and result delivery:

| Command | Role |
|---|---|
| `poketto status` | Inspect the session scope, save baseline and pending write outcome |
| `poketto save` | Commit selected files and explicit deletions, retaining other local edits |
| `poketto sync` | Reconcile one file against its own baseline and current remote content |
| `poketto recover` | Reconcile a pending save or move using its original commit and completion receipt |
| `poketto move` | Move saved files, folders and indexed media with Markdown reference repair |
| `poketto media list` / `import` / `fetch` | Discover indexed media, store originals or materialize referenced files |
| `poketto artifact create` | Retain a temporary result for image, text or binary delivery through MCP |

Repository credentials and original storage remain outside the sandbox. Ordinary edits stay in the execution session until saved. Conflicts retain local work; an uncertain acknowledgement must be reconciled before another save. Unsaved session files can be discarded on expiry or restart.

This supports tasks such as updating a listening list, maintaining research notes, or organizing an article with its images. The agent discovers the workspace's organization from its files and guidance. The external agent harness supplies the model and decides how to carry out the task; Poketto supplies authorized data, execution and persistence.

## One workspace, several views

The browser provides a Markdown editor, file and directory navigation, image previews, and a destination picker for moves. File and folder moves repair supported Markdown references in the same commit. Public pages provide article routes, tags, search and image galleries over the content allowed by publication policy.

Execution receives the same permission boundary: full readers get authorized current files and original Git history; public-only readers get a fresh public projection with private metadata and original history excluded. Sessions are isolated even when clients share a key. Revocation and publication withdrawal invalidate affected execution sessions.

```mermaid
flowchart LR
    Browser[Browser editor] --> Service[Poketto]
    Agent[AI agent via MCP] --> Service
    Service <--> Sandbox[Isolated shell / Python / Git]
    Service <--> Repository[Remote Git: text, index, history]
    Service <--> Originals[Local originals per workspace]
    Service --> Blog[Public blog]
```

API capabilities govern reads, writes, publishing and execution. An agent granted both private-read and publish capabilities can publish private material; the external harness remains responsible for interpreting user intent and handling prompt injection.

## Development status

Poketto is under active development. Repository authoring, local media, browser moves and the CodeAct save/media workflow are implemented. [Client acceptance](acceptance/clients/README.md) records real Codex and Claude Code workflows in an isolated environment; [native executor verification](executor-native/README.md) exercises the Linux isolation boundary. Final HTTPS installation and deployed-topology acceptance remain open.

The [content plan](notes/proposed/2026-09-09-codeact-content-and-media.md) still includes default-private `public/` and `private/` roots, portable ZIP exports, content conversion and removal of redundant MCP file tools. The current publication policy and tool interfaces are documented in the [usage reference](docs/usage.md). Those planned changes are not available yet.

The primary deployment is a self-hosted Linux server. Hosted workspace provisioning, backups, visitor Q&A and the [optional serverless profile](notes/proposed/2026-09-01-optional-serverless-deployment-profile.md) are outside the current delivery scope.

## Develop and self-host

The application uses Java 26, Spring Boot, PostgreSQL 17 and a Next.js frontend. The separate Linux execution service runs the sandbox. Build with the checked-in Gradle Wrapper:

```sh
./gradlew test repoCheck
./gradlew check
```

The full check requires Docker, the pinned Node.js/npm runtime and the executor test prerequisites. On Windows use `.\gradlew.bat`. Application startup additionally requires PostgreSQL, an absolute data directory and a pre-provisioned private HTTPS Git repository.

- [Development and operations](docs/usage.md): runtime prerequisites, configuration, publishing, MCP and deployment.
- [Browser acceptance](acceptance/README.md): run the real application with disposable synthetic data.
- [Execution service](executor-service/README.md): installation, protocol, CLI, limits and lifecycle.
- [AGENTS.md](AGENTS.md): contribution rules and checks. Reusable agent workflows live in `.agents/skills/`; architecture decisions live in `notes/`.

Verified `main` commits publish application and frontend images. Automatic deployment is enabled separately by the operator; local originals require their own persistence and backup arrangements.

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
