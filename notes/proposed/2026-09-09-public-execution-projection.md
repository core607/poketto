# Public Execution Projection

Date: 2026-09-09
Status: Proposed

## Scope

This mechanism implements the public-reading boundary in [CodeAct content and media](2026-09-09-codeact-content-and-media.md). That record owns the repository format, media bridge, authoring, exports, and migration. [CodeAct workspaces](2026-09-09-codeact-workspaces.md) retains the full-reader save contract. The [local supervisor](2026-09-05-local-execution-supervisor.md) owns process isolation and lease termination.

## Decision

An API key with `EXECUTE_REPOSITORY` opens an execution copy within its current read authority. `READ_PRIVATE` selects the existing complete repository bundle with original ancestry. Without it, the host builds a new bare Git repository from the current approved publication and exports one parentless commit. Original repository objects, commit identities, authors, configuration, private files, and arbitrary frontmatter never enter that bundle. The generated commit uses a fixed service identity and epoch timestamp; publication-approved article dates remain in file metadata.

The public projection retains article titles, tags, routes, public dates, and Markdown body content. It removes source `AGENTS.md` files and supplies a service-owned root guide. HTML nodes and reference definitions are removed; approved article and indexed-media destinations are rewritten into the projection namespace. Unresolved or private destinations become plain labels. Safe external article links and local anchors remain text references, with no automatic fetch.

Each public route maps to a folder containing `index.md`, with the root route using root `index.md`. Route segments named `index.md`, `AGENTS.md`, or `.poketto`, and segments beginning with `~`, gain a `~` prefix to keep mapping injective and avoid file/directory conflicts. Original paths remain in a host-only mapping. Referenced eligible indexed media receive separate logical aliases under a collision-free media folder and an approved `.poketto/assets.json`; originals are not eagerly copied. Legacy Git binaries and automatic gallery materialization require the media cutover and bridge before complete media acceptance.

The session keeps the scope selected when it opens. Gaining private-read permission does not enlarge an existing public copy. Losing required permission invalidates the session. Public sessions cannot select original historical commits. A public session continues across unrelated private changes but expires when its projected public content or source mapping changes, publication becomes unavailable, or current authorization fails. A new MCP session obtains the replacement projection; the host does not silently replace local files during a command.

The host hashes length-delimited projected files and source mappings to compare publication changes without exposing original commit identities. A bounded cache of 64 fingerprints is keyed by workspace and immutable source commit. Every lookup first obtains a current unexpired publication snapshot, and validation checks that snapshot again after any object work. Export metadata carries its workspace identity. A proof from another workspace is rejected even if public content is identical.

Authorization is checked before opening, before each command, after a completed command before returning output, and during lease renewal. The existing worker close protocol terminates invalid leases. A command may finish locally while publication is withdrawn; its output is then suppressed. Already delivered data cannot be recalled. This mechanism limits the data available to a public key; it does not infer user intent or prevent a fully authorized external harness from following injected instructions.

## Bounds and alternatives

Projection creation uses the configured bundle and wall-time limits, document/workspace text bounds, bounded Markdown traversal, and a fresh disposable staging directory. Object writing and bundle creation do not copy source configuration. Cleanup handles read-only Git objects on Windows and does not follow symlinks. Fingerprint caching holds hashes rather than source text and cannot substitute for current publication authorization.

Filtering an original clone leaves private objects recoverable through Git history and is rejected. Pinning validity to every source commit would close sessions after unrelated private edits; comparing the approved projection avoids that disruption. Rebuilding the working tree silently would discard local analysis and change command semantics, so changed publication requires a new session.

## Acceptance

Real Git fixtures must prove one independent commit, absent original/private objects, excluded source guides and private metadata, scoped indexed-media references, cleanup on withdrawal, workspace separation, and stable validity across private-only commits. Real socket tests must observe projection-only opening, unchanged session scope after permission increases, and output suppression plus lease closure after withdrawal. Required Linux storage and executor checks remain mandatory.

Before this mechanism is treated as fully delivered, validate the real public MCP execution path with the deployed worker, complete media materialization and gallery handling, and update the public capability documentation with the repository-format cutover. Unit fixtures and protocol tests do not claim that complete client flow has run.
