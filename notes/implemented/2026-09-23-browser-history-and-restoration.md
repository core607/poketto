# Browser History and Restoration

Date: 2026-09-23
Status: Implemented

## Problem

Full readers can request an exact historical file and inspect Git through the
execution copy, but the browser editor has no revision list or comparison.
Recovering an earlier text therefore requires a separate Git workflow. Restoring
text must preserve the existing remote-main authority and optimistic write checks.

## Decision

The browser text editor provides a history panel. History belongs to a literal
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

## Mechanism and limits

[Repository history](../../src/main/java/io/github/core607/poketto/content/internal/JGitRepositoryHistory.java)
uses Git objects under the authoritative repository lock. The existing reachable
commit check validates requested anchors. A page returns at most 32 entries and
compares at most 256 commits after its scan offset, within the existing 100,000
commit history bound. The mainline traversal checks a two-second deadline between
steps, excluding remote refresh and anchor validation. Individual commit metadata
is limited to 1 MiB, and bodies are released after selection; returned subjects
and author names have 240 and 120 Unicode code point limits respectively.

The browser keeps one 20-entry page and one selected source rather than growing
an unbounded history list. Its line comparison accepts at most 256 KiB combined
UTF-8 source, 2,000 combined lines and 250,000 line-pair comparisons; exceeding any
bound selects side-by-side source. Line endings remain distinguishable, including
CRLF and missing trailing newlines. Historical source retains the existing 1 MiB
file-read limit. Neither preview nor restoration edits the historical object.

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

Real Git tests cover additions, edits, unrelated commits, first-parent merges,
deletion and recreation, bounded pagination, metadata limits and cross-workspace
commit rejection. Authorization tests check denial before traversal and revocation
before returning data. HTTP integration tests reject anonymous and public-only
readers, retrieve exact historical source, and verify that restoration adds a
commit while stale writes fail without overwriting current content.

Mounted editor tests cover late responses, escaped source, cancellation of unsaved
replacement and saving restored text with the current write preconditions. Source
comparison tests retain exact line endings and exercise the fallback bounds.
Chrome acceptance through the [browser entrance](../../acceptance/README.md)
confirmed a new saved restoration with earlier history retained, conflicting edits
from another tab preserved, desktop and mobile layouts, and the large-source
fallback.

## Same-topic audit

[Repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md)
retains arbitrary-path reads and revision-checked writes.
[CodeAct content and media](../implemented/2026-09-09-codeact-content-and-media.md)
retains full-reader history and excludes it from public projections.
[Authoring and discovery](../implemented/2026-09-23-authoring-and-discovery-experience.md)
retains unsaved local recovery; this feature recovers committed source.
[Off-host backup and restore](../proposed/2026-08-27-off-host-backup-and-restore.md) remains a
separate proposal; choosing an older text version supplies no backup service.
