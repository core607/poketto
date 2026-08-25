# Poketto

A self-hosted personal knowledge base whose public face is a blog. The same Markdown content serves public publishing and, over MCP, the long-term memory of trusted AI agents.

[中文说明](README.zh.md)

## Status

Preparation. Requirements and architecture are settled ([requirements](notes/implemented/2026-08-25-requirements-and-architecture.md)); development has not started; there is no runnable code yet.

## Who this is for

- People who want their notes, clippings, and blog to be one set of Markdown files in a git repository, with the database reduced to a rebuildable search projection.
- People who want their AI assistants to read and write that content over MCP with scoped API keys, instead of handing out shell access.
- People who run things on one small server and prefer fewer components over more.
- Anyone curious about a repository designed to be developed by agents — start with [AGENTS.md](AGENTS.md).

## Layout

```
AGENTS.md            Rules for agents working in this repository (start here)
.agents/skills/      Reusable workflows: prose, review, checks, notes lifecycle
notes/               Decision records: proposed / implemented / rejected / archived
```

## License

Code and project documents: [Apache-2.0](LICENSE).
Artwork and published creative content: [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
