# Incremental Public Snapshots

Date: 2026-10-16
Status: Proposed

## Problem

[Repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md) own the public content snapshot. Every acknowledged commit installs a new snapshot through `JGitPublicContentSnapshots.installAcknowledged`. Its `build` reparses every eligible public document's Markdown and front matter, even when the commit changed only private paths or nothing public at all. Saving one private file therefore costs as much as rebuilding the website. The cost grows with the number of public documents, not with the size of the change.

On 2026-10-16 the production space with 212 public documents was profiled with JFR over four single-file saves, two private and two below `public/`. Snapshot installation took about 1.3 s of application CPU per save, roughly half of each save's measured 2.8 s of CPU. Every save reported `snapshotUpdated: true`.

## Proposal

Build the next snapshot from the one it replaces, when that one exists for the same workspace:

- Reuse a parsed `PublicArticle` when its source blob id, its path and the publishing policy blob id are all unchanged. Parse only added and changed eligible documents, and drop removed ones.
- Recompute what depends on other documents from the commit's tree listing alone: folder landings, which prefer `index.md` over `README.md`, and resolution of links between documents. Reparse no unchanged body to do it.
- Fill history-derived dates for documents without front-matter dates only for changed paths. Every other document keeps the dates it already had.
- Leave the publication-closing rule as it is. A publication-affecting patch still persists the CLOSED marker before it pushes, and installation still opens the new snapshot only after it is built. This proposal shortens the build; it does not shorten the closed window.

## Acceptance criteria

- A commit that changes only private paths parses no Markdown during installation.
- On any sequence of edits, an incremental build produces a snapshot equal to a full build of the same commit. A test applies generated add, change, rename and delete sequences and compares the two builds.
- Crash recovery, marker ordering and snapshot expiry behave as before. The existing marker tests pass unchanged.
- Production `repo_exec stages` records show a private save's bridge time without the snapshot-build share measured above.

## Alternatives

- **Keep serving the previous snapshot after a private-only commit.** This skips installation entirely. But the snapshot names its commit, image grants bind the page commit, and renewal assumes the snapshot matches current authority, so serving an older commit id would weaken each of those guarantees.
- **Cache parsed documents by blob id across snapshots.** This is equivalent in effect, but it needs a separate bounded cache and eviction policy. Reusing the previous snapshot's articles bounds the cache to one snapshot per workspace.

## Consequences

Installation cost follows the size of the change. Correctness then depends on the equivalence test catching any field that silently depends on another document. Pair this with [workspace cache packing](2026-10-16-workspace-cache-packing.md): the tree listing still reads Git objects.
