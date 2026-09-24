# Portable Content Exports

Date: 2026-09-10
Status: Implemented

## Scope

The [content plan](2026-09-09-codeact-content-and-media.md) requires browser and
CLI exports that work outside Poketto. A repository ZIP alone omits indexed
originals; a worker archive can include private history, unsaved files and runtime
metadata. A host-owned export service selects committed documents and streams
their authorized dependencies into an expiring ZIP. Exporting neither publishes
content nor changes Git.

## Selection and package

Both entrances use the same request: explicit files or directory prefixes and
private or public-share scope. A selection is resolved against one immutable
current authority revision. Full-read callers can export private content;
public-share requests reject private selections even when the caller can read
them. A public-only execution session translates its visible projection paths
through host-owned mappings, without accepting original source paths or history
from the sandbox. An empty or missing selection list fails explicitly; an empty
path selects the whole workspace through the HTTP interface.

The package contains selected documents and their required local media. Directory
selection includes indexed media beneath that directory. Article hyperlinks do
not recursively add other articles; external URLs are never fetched. Relative
references, legacy managed image references and eligible Git images resolve to
their exact committed versions. Missing originals or unauthorized local
dependencies fail the entire request; no partial archive is returned as complete.

Documents occupy a content namespace and media a separate package namespace.
Only resolvable destinations are rewritten to package-relative paths, preserving
fragments. Links between selected articles also resolve inside the package.
Private exports preserve authorized source; public exports use only approved
article fields and sanitized bodies, never private frontmatter or original source
coordinates. Neither scope includes `.git`, credentials, the internal media index,
physical storage paths or runtime guides. Every ZIP entry has a validated relative
name; normalized collisions, traversal, symlinks and special files are rejected.

## Storage and delivery

The service creates archives in protected local staging with bounded streaming. Defaults
are 512 MiB of originals, 10,000 entries and one concurrent build per instance.
Text, compressed output, build time, retained packages and total staging bytes
are also bounded; limits include metadata and ZIP overhead. Capacity is reserved
before building, the handle is published only after the ZIP is closed and verified,
and failed builds remove partial files. A restart removes abandoned disposable packages.

Handles bind workspace, principal, scope and expiry. CLI handles additionally bind
the server-issued MCP session; they are not bearer public links. Browser downloads
and CLI materialization recheck current authorization while transferring bytes.
Public exports also revalidate their admitted article/media projection, so
withdrawal or changed public eligibility invalidates an outstanding package.
Changing publication in another workspace cannot affect or authorize it.

The CLI writes actual ZIP bytes to an explicit destination through the existing
protected incoming-file channel, retaining different local files. Large exports
remain subject to the worker's disk quota and transfer deadline. Expiry, revocation,
capacity exhaustion and incomplete materialization have distinct outcomes. No
private export is copied into a public-only worker, and download handles never
expose storage locations.

## Alternatives and verification

`PortableContentPlanner` selects committed Markdown and indexed
media, resolves required managed originals and Git attachments, and produces
host-only entries for `PortableArchiveWriter`. Private source retains frontmatter;
public packages use approved fields, sanitized bodies and generated article names.
Reference discovery is shared within a plan, bounded to 100,000 unique public
destinations, and public Markdown rendering enforces node and depth bounds.
Publication checks include referenced Git object identities and path eligibility,
so unchanged article text cannot keep an excluded image authorized.
Legacy `managed:` images use exact immutable references in published article bodies
as their publication authority, matching the public image service. Public exports
apply the same image-preview validation before copying those originals; arbitrary
files require indexed-media authorization. Removing or changing the article reference
invalidates outstanding public packages through the article fingerprint.
`LocalPortableContentExports` owns protected staging, exclusive root ownership,
bounded reservations, expiring handles, download integrity checks and session
cleanup. The content-owned `RepositoryOriginalTransfers` port keeps media storage
behind its assets implementation without a module dependency cycle. The
[usage reference](../../docs/usage.md#export-http-interface) describes the HTTP
interface and its limits.

The CLI command `poketto export PATH... --output FILE [--public]` translates
public paths on the host; a public directory selection expands to at most 128
approved source paths. The transfer protocol permits up to 1 GiB while the
server's ZIP limit, ordinary command deadline and lease disk quota remain active.
The worker admits transfers against current free space with 1 MiB of bridge
headroom and repeats the space check while streaming. Known disk-capacity
failures return controlled errors, release staging and retain existing session
files; unexpected transfer or storage failures still close the session. The
shared incoming-file channel also preserves a stored-original receipt when a
media import cannot update its local index, so retrying the same key after
freeing space completes the index without creating another original.

Browser handles bind owner, workspace and expiry and do not claim MCP-session
cleanup. A handle received after the dialog was cancelled is released; a handle
already offered for download remains valid until expiry.

Reusing Git bundles would expose history and omit original media. Asking the
agent to assemble every package duplicates authorization, reference rewriting and
failure handling across clients. A permanent public download URL would turn an
export into publication. A shared deterministic service avoids those changes.

The [logical index](2026-09-09-logical-media-index.md) retains media
ownership, [indexed delivery](2026-09-09-indexed-media-delivery.md)
retains exact-original transfer, and [CodeAct workspaces](2026-09-09-codeact-workspaces.md)
retain session and materialization boundaries.

`PortableContentPlannerTests`, `PortableArchiveWriterTests`, `LocalPortableContentExportsTests`
and `SessionExportSelectionTests` pin selection, packaging, handles and cleanup;
[native capacity evidence](../../executor-native/evidence/2026-09-11-export-capacity.json)
and [import recovery evidence](../../executor-native/evidence/2026-09-11-import-capacity.json)
record the worker capacity paths. Final production HTTPS and live-corpus
acceptance are installation-level work.
