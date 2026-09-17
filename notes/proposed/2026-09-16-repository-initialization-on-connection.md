# Repository Initialization on Connection

Date: 2026-09-16
Status: Proposed

## Problem

A space connects an existing GitHub or CNB repository. [CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md) defines the content layout that the [content template](../../content-template/AGENTS.md) provides, and [usage](../../docs/usage.md) tells the operator to commit that template into an empty repository by hand. The person creating a space is an account owner, not necessarily the operator, and nothing in the creation flow performs or even mentions that step. Two failures follow.

An empty repository has no commit. A snapshot export requires one, so the first `repo_exec` for the space fails before a lease is opened, and the failure reaches the client as the fixed `UNAVAILABLE` text for a missing repository authority. The client cannot tell an empty repository from an outage, and the service records only the outcome code.

A repository that already holds content but no guides is readable and writable, because every path outside `public/` is private, but an agent entering it finds no `AGENTS.md`. The `repo_exec` description tells the agent to read the root guide first; there is none to read, and the agent has no authority to decide the repository's conventions on the owner's behalf.

## Proposal

Initialization is one create-only change: the template files that are absent — `AGENTS.md`, `private/AGENTS.md`, `public/AGENTS.md` and `.poketto/publishing.yaml` with publication disabled — are added through the ordinary authority write path with the current `main` as base, or as the root commit when `main` does not exist. Files that exist are never overwritten, existing content is never moved, and publication stays disabled until the owner enables it. The root guide states that content outside `private/` and `public/` stays where it is and is private, so an existing layout keeps working; the owner or an agent acting on the owner's request may refine the guides afterwards.

When creation verifies the repository, the server classifies it:

- No commit on `main`: creation commits the template as the root commit before reporting `READY`. The creation form says so; there is nothing in the repository that consent could protect.
- A commit without all four files: creation reports `READY` without changing the repository and lists the files initialization would add. The space's repository connection view shows that list with the guarantee that nothing existing is modified or moved, and an explicit action applies the change. Space owners can apply it later for a space that was connected before this record.
- All four files present: nothing is offered.

Until a repository has a commit, `repo_exec` and the other copy-opening tools answer `REPOSITORY_EMPTY` with a message that names the space's repository connection as the place to initialize it. The authority-unavailable text is kept for authority failures only, and the tool layer records the cause of every failure it maps to a client code, with its stack trace, so a refusal can be diagnosed from the journal.

## Alternatives

- Keep initialization a documented operator step. Rejected: the owner creating a space is not the operator, and an agent cannot enter an empty repository to do it later.
- Let the agent initialize through a CLI command in the sandbox. Rejected: an empty repository cannot open a copy, and a first connection must not depend on an agent choosing a layout for someone else's content.
- Initialize a nonempty repository automatically. Rejected: even an additive commit to a repository the owner has just connected needs the owner's explicit choice; the empty case has nothing to protect.
- Move existing content into `private/`. Rejected: paths outside `public/` are already private, and moving another author's files is not the service's decision.

## Consequences and risks

- The application authors a commit on the owner's remote at creation time; the credential already needs write access for saves, so no new permission is required. Writing `.poketto/publishing.yaml` requires the publication capability, which the creator holds as owner.
- A concurrent push between verification and the initialization commit surfaces as a conflict; the status keeps offering initialization until it applies.
- The creation form and the repository connection view gain wording and an action, so the change needs interface evidence from a real run.
- The template's root guide gains one sentence about existing content; the shipped template remains the single source of the initialization files.

## Acceptance

- Creating a space on an empty remote yields a root commit with the four files and publication disabled; `repo_exec` then opens a copy whose root holds `AGENTS.md`.
- Creating a space on a remote with content and no guides reports `READY` and the initialization list; applying it adds only the absent files, leaves every existing file and its history unchanged, and a second application reports nothing to add.
- A remote that already carries an `AGENTS.md` keeps it byte for byte; only the absent files are added.
- `repo_exec` on an uninitialized empty repository returns `REPOSITORY_EMPTY`, not `UNAVAILABLE`, and the journal names the reason.
- Interface evidence covers the creation wording and the consent action; `docs/usage.md` and its Chinese counterpart describe initialization as the service's behavior instead of an operator step.

## Same-topic audit

[Multi-user workspaces and discovery](../implemented/2026-09-11-multiuser-workspaces-and-discovery.md) owns space creation and is retained; it excludes automatic remote creation, which this record does not change. [CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md) owns the layout the template provides and is retained; its statement that the template initializes both roots stays true, and the implementer updates the manual step in [usage](../../docs/usage.md). [Service diagnostics](../implemented/2026-09-14-service-diagnostics.md) is retained; recording the cause of a mapped tool failure completes the gap it names for refusals.
