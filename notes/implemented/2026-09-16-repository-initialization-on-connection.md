# Repository Initialization on Connection

Date: 2026-09-16
Status: Implemented

## Problem

A space connects an existing GitHub or CNB repository. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) defines the content layout that the [content template](../../content-template/AGENTS.md) provides, and [usage](../../docs/usage.md) used to tell the operator to commit that template into an empty repository by hand. The person creating a space is an account owner, not necessarily the operator, and nothing in the creation flow performed or even mentioned that step. Two failures followed.

An empty repository has no commit. A snapshot export requires one, so the first `repo_exec` for the space failed before a lease was opened, and the failure reached the client as the fixed `UNAVAILABLE` text for a missing repository authority. The client could not tell an empty repository from an outage, and the service recorded only the outcome code.

A repository that already holds content but no guides is readable and writable, because every path outside `public/` is private, but an agent entering it finds no `AGENTS.md`. The `repo_exec` description tells the agent to read the root guide first; there is none to read, and the agent has no authority to decide the repository's conventions on the owner's behalf.

## Decision

Initialization is one create-only change: the template files that are absent among `AGENTS.md`, `private/AGENTS.md`, `public/AGENTS.md` and `.poketto/publishing.yaml` (publication disabled) are added through the ordinary authority write path with the inspected `main` commit as base, or as the root commit when `main` does not exist. Files that exist are never overwritten, existing content is never moved, and publication stays disabled until the owner enables it. The root guide states that content outside `private/` and `public/` stays where it is and is private, so an existing layout keeps working; the owner or an agent acting on the owner's request may refine the guides afterwards. The template ships inside the application (`processResources` packages `content-template/`), so the service adds the same files the documentation describes.

Connection verification already lists the remote's refs; it now reports whether the repository has any branch. Creation acts on that classification:

- No branch: once the creation transaction has committed the workspace, its owner and the binding, the service commits the template as the root commit, as the new owner, before answering `READY`. A failure here leaves the space ready and the repository empty, recorded by exception type in the journal; the repository connection view then offers the same change.
- A branch: creation changes nothing and reads nothing from the repository. The space's repository connection view reads the four paths at one commit, lists the absent ones with the guarantee that nothing existing is modified or moved, and an explicit owner action applies the change. The same view serves a space connected before this record and a deployment-managed repository.
- All four files present: the view says so and offers nothing.

The proposal had creation list the absent files of a nonempty repository in its own response. That would read the repository during creation and would need the list persisted for status polling; the view computes it on demand instead, which is where the action is.

Until a repository has a commit, `repo_exec` and the public projection answer `REPOSITORY_EMPTY` with a message that names the space's repository connection view. The authority-unavailable text is kept for authority failures only. Per [service diagnostics](2026-09-14-service-diagnostics.md), the tool boundary records a failure it maps to `UNAVAILABLE` by its exception type chain, never by its message or stack, which can name private material.

## Alternatives

- Keep initialization a documented operator step. Rejected: the owner creating a space is not the operator, and an agent cannot enter an empty repository to do it later.
- Let the agent initialize through a CLI command in the sandbox. Rejected: an empty repository cannot open a copy, and a first connection must not depend on an agent choosing a layout for someone else's content.
- Initialize a nonempty repository automatically. Rejected: even an additive commit to a repository the owner has just connected needs the owner's explicit choice; the empty case has nothing to protect.
- Move existing content into `private/`. Rejected: paths outside `public/` are already private, and moving another author's files is not the service's decision.

## Consequences and risks

- The application authors a commit on the owner's remote at creation time; the credential already needs write access for saves, so no new permission is required. Writing `.poketto/publishing.yaml` requires the publication capability, which the creator holds as owner; the view's action requires the owner capability that also guards credential rotation.
- A push between inspection and the initialization commit surfaces as a conflict; the view keeps offering initialization until it applies.
- Initialization inspects each of the four paths through the authority, one fetch per path; the cost is a few round trips against a cached repository and is paid only when the view is opened or the action taken.
- Generations of the executor that saw an empty repository as an unavailable authority are replaced; nothing else observes the new exception type.

## Implementation and acceptance

`RepositoryInitialization` (content API) with `ContentRepositoryInitializer`; `RepositoryEmptyException` thrown by the snapshot exports and passed through executor admission; `REPOSITORY_EMPTY` in the MCP tool boundary; `RepositoryConnections.Verified.emptyRepository`; `SpaceCreationService.complete` initializes after the transaction; `GET`/`POST /api/auth/workspaces/{workspaceId}/repository-initialization`; the creation form wording and the initialization section of the repository connection view.

- `ContentRepositoryInitializerTests`: an empty remote yields a root commit with the four files and publication disabled, and a second application adds nothing; a remote with its own `AGENTS.md` and other content keeps every byte and gains only the absent files on top of its head; owner authorization precedes any read; the shipped template equals `content-template/`.
- `RepositorySnapshotExportsTests` and `McpEmptyRepositoryTests`: an empty repository is `RepositoryEmptyException` for the full and the public export, and `REPOSITORY_EMPTY` at the tool boundary.
- `SpaceCreationIntegrationIT`: initialization runs once, after the transaction, as the owner, only for an empty repository, and its failure leaves the space `READY`. `SpaceCreationHttpIntegrationIT`: creation on the empty fixture remote leaves a root commit with the four files, and the initialization routes answer the owner and refuse an outsider.
- `frontend/tests/repository-connection.test.tsx`: the view lists the absent files, applies only on the explicit action, reports the commit and re-reads the status.
- Interface evidence from the acceptance harness is attached to the pull request.

## Same-topic audit

[Multi-user workspaces and discovery](2026-09-11-multiuser-workspaces-and-discovery.md) owns space creation and is retained; it excludes automatic remote creation, which this record does not change. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns the layout the template provides and is retained; its statement that the template initializes both roots stays true, and [usage](../../docs/usage.md) now describes initialization as the service's behavior instead of an operator step. [Service diagnostics](2026-09-14-service-diagnostics.md) is retained; the type-chain record of a mapped tool failure completes the gap it names for refusals.
