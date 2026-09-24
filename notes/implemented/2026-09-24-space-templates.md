# Space templates

Date: 2026-09-24

## Problem

Every new space received the same four files from [content-template](../../content-template/AGENTS.md):
- a root `AGENTS.md`;
- guides for `private/` and `public/`;
- a disabled `.poketto/publishing.yaml`.

A member who connects an agent over MCP then had to teach it how this particular space is organised. The purpose of a space is usually known: a journal, reading notes, photo albums or a news digest. The repository can carry those conventions itself.

## Decision

**Sets.** [RepositoryInitialization.Template](../../src/main/java/io/github/core607/poketto/content/RepositoryInitialization.java) names five sets:
- `general` adds nothing beyond the base files;
- `journal`, `reading-notes`, `albums` and `digest` each add one folder pair under `private/` and `public/`, each folder with its own `AGENTS.md`.

The files ship under `content-template/sets/<name>/` beside the unchanged base. The guides fix, for agents and people alike:
- file names and frontmatter;
- structure and citation rules;
- how to publish, including `publish_at` where a release date is natural.

**Application.** Owners add a set from the space's **Storage** section, where base initialization already lives.
- `GET /api/auth/workspaces/{id}/repository-initialization?template=<name>` lists the base and set files still missing.
- `POST` with `{ "template": "<name>" }` adds them in one commit.
- Without a template, both requests behave exactly as before.

Following the [initialization rules](2026-09-16-repository-initialization-on-connection.md), a set write:
- is create-only: an existing file at a set path keeps its bytes;
- never moves anything;
- leaves publication disabled;
- requires owner authority.

Because nothing is overwritten, a set can be added to a space that already has content, not only to an empty one.

**Creation flows.** Token-based and GitHub-authorized creation still write the base files only; the owner adds a set afterwards.

**Packaging.** The build copies `content-template/` into application resources, sets included, and the shipped files must equal the declared base and set files.

## Alternatives

- **Choosing the set in the creation request.** GitHub-authorized creation completes across requests, so the choice would need persisting in both creation attempt tables, and creation would need a second failure mode. Adding a set afterwards reuses the initialization path and its authority checks.
- **Duplicating the base files in every set.** Five copies of the base guides would drift. Sets only carry their own folders.
- **Templates in a separate repository or downloaded at runtime.** This adds a network dependency and a second source of truth, and a template could change without the deployed documentation.
- **Owner-uploaded arbitrary templates.** They need validation of arbitrary paths and sizes and a management interface. The named sets cover the stated purposes first.

## Consequences

- Guide text is product surface. A wrong convention repeats in every space that adds the set, so set guides get the same review as documentation.
- Set guides are written in English like the base guides; agents follow them regardless of the space's language.
- An owner who already customised a set path keeps their file. The set then adds only its other files.

## Verification

[ContentRepositoryInitializerTests](../../src/test/java/io/github/core607/poketto/content/internal/ContentRepositoryInitializerTests.java) pins create-only set application against a real Git fixture and the shipped files against `content-template/`; [tests/repository-connection.test.tsx](../../frontend/tests/repository-connection.test.tsx) pins the template parameter in the Storage section's requests.
