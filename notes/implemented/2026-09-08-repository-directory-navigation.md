# Repository Directory Navigation

Date: 2026-09-08

The current agent file entrance is [CodeAct](2026-09-10-codeact-mcp-entrance.md). Standalone MCP file
CRUD described here is superseded; the shared domain and browser HTTP contracts
remain applicable.

## Problem

An agent without a known path cannot discover content through `get_file`. The optional executor supports exploration, but ordinary directory browsing should also work with a read-only key and no worker. The browser's structured Markdown tree excludes non-Markdown files and parses document bodies; it is not a filesystem listing.

## Decision

Expose `list_directory` over the shared authorized repository reader. It reads committed Git tree entries and the bounded [logical media index](2026-09-09-logical-media-index.md), without opening original media or invoking the executor. Omitted or empty `path` selects root. Entries retain repository-relative names and identify files, directories, symlinks and submodules; listing an entry does not make it readable by the text or image tools. Symlinks and submodules are never followed.

The default page size is 100, with a maximum of 200. `offset` is between zero and 100,000. Without indexed media, each call visits at most `offset + limit + 1` immediate entries. The logical media record owns the bounded whole-tree collision check when indexed paths are present. A continuation beyond the maximum offset fails the page explicitly; the reader never emits an unusable offset or silently reports an incomplete directory as complete. Results include the resolved `commit`, `path`, `expectedAbsence`, `entries` and nullable `nextOffset`. Continuations require the first page's commit, path and returned offset. Git tree order is stable within that commit. Missing paths return an empty page with expected absence; selecting a non-directory is invalid. An unborn repository has an empty root and a null commit. Unsafe request paths, invalid bounds and commits outside the authorized workspace's main history fail through existing error boundaries. An entry beyond the repository path-length bound fails its page instead of emitting unbounded metadata or truncating it into another path.

`READ_PRIVATE` authorizes listings. `get_file` and `repo_patch` retain their existing read, revision and publication contracts. Basic discovery does not require `EXECUTE_REPOSITORY`.

Content repositories may use a root `AGENTS.md` for purpose, navigation and general maintenance rules, with directory-local guides for more specific knowledge. Guides explain responsibilities and link useful entry points rather than duplicating an exhaustive file inventory. Agents read relevant guides and original content progressively, and maintain affected guidance with an authorized structural change. Guides remain ordinary Git text; Poketto does not inject, interpret or enforce their prose. Existing publication exclusions can keep them out of the blog.

## Alternatives

Requiring the executor for every discovery step ties basic reading to an optional service and a stronger capability. Reusing the Markdown tree loses ordinary files and adds parsing and history work. Automatic guide injection, durable semantic indexes and a separate skill format add mechanisms before client evidence establishes a need. Generic directory reads and repository-owned prose preserve the agent's ability to investigate unfamiliar content.

## Verification and consequences

Focused repository tests cover raw entries, malformed and oversized blobs, nested names, pagination across remote advancement, missing directories, symlinks and workspace isolation. Real Streamable HTTP tests exercise discovery and guide reading with a read-only key while the executor is absent, assert the complete response shape, and reject invalid inputs and unauthorized callers. These checks do not establish that every AI client follows repository guidance; fresh-client task evaluation remains a separate acceptance activity.

The [retrieval proposal](../proposed/2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) retains composable execution and its isolation boundary; this decision extends its selection of basic read tools. [Phase-one delivery](../proposed/2026-09-05-phase-one-daily-use.md) retains its remaining client and installation acceptance. The logical media record extends the file namespace through a Git index; directory access adds no database state or execution authority.
