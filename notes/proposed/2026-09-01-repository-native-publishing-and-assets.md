# Repository-Native Publishing and Assets

Date: 2026-09-01
Status: Proposed

## Problem

The implemented [content foundation](../implemented/2026-08-26-content-foundation.md) recognizes only strict Poketto documents below `documents/`, and the [requirements](../implemented/2026-08-25-requirements-and-architecture.md) place every image outside Git behind a content hash. Those contracts suit content created through a structured editor, but they do not adopt an existing personal repository containing Markdown and related images in ordinary nested folders.

A repository-native service should not require the owner to relocate every note, add Poketto frontmatter to every file, rewrite relative image links, or mark each article public individually. It needs a one-time repository publishing policy, commit-pinned repository text, revision-consistent managed assets, and agent writing that preserves the original file layout.

## Proposal

### Repository corpus

- Every regular UTF-8 Markdown file outside reserved Poketto metadata and configured exclusions belongs to the workspace corpus, regardless of directory depth or frontmatter shape. Trusted repository execution may inspect other authorized files as bounded raw repository content.
- A repository text file is addressed by canonical repository path and opaque blob revision at one resolved commit. It does not need a Poketto UUID or canonical frontmatter to be read, rendered, or patched.
- Strict managed documents may coexist for structured APIs while they have consumers, but `documents/` is not the corpus or publishing boundary. Unknown frontmatter and noncanonical Markdown remain byte-preserved unless an authorized patch changes them.
- A path move changes the default route. Stable aliases or redirects must be explicit repository metadata; Poketto does not infer identity from similar titles or content.

### Publishing policy

An unconfigured repository is private. Publishing begins only after the owner commits a valid `.poketto/publishing.yaml` and explicitly enables its policy. The first policy mode is `public-by-default`: eligible Markdown and Git-bound asset sources are public unless a configured path exclusion matches them. A managed-only asset becomes publicly retrievable only while an eligible public document references its managed identity.

Exclusions are repository-relative path globs evaluated by a bounded linear-time matcher. They are not regular expressions and do not accept request-time patterns. Reserved metadata, Git internals, symlinks, submodules, non-regular files, and paths that escape the repository are never public. Exclusion wins permanently for a resolved path; a public document cannot embed or link through an excluded asset.

The repository may keep private or draft material in one or more excluded folders instead of carrying a visibility flag in every file. A machine creation or move into the public set, a patch to an already-public file, and any publishing-policy change require `PUBLISH`. Private-only text changes require `WRITE_PRIVATE`. Direct owner commits remain break-glass publishing actions and are reflected after the new `main` resolves.

### Folder pages and routes

- `index.md` is the page for its containing folder. A nested folder therefore becomes a text-and-image article without an administration UI.
- A non-index Markdown file receives a path-derived article route. Route normalization and collision checks are deterministic across Windows and Linux; a collision excludes every conflicting page and reports a workspace diagnostic rather than selecting one.
- Titles, dates, tags, summaries, and aliases may come from recognized optional frontmatter or repository metadata. Missing optional metadata does not make otherwise valid Markdown unreadable.
- Tag, archive, RSS, and sitemap views derive only from the public file set at one resolved commit.

### Managed assets and rendering

Markdown may contain a managed asset identity or an ordinary relative image link. A managed identity resolves through `AssetCatalog`. The renderer resolves a relative link against the referring file's directory at the same commit, validates the Git source, and imports or finds its binding under [asset BlobStore and Git synchronization](2026-09-01-repository-asset-blob-store.md). Both forms produce a workspace-scoped immutable URL for the confirmed managed asset version. Poketto never writes a deployment hostname, local path, bucket URL, or signed provider URL into the repository.

A bounded `ManagedAssetResolver` accepts only an authorized managed identity or a regular Git file from the resolved commit. It validates path containment, size, media type, response bounds, asset revision, and public reachability. A Git-bound asset whose source path is excluded remains unavailable even when a document names its managed identity. Public responses use the managed asset version as an `ETag` and may be cached immutably. HTML rendering sanitizes active content and applies the public CSP; a Markdown link cannot turn an excluded file, private managed asset, repository metadata, or arbitrary remote resource into a public response.

BlobStore owns managed image bytes: local filesystem in the primary single-server profile and OSS-compatible object storage in the optional serverless profile. A Git image is an optional import source and synchronized copy. A pure upload needs no Git path, while an adopted repository keeps working with its existing relative links. Pending or conflicted Git synchronization never changes the last confirmed asset version silently.

One page response pins the resolved repository commit and every managed asset revision it emits. Replacing a managed asset may change a later page response without a Markdown commit; its immutable asset URL and `ETag` still identify the exact bytes. Historical rendering therefore requires both the repository commit and recorded asset revisions rather than pretending that Git alone contains the full page.

### Agent authoring and SRT

`repo_exec` discovers folder structure, reads text, inspects Git history, and runs bounded local image-processing tools inside the SRT workspace. It does not emit image bytes as base64 or write repository or asset authority.

A structured `get_asset` operation accepts a managed identity or an authorized commit and Git path. It returns the confirmed BlobStore version as bounded multimodal content together with its opaque asset revision and Git synchronization state. It verifies workspace, public or private scope, path and binding when present, media type, dimensions, and byte limits. SRT itself receives no authority credential and does not need network access to let the model see an image.

