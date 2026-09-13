# Public Author Names

Date: 2026-09-14

## Problem

The [multi-user discovery contract](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md) requires author display names on public reading cards. A login identifier, account address or Git commit identity is not an explicit public signature. Folder landings also need an authored name for albums and collections.

## Decision

Use a nonblank Markdown frontmatter `public_author` string first. Otherwise use the workspace's public signature. A human workspace owner can set that signature; an unset signature follows the already-public workspace display name. This rule applies to articles, folder landings, discovery cards and public search summaries.

Both signature forms are plain text, trimmed and bounded to 120 Unicode code points. Missing or blank article signatures select the workspace fallback. Invalid `public_author` metadata follows the existing structured-document diagnostic path. Reading never rewrites source Markdown, and unrelated edits preserve authored bytes.

The application stores the workspace signature with workspace website settings and protects updates with current human-owner authorization and session CSRF checks. An account login, email, token holder or Git author is never an implicit source. Existing arbitrary metadata, including `author`, remains private; only the newly documented `public_author` field opts into public display. Public responses expose the chosen display text rather than private account identifiers. The frontend renders it as escaped text.

Retained discovery data stores only the approved article signature; an absent article signature resolves against current public workspace settings when the card is emitted. Account and website permission checks remain independent of text selection. Retained signature text is included in existing metadata budgets. Existing publication withdrawal continues to deny cards and pages.

Public execution projections and public portable exports preserve the explicit article signature. They never copy a workspace fallback into Markdown or expose arbitrary original frontmatter.

## Alternatives and acceptance

A workspace-only signature cannot represent individually authored articles in shared spaces. Reusing commit authors or account names would publish information that was not selected for public display. Copying fallback names into Markdown would modify content and leave stale copies after a workspace rename.

Real repository and database integration must verify article precedence, blank fallback, owner-only updates, independent spaces, current fallback after a signature change, private-field exclusion and source preservation. The running frontend must show an authored article and an unsigned album using different correct names, and allow the owner to change the public signature. Existing website withdrawal remains enforced.

The same-topic audit retains the parent proposal, [website delivery](2026-09-14-workspace-public-delivery.md), [collection reading](2026-09-14-collection-reading.md) and [authoring foundations](2026-09-05-repository-authoring-foundations.md). Their identity, source and public-access rules remain applicable. Thumbnail delivery and search-return behavior remain separate outstanding requirements.
