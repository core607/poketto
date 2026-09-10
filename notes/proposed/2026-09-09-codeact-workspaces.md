# CodeAct Workspaces and Permission-Scoped Execution

Date: 2026-09-09
Status: Proposed

[CodeAct content and media](2026-09-09-codeact-content-and-media.md) owns the expanded implementation target: public/private roots, local indexed media, workspace-only deduplication, original history for full-read sessions, portable exports and deterministic service authorization without a mandatory reviewer agent.

## Priority and problem

This is the next development priority for repository agent access, ahead of expanding specialized MCP tools. Implement the workspace and authorization boundary before replacing the agent authoring entrance. The implementation steps below describe one target architecture, not compatibility modes or separate experimental products.

The [current executor](2026-09-05-local-execution-supervisor.md) requires both `READ_PRIVATE` and `EXECUTE_REPOSITORY`. It exports a credential-free Git bundle with reachable history into a disposable session. Commands can change session files, but authoritative reads and `repo_patch` operate separately. An agent cannot save those files without sending their contents through the structured write interface. A key without private-read authority cannot execute at all.

Poketto will provide a coherent CodeAct workspace: ordinary files, shell and Python tools, bounded execution, and a small service bridge for durable saves and managed assets. A permitted search scans the files present in that workspace, including private articles when the identity has private-read authority.

## Workspace admission and contents

The application resolves the workspace, current identity, execution capability, content version and read scope before preparing any export. Agent commands cannot choose a host path, raw repository source or broader scope. Execution remains authenticated and explicitly granted; this proposal does not open an anonymous executor or implement visitor Q&A.

| Read scope | Export | Authority writes |
|---|---|---|
| Full repository | Exact authorized files and independently copied history covered by `READ_PRIVATE` | Require existing write and publication capabilities |
| Public only | Fresh files derived from the currently serviceable public document and media projection | Unavailable |

Public-only execution requires `EXECUTE_REPOSITORY` and current workspace membership, but not `READ_PRIVATE`. Its files expose only the information already eligible for public delivery. Filtering raw Markdown paths alone is insufficient: private frontmatter, internal diagnostics, excluded guides, source metadata and hidden media references must not enter the export. Use public logical routes for the derived document layout; retain any source mapping outside the sandbox. Include only validated, publicly reachable media. Invalid or expired publication state rejects admission.

Build restricted exports from an empty destination. Never clone the original repository and then remove private paths. Do not transfer its object database, commit messages, refs, reflogs, alternates or source credentials. A fresh local Git baseline may support `git diff`; it is not a commit in repository authority. Service-owned version and scope records remain outside the writable directory.

The [publishing policy](2026-09-01-repository-native-publishing-and-assets.md) remains the visibility authority, including root `private/` and configured exclusions. Directory-based privacy versus file metadata is a separate product decision; admission must consume the shared visibility result rather than introduce another policy parser. Public export is a derived reading representation, not an editable copy of hidden source files.

Session reuse is bound to identity, workspace, read scope, source version and a bounded lease. A permission downgrade or publication withdrawal invalidates affected sessions: terminate their processes, deny further output and bridge access, and remove their files. Revalidate at admission, command dispatch, output delivery and bridge operations, and connect policy/key invalidation to active-session cancellation. Never downgrade a full workspace by deleting selected files in place. Content already delivered to a client cannot be recalled.

## Execution and service bridge

`repo_exec` is the primary composition entrance. Supply ordinary search, shell, Python and a precise patch utility in the declared toolchain. Directory listing, text reads, edits, moves and local processing operate on the same session files. Do not require a parallel model-facing filesystem API or domain-specific playlist/news operations. The full workspace bootstrap returns authorized root `AGENTS.md` guidance; nested guides remain discoverable files. Guidance never changes permissions, and restricted exports do not inject excluded guides.

Maintain a narrow image/artifact return channel with explicit media types, size limits and authorized references. Printing a filename or Base64 into stdout does not deliver a visual input. Long text results use bounded session artifacts with explicit truncation and expiry. Imported assets belong to the same session and scope; artifact handles cannot name arbitrary host files or another session's outputs.

A small `poketto` CLI exposes host-mediated save and asset operations. The host retains remote and storage credentials. Use a bounded capability bridge, not general network access or a mount of the application control socket. Every request is tied to the admitted session and reauthorized by the existing domain service. Preserve no-network execution, resource admission, process-tree cancellation and fail-closed sandbox setup.

CLI calls wait for the host result, so a command can fetch media and then process that file, or save and inspect its acknowledged status. Do not defer mutations silently until the shell exits. A lost response reports an unknown outcome and never causes an automatic write replay. Signed host polling and completion must remain responsive while the worker runs the command; renewal, revocation and cancellation cannot wait on the command's operation lock.

Saving collects explicitly selected paths and deletion intent from a stable capture of the session files. The application owns the original base and expected revisions, validates bytes and paths, and uses the shared atomic repository writer. A command exit or local Git commit does not imply a durable save. A conflicting remote ref leaves the session changes available for reconciliation; an uncertain acknowledgement requires reconciliation before retry. Hidden or unselected files are never inferred to be deletions. Public-only sessions cannot save or upload.