An agent may draft `index.md` or another Markdown file inside its disposable execution workspace. A separate `repo_patch` operation applies bounded UTF-8 text changes to authority. It carries the resolved base commit and expected blob revision or expected absence for every affected path. The server validates path, size, publishing impact, capability, and candidate tree before asking `RepositoryAuthority` to advance `main`. A successful sandbox command never implies that its draft was committed.

Structured `put_asset`, `bind_asset`, `move_asset`, `inspect_asset_removal`, and `delete_asset` entrances invoke the asset service rather than writing through SRT. Public reference changes and Git source synchronization require `PUBLISH`; private managed-only changes require `WRITE_PRIVATE`. Move and delete operations expose the source classification and require the explicit Git disposition defined by the asset proposal. An agent cannot smuggle that choice through free-form confirmation text.

The existing full-copy SRT workspace remains the first security boundary. Remote fetch and session materialization are measured separately on a representative repository containing nested text and images. Session reuse prevents twenty small commands from paying that copy repeatedly; a filtered snapshot requires a later decision only if measured local-copy cost is material and the filter preserves commit identity, isolation, and explicit missing-blob behavior.

## Implementation scope and dependencies

The first implementation depends on [remote repository authority](2026-09-01-remote-repository-authority.md), [asset BlobStore and Git synchronization](2026-09-01-repository-asset-blob-store.md), and the read boundary in [repository-native retrieval and sandboxed execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md). It adds publishing-policy parsing, path and route resolution, arbitrary Markdown reads, managed and relative asset references, public-content contracts, structured asset entrances, text-file revision reads, `repo_patch`, diagnostics, and focused security, browser-contract, and Git integration tests.

It updates the requirements counterparts and frontend proposal. It reverses the target assumption that every content file lives below `documents/`, every publish is a per-document visibility mutation, every image reference is a managed hash, and Git cannot supply a synchronized image source. The implemented content and write notes remain the executable baseline until this proposal ships.

The first implementation excludes arbitrary binary writes through `repo_exec`, image editing, thumbnail generation, OCR as a service, automatic captions, CDN configuration, redirects inferred from history, submodule content, Git LFS integration, and a general-purpose arbitrary-file write API.

## Alternatives considered

**Require every article below `documents/`.** This keeps parsing predictable but turns adoption into a migration and makes the repository conform to Poketto instead of letting Poketto read the repository.

**Publish only files carrying `visibility: public`.** This is conservative but requires repeated bookkeeping in a repository whose owner wants the blog to be its ordinary public face. A one-time public-by-default policy with excluded private folders makes the broad intent explicit.

**Publish every connected repository immediately.** This removes setup but can disclose an existing private corpus before the owner sees the effective file set. An unconfigured repository stays private until its policy is committed and enabled.

**Serve Git images outside the managed asset system.** This avoids synchronization state but gives relative and uploaded images different authorization, metadata, lifecycle, URLs, and serverless behavior.

**Require every managed image in Git.** This keeps a folder portable but forces uploads and generated images through one repository path and commit workflow. A Git binding remains optional instead.

**Let `repo_exec` push its workspace.** Sandbox mutations and generated files would bypass capability checks, publishing analysis, revision preconditions, and authority reconciliation. `repo_patch` is the only agent write bridge.

## Acceptance

- A pre-existing repository with nested Markdown and sibling images becomes readable without moving files, adding UUIDs, canonicalizing frontmatter, or rewriting links.
- Before a valid policy is enabled, every public route returns no repository content. After enabling public-by-default, eligible new Markdown is public automatically and every configured private-folder path remains unavailable to public pages, assets, lists, Q&A, errors, caches, and counts.
- `folder/index.md` renders as the folder page. A relative image imports through its Git binding, a managed-only reference needs no Git file, and neither form exposes a remote URL, local path, credential, excluded file, or another workspace.
- Rendering rejects unsafe HTML, path traversal, symlinks, submodules, ambiguous normalized routes, oversized files, unsupported media, and links from public Markdown into excluded paths.
- `repo_exec` can discover mixed text-and-image folders. `get_asset` returns the confirmed managed version and synchronization state without base64 command output or sandbox network access.
- Structured asset operations create managed-only images and synchronize optional Git sources. A Git-backed move or delete cannot proceed without an explicit source disposition, expected asset revision, and expected Git revisions.
- `repo_patch` can create or update a Markdown draft with expected-blob and expected-ref protection. It cannot write binary files, escape allowed paths, expose private content without `PUBLISH`, modify publishing policy without `PUBLISH`, or commit sandbox debris.
- Cold remote fetch, warm incremental fetch, first Git-asset import, managed-only upload, synchronized update, first SRT materialization, and a twenty-command reused session record network bytes, local bytes copied, latency, memory, and storage without identifying a private corpus.
- Requirements, README counterparts, frontend and retrieval proposals, relevant automated tests, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

Public-by-default makes path placement a publishing action. The initial private state, explicit enablement, exclusion diagnostics, and `PUBLISH` capability reduce accidental exposure, but owners using direct Git must understand that a commit outside excluded folders may become public immediately.

Arbitrary Markdown has weaker uniform metadata than a canonical schema. Optional parsing and diagnostics preserve readable content, while features that require dates, tags, or stable aliases must define their fallback rather than rejecting the whole repository.

Git repositories with binary history can be expensive on a cold cache and during isolated SRT workspace creation. Incremental fetch and session reuse avoid repeated network transfer; resource evidence decides whether a more complex filtered snapshot is justified.
