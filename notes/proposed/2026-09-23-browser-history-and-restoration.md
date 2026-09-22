# Browser History and Restoration

Date: 2026-09-23
Status: Proposed

## Problem

Full readers can request an exact historical file and inspect Git through the
execution copy, but the browser editor has no revision list or comparison.
Recovering an earlier text therefore requires a separate Git workflow. Restoring
text must preserve the existing remote-main authority and optimistic write checks.

## Decision

Add a history panel to the browser text editor. History belongs to a literal
repository path; the panel states that it does not follow renames. A full reader
can browse changes along remote main's first-parent history, select an earlier
revision, and compare its exact source with the current editor text. Merge commits
represent their resulting main-tree change against their first parent. Deletions
remain visible but do not become empty-text restoration candidates.

The history API requires current `READ_PRIVATE` before the repository read and
again before returning data. Public readers, public-scoped members and site
administrators without workspace membership cannot read historical metadata or
bytes. The content module owns bounded Git traversal; the web controller delegates
through the authorized reader. No public or MCP history endpoint is added.

Pages pin a remote-main commit. A continuation stays within that reachable commit's
history; rewritten or unreachable selections fail. Limit both commits inspected
and entries returned per page, with a continuation that advances even across long
runs of unrelated commits. Return commit ID, bounded subject and author display
name, commit time, and file presence; do not expose author email. Existing file
reads supply exact historical UTF-8 bytes and preserve binary, size, symlink and
managed-media rejection semantics.

Comparison renders escaped source, with bounded line-difference work and an
explicit fallback to side-by-side source when the comparison budget is exceeded.
No historical source is interpreted as raw HTML. A slow or late history request
cannot replace a different file, workspace or selected revision.

Restoration copies the selected historical source into the current editor after
confirmation when it replaces unsaved text. It retains the current file's commit
and revision/absence precondition. The ordinary preview and save flow then creates
a new Git commit; it never resets a branch, force-pushes, deletes history, or saves
automatically. A stale current baseline still conflicts. Current write and publish
permissions determine whether restoration and saving are available, independently
of history-read permission. Local recovery continues to retain unsaved editor text.

## Alternatives and consequences

- A dedicated server-side restore mutation would duplicate the existing patch
  authorization and conflict path. Reusing the editor gives the author a preview
  and keeps one persistence mechanism.
- Rewinding remote main would erase intervening changes and affect unrelated
  files. It is outside this feature.
- Rename following and repository-wide history browsing require separate path
  identity and discovery choices. This feature exposes the selected path only.
- A persistent history index would create another projection of Git authority.
  Bounded on-demand traversal is sufficient for the initial browser entrance.

## Verification

Exercise a real Git repository with additions, edits, unrelated commits, a merge,
deletion and recreation; verify bounded pagination and remote-history validation.
Direct HTTP coverage must reject public-only and unrelated principals and recheck
revocation. Mounted editor tests cover late responses, unsaved replacement,
comparison bounds and preserving current write preconditions. Browser acceptance
must show history, comparison, explicit restoration and a new saved commit while
retaining the earlier history; a concurrent write must refuse overwrite.

## Same-topic audit

[Repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md)
retains arbitrary-path reads and revision-checked writes.
[CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md)
retains full-reader history and excludes it from public projections.
[Authoring and discovery](../implemented/2026-09-23-authoring-and-discovery-experience.md)
retains unsaved local recovery; this feature recovers committed source.
[Off-host backup and restore](2026-08-27-off-host-backup-and-restore.md) remains a
separate proposal; choosing an older text version supplies no backup service.