After a confirmed save, advance the session's service-owned baseline without losing unselected edits. Serialize capture/baseline updates against mutating commands. Return the committed version separately from public snapshot installation. Managed uploads keep immutable references and do not implicitly save a document or publish an image. Ordinary restart or lease expiry discards unsaved work; report that lifetime rather than promising durable drafts.

## Implementation order

The worker's prepared `session_files.capture_text` primitive captures only selected UTF-8 files and explicit deletion paths, with the shared 64-file/4-MiB save bounds. It opens each path component without following symlinks, rejects hard links and special files, and preserves source bytes after UTF-8 decoding. Its caller must freeze the entire command process tree or prove it empty and retain the lease mount throughout capture. The bridge, process-freeze integration, host-owned revision baseline and real-client save workflow are not yet connected; the helper alone does not provide durable authoring.

The prepared `bridge.LeaseBridge` mailbox uses a supervisor-owned request FIFO and read-only reply directory per lease, with 512-KiB frames, four pending requests and 256 request identities per lease. The CLI correlates replies by request identity, acknowledges consumed replies and never replays a write automatically. The host claims each request once and publishes complete replies by atomic rename. Closing the bridge stops polling, removes retained replies and makes waiting clients report an unknown outcome.

SRT 0.0.75 ignores the path-based `allowUnixSockets` setting on Linux; enabling Unix socket creation would require a broader exception. FIFO requests preserve `allowAllUnixSockets: false`. An advisory FIFO writer lock prevents interleaving between cooperative CLI processes; malformed traffic can invalidate only its own lease. Native sandbox mounts must isolate the FIFO and reply directory even when leases share an execution UID. The real CLI/FIFO tests do not prove that native boundary. Worker lifecycle, signed host polling, CLI installation and service dispatch remain to be integrated.

1. **Admission and exports:** add explicit read scope to execution admission and leases; implement public-projection exports without original history, scoped reuse, invalidation and bounded cleanup. Keep full authorized repository access available.
2. **CodeAct I/O:** define the session filesystem, bootstrap guidance, precise editing utility, bounded logs, and image/artifact ingress and egress. Make all local operations observe the same state.
3. **Durable authoring:** implement the capability bridge, selected-file capture, remote conflict/retry handling, asset operations and baseline advancement. Reuse domain authorization and persistence.
4. **MCP cutover:** make execution the agent file workflow and remove redundant standalone filesystem/read/patch entrances once their required behavior is covered. Retain a narrow media entrance if the transport needs it. Update current-state contracts and paired requirements/README together. Preserve browser APIs and their shared services.

These steps do not add per-file ACLs, choose a new publication format, implement a folder picker, or enable public arbitrary execution. CodeAct becoming the agent file entrance makes the worker a prerequisite for that workflow; account for cold-start and resident resource costs in the existing capacity acceptance rather than maintaining a second agent CRUD architecture.

## Acceptance

Use focused behavioral evidence and the required repository checks, not repeated model comparisons or a new benchmark program:

- A full-scope search finds authorized public and private articles. A public-only search sees only public documents; neither shell traversal, local Git inspection, artifacts nor bridge requests recover hidden files, metadata or history.
- Policy withdrawal, key revocation and expiry stop affected active sessions and prevent further result delivery or attachment access. Cross-session handles and symlink/path escapes fail through the real worker boundary.
- A native CodeAct task discovers content, edits a file, handles an image and saves selected changes without resending the edited document through a separate MCP write call. Subsequent reads see the saved baseline and any retained unselected edits.
- Concurrent authority changes, lost save acknowledgements, selected deletions and attempted writes outside scope exercise the shared writer's actual authorization and remote-ref boundary. Restricted export omissions never become authoritative deletions.
- Existing worker resource and cleanup checks cover export, retained artifacts and bridge processing. Run one representative client workflow and the required native isolation checks; broad repeated trials are not a prerequisite.

## Alternatives, risks and related decisions

Keeping the current fixed read/write tools avoids worker startup for small requests but leaves two content views and requires model-mediated transfer of edited bytes. Moving those same CRUD calls behind a code wrapper preserves that duplication. Direct remote credentials inside the sandbox would bypass the service's authorization and concurrency boundary. Full clones followed by filtering expose original history. These alternatives are not the target.

Retain [retrieval and execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) for repository-native search and isolation rationale; this proposal replaces its target of a permanently separate structured agent write path and mandatory private-read execution. Retain the [local supervisor](2026-09-05-local-execution-supervisor.md) for implemented transport, resource and lifecycle guarantees. Retain [authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md) and [publishing/assets](2026-09-01-repository-native-publishing-and-assets.md) for shared writes, visibility and media ownership. Retain [directory navigation](../implemented/2026-09-08-repository-directory-navigation.md) as the implemented baseline until cutover, including its rationale for executor-free reads. [Phase-one delivery](2026-09-05-phase-one-daily-use.md) retains installation acceptance; this plan has priority over additional agent tool expansion. No related note is wholly obsolete, archived or rejected.
