# Incremental Public Snapshots

Date: 2026-10-16

## Problem

[Repository authoring foundations](2026-09-05-repository-authoring-foundations.md) own the public content snapshot, which every acknowledged commit installs anew through `JGitPublicContentSnapshots.installAcknowledged`. Its build read and parsed every eligible public document again, even when the commit changed only private paths. On 2026-10-17, JFR samples of seven saves on the production space (about 210 public documents) put about 60% of the saves' request-thread CPU in that rebuild:

- about 45% in reading and parsing each document again;
- about 15% in parsing the bodies of folder landings for their links, which `PublicContentSnapshot` also redoes when the same commit's snapshot is renewed.

## Decision

- `RepositoryDocumentScanner` keeps the documents of the public snapshot's last scan per workspace, keyed by path, file mode, blob and public scope. A document's file and parsed metadata depend only on those four, so an unchanged document is taken from that scan and rebased onto the new commit instead of being read and parsed. Each scan replaces its workspace's documents with the ones it saw. The cache therefore never holds more than one snapshot's documents, and a removed document leaves it with the next scan. Scans of one workspace are already serialized by the authority's workspace lock.
- Only the public snapshot's scan keeps documents. Browser trees and full-repository reads use other eligibility rules, and would only displace its documents.
- `MarkdownDestinations.parse` remembers results by body, least recently used first, up to 16 Mi characters of bodies. Landings, cover lookups and projections ask again for bodies they already parsed.
- Everything derived from several documents is computed again on each build: folder landings, collisions, article identity diagnostics and history-derived dates. The publication-closing order is unchanged: CLOSED is recorded before the build, and OPEN after it.

## Alternatives

- **Keep serving the previous snapshot after a private-only commit.** This skips installation entirely. But the snapshot names its commit, image grants bind the page commit, and renewal assumes the snapshot matches current authority, so an older commit id would weaken all three.
- **A cache of parsed documents by blob id alone, shared across workspaces with its own eviction.** The path and scope are part of what a parse depends on, and an independent cache needs its own bound. Keeping one scan per workspace bounds the memory by what the published snapshots already hold.

## Consequences

A save's snapshot cost follows the documents it changed. Correctness depends on the key covering every input of a document's parse; the equivalence test compares a reusing build with a fresh one after each edit. The cache and the remembered destinations add memory on the order of the public snapshots' own text: a space that stops publishing drops its kept documents, and each remembered body also counts a fixed entry cost, so many short bodies cannot outgrow the bound.

## Verification

`SnapshotScanReuseTests` applies additions, changes, renames, deletions, an invalid document and private additions, and requires each reusing build to equal a fresh build of the same commit and to reuse unchanged documents. The existing snapshot, marker and article-identity tests pass unchanged.
