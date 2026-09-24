# Space templates

Date: 2026-09-24
Status: Proposed

## Problem

Every new space receives the same four files from [content-template](../../content-template/AGENTS.md):
- a root `AGENTS.md`;
- guides for `private/` and `public/`;
- a disabled `.poketto/publishing.yaml`.

A member who connects an agent over MCP must then teach it how this particular space is organised. The purpose of a space is usually known at creation: a weekly journal, reading notes, photo albums or a news digest. The repository could carry those conventions from its first commit.

## Proposal

**Template sets.**
- `content-template/` becomes a directory of named sets:
  - `general`, the current four files;
  - `journal`;
  - `reading-notes`;
  - `albums`;
  - `digest`.
- Every set contains the four base paths, so publication starts disabled and the private and public roots exist.
- A set may add folders with a guide and example files. For example, `journal` has `private/weekly/`, whose `AGENTS.md` fixes file names, frontmatter (`title`, `tags`, `id`) and the move to `public/` when publishing.
- Guides are written for agents and people alike, in the space's language.

**Initialization.**
- `RepositoryInitialization` lists the files of the chosen set instead of four fixed paths.
- The [initialization rules](../implemented/2026-09-16-repository-initialization-on-connection.md) are unchanged: create-only, never overwriting or moving, publication off, owner authority required.
- Both creation requests, token-based and GitHub-authorized, accept an optional `template` name, defaulting to `general`. An unknown name is rejected before any repository work.
- A GitHub-authorized space still becomes READY only after every file of its set exists.
- The **Storage** view's initialization offers the same choice for an empty repository. A non-empty repository keeps today's behaviour: only missing base guides can be added.

**Interface.** The creation forms show the sets as cards, each with a one-line purpose and the folders it adds. `general` is preselected.

**Packaging.** The build keeps copying `content-template/` into application resources and fails when a set lacks a base path. A unit test compares each set's file list with the directory, replacing today's four-path check.

## Alternatives

- **Templates in a separate repository or downloaded at creation.** This adds a network dependency and a second source of truth, and a template could change without the deployed documentation.
- **A single template plus agent-side instructions.** The agent's host would carry conventions that belong to the space and would be lost when another agent connects.
- **Letting owners upload arbitrary templates.** It needs validation of arbitrary paths and sizes and a management interface; the named sets cover the stated purposes first.

## Consequences and risks

- Guide text becomes product surface. A wrong convention in a template repeats in every space created from it, so each guide needs review like documentation.
- Examples under `private/` are real files in the owner's repository. They carry no personal data and can be deleted freely.

## Verification plan

- A unit test covers the file list of every set and checks that each contains the four base paths.
- An integration test for each creation path confirms that the chosen set's files exist in the first commit and that an unknown name is refused.
- The frontend test covers the template picker's request body.
