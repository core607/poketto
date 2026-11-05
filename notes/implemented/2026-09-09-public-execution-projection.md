# Public Execution Projection

Date: 2026-09-09

Partially superseded by: [Native repository consumer](2026-11-05-native-repository-consumer.md).
Implemented: 2026-09-14

## Problem

An API key holding `EXECUTE_REPOSITORY` but not `READ_PRIVATE` may run commands against a workspace, yet the only execution copy that existed was the complete repository bundle with original ancestry. Filtering that bundle cannot make it safe: private files, private frontmatter, source `AGENTS.md` guides, commit identities, authors and configuration remain recoverable through Git history, and any path or link that names private content leaks the fact of its existence.

The public reader therefore needs a separate reading namespace derived solely from approved publication, a way to tell when that namespace is stale without exposing the private authority commit, and a session lifecycle that does not enlarge, downgrade or silently rebuild a copy while a command is running. [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns the repository format, media bridge, authoring, exports and migration; this record owns how the public projection is built, validated and withdrawn.

## Decision

**Scope selection.** A session opened with `READ_PRIVATE` exports the complete repository bundle with original ancestry. Without it, `RepositorySnapshotExports.createPublic` builds a fresh bare Git repository in the configured staging directory from the current approved publication snapshot and exports one parentless commit on `refs/heads/snapshot`. A public open that names a commit is refused; public execution starts from current publication only.

**Bundle contents.** The generated commit carries a fixed service identity (`Poketto <poketto@invalid>`, epoch timestamp, UTC) and the message `Public reading projection`, so equal projections hash equally. Original repository objects, commit identities, authors, configuration, private files and arbitrary frontmatter never enter the bundle. Each article becomes a frontmatter block of `title`, `tags`, `route`, `public_author` and, when present, `date` and `updated_at`, followed by the sanitized Markdown body. [Public author names](2026-09-14-public-author-names.md) owns the explicit signature field. Source `AGENTS.md` files are dropped and a service-owned root `AGENTS.md` guide explains the projection's limits to the agent.

**Body sanitization.** HTML blocks, inline HTML and link reference definitions are removed. Links and images to approved articles or admitted indexed media are rewritten to relative projection paths. A destination that does not resolve, or resolves to private content, is flattened to its plain text. Own-page fragments and absolute `http`, `https` and `mailto` destinations are kept as authored and never fetched. Traversal is bounded to 20 000 nodes and depth 128; each article stays within the document byte limit and the projection within a workspace byte bound.

**Route mapping.** Each public route maps to a folder holding `index.md`; the root route is root `index.md`. A route segment named `index.md`, `AGENTS.md` or `.poketto`, or beginning with `~`, gains a `~` prefix so the mapping stays injective and no projected file becomes a directory of another. Two routes normalizing to the same path, or nesting, reject the whole projection. The original source paths stay in a host-only mapping inside `PublicExport.sourcePaths`.

**Media.** Media referenced by an approved article and permitted by the publishing policy receives a logical alias `N-<basename>` below a `_media` folder (or `_media_N` when a route already claims that prefix), and the projection writes an approved `.poketto/assets.json` listing those aliases. Originals are not eagerly copied; `poketto media fetch` in a public session resolves only through the host-owned mapping, as the [worker reference](../../executor-service/README.md) describes. An absent local media file is not a deletion.

**Validity without exposing the authority commit.** `PublicExecutionProjection.fingerprint` hashes length-delimited projected files, source mappings and media aliases with SHA-256. `JGitRepositorySnapshotExports` keeps a bounded, access-ordered cache of 64 fingerprints keyed by workspace and immutable authority commit; the cache holds hashes, never source text. `requireCurrentPublic` authorizes `EXECUTE_REPOSITORY`, rejects a proof from another workspace even when the content would be identical, obtains the current unexpired publication snapshot, compares its fingerprint with `PublicExport.projectionSha256`, then re-authorizes and checks that the snapshot commit has not moved in the meantime. Private-only commits therefore leave a public session valid; a changed public article, changed source mapping, disabled publication or failed authorization invalidates it. `publicProjectionChanged` runs the same checks but returns a fingerprint mismatch as a result; it reports a change only when authorization, the workspace and one unmoved snapshot all hold, and every other failure still throws. `requireCurrentPublic` throws on that result.

**Session lifecycle.** The copy keeps its initial reading scope. The native workspace owner checks the current public fingerprint before use, and the command, artifact and host-call paths re-authorize before releasing data. Withdrawal during a command suppresses its output. The host keeps local files stable during execution.

**Changed publication.** The [native consumer decision](2026-11-05-native-repository-consumer.md) retires automatic clean-copy inspection and refresh. Any changed public projection refuses the old copy with `PUBLICATION_CHANGED`. Its files remain until explicit disposal or expiry. A new copy starts from the current authorized projection under a new copy ID. Reconnection preserves the scope and binding already recorded by the account journal.

A public key receives the publication-derived namespace. An external harness remains responsible for its own response to untrusted content. Already delivered data can remain with the recipient.

## Alternatives

**Filtering an original clone.** Rejected: private objects remain recoverable through Git history, and the current tree cannot be made safe by path filtering alone. The projection is built from publication fields, never from the source tree.

**Pinning validity to every source commit.** Rejected: it would close public sessions after each unrelated private edit. Comparing the fingerprint of the approved projection keeps the session alive across private-only commits while still catching every public change.

**Rebuilding the working tree in place or during a command.** Rejected: it would change command semantics mid-session and mix two projections in one tree. The native consumer fences the old copy at admission and requires explicit disposal before replacement.

**Always rebuilding a stale copy.** Rejected: it would silently discard local analysis, generated files and fetched media, which a public copy cannot save. A copy with local work is refused and kept until the user discards it.

**Merging local changes into the new projection.** Rejected: installing the new projection's objects beside local work needs the baseline installation that public copies deliberately cannot invoke, conflicts would land in a copy that cannot save, and local files could carry withdrawn text into a copy presented as current publication.

**Automatic clean-copy refresh.** The signed worker inspected a stale copy through an extra lease and could rebuild it under the same ID. The native consumer fences the copy and lets its owner dispose it. This removes the inspection and refresh protocol from the application while retaining local work until an explicit choice.

**Treating a fingerprint as authorization.** Rejected: the cache accelerates comparison only. Every check still obtains a current publication snapshot and re-authorizes the principal, so a cached hash cannot substitute for current authorization.

## Consequences

- A public-only key can run commands, read, search and export, but sees only what the website may already serve; it cannot save, and [`docs/usage.md`](../../docs/usage.md) describes its status and exports.
- Public and full projections are separate copies; one account's authorized clients share a copy only within the same workspace and reading scope.
- Every public command pays for a fingerprint lookup; a cache miss recomputes the projection within the export deadline, which bounds the cost of a large publication.
- Route segments that collide with reserved names are visibly renamed with `~`; agents reading the projection must not assume projected paths equal repository paths.
- Withdrawal is not instantaneous for bytes already in flight; the guarantee is that no further output is returned after the check fails.
- The projection rejects, rather than partially serves, a workspace whose routes collide or nest or whose size exceeds its bounds.
- A changed publication fences its old copy until disposal or expiry, including a copy without local edits. Its owner requests a new copy for current publication.
- An interruption at any point of a rebuild leaves a `REFRESHING` record under the same ID, and the next request resumes it; a `REFRESHING` copy is collected only after its idle expiry.
- A tool that writes a cache under `$HOME` during ordinary use makes the copy count as not clean; the user then discards it explicitly.

## Implementation and acceptance

`PublicExecutionProjection` owns the publication-only tree and fingerprint.
`JGitRepositorySnapshotExports` owns export and current-publication checks.
`NativeRepositoryWorkspace` owns projection selection and admission.
`RepositorySnapshotExportsTests`, `RetainedPublicProjectionTests` and the
[native workspace probe](../../executor-native/overlay_copy_probe.py) exercise
those boundaries. [Authenticated client acceptance](../../acceptance/clients/README.md)
covers public-copy refusal and disposal through HTTP/MCP.

Related: [CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns the repository format, media bridge and exports; [workspace website delivery](2026-09-14-workspace-public-delivery.md) owns the member-authorized media entrance the projection fetches through; [account working copies](2026-09-14-account-working-copies.md) own copy ownership and storage, not this security boundary.
