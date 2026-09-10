# Portable Content Exports

Date: 2026-09-10
Status: Proposed

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
from the sandbox. An empty or missing selection fails explicitly.

The package contains selected documents and their required local media. Directory
selection includes indexed media beneath that directory. Article hyperlinks do
not recursively add other articles; external URLs are never fetched. Resolve
relative references, legacy managed image references and eligible Git images
using their exact committed versions. Missing originals or unauthorized local
dependencies fail the entire request. Do not return a partial archive as complete.

Documents occupy a content namespace and media a separate package namespace.
Rewrite only resolvable destinations to package-relative paths, preserving
fragments. Links between selected articles also resolve inside the package.
Private exports preserve authorized source; public exports use only approved
article fields and sanitized bodies, never private frontmatter or original source
coordinates. Neither scope includes `.git`, credentials, the internal media index,
physical storage paths or runtime guides. Every ZIP entry has a validated relative
name; reject normalized collisions, traversal, symlinks and special files.

## Storage and delivery

Create the archive in protected local staging with bounded streaming. Defaults
are 512 MiB of originals, 10,000 entries and one concurrent build per instance.
Also bound text, compressed output, build time, retained packages and total staging
bytes; limits include metadata and ZIP overhead. Reserve capacity before building,
publish the handle only after the ZIP is closed and verified, and remove partial
files on every failed build. A restart removes abandoned disposable packages.

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

The prepared `PortableContentPlanner` selects committed Markdown and indexed
media, resolves required managed originals and Git attachments, and produces
host-only entries for `PortableArchiveWriter`. Private source retains frontmatter;
public packages use approved fields, sanitized bodies and generated article names.
Reference discovery is shared within a plan, bounded to 100,000 unique public
destinations, and public Markdown rendering enforces node and depth bounds.
Publication checks include referenced Git object identities and path eligibility,
so unchanged article text cannot keep an excluded image authorized.

Real Git and native local-storage tests verify mixed media bytes, relative links,
private metadata removal, missing dependencies, cross-workspace identity denial,
private-only updates and image withdrawal. Protected staging, expiring handles,
download authorization, browser and CLI integration remain required before the
export feature is available.

Reusing Git bundles would expose history and omit original media. Asking the
agent to assemble every package duplicates authorization, reference rewriting and
failure handling across clients. A permanent public download URL would turn an
export into publication. A shared deterministic service avoids those changes.

The [logical index](../implemented/2026-09-09-logical-media-index.md) retains media
ownership, [indexed delivery](../implemented/2026-09-09-indexed-media-delivery.md)
retains exact-original transfer, and [CodeAct workspaces](2026-09-09-codeact-workspaces.md)
retains session and materialization boundaries. These decisions remain active;
this record does not replace the separate public-root conversion.

Required evidence covers real Git and local originals, ZIP contents and relative
links, mixed media, missing/corrupt bytes, private selection refusal, cross-workspace
and cross-client handle denial, publication withdrawal, limits and cleanup. Run
native Linux storage checks and actual browser/CLI acceptance through the shared
service. Model benchmarks are not an export correctness gate.
