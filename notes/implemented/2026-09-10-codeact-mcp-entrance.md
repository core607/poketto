# CodeAct MCP File Entrance

Date: 2026-09-10

## Problem

Standalone file-read, directory-list and patch tools duplicate operations available
inside the isolated workspace and its host-mediated CLI. Two agent file APIs give
clients different baselines and persistence paths to coordinate, and require the
service to maintain a second set of schemas and protocol mappings.

## Decision

`repo_exec` is the agent file entrance. Agents use shell, Python, Git and search to
inspect authorized files, read repository-owned `AGENTS.md` guides and edit local
content. The `poketto` CLI mediates selected saves, media, moves, synchronization
and recovery through existing authorization and atomic remote writes. Shell edits
and local Git commits do not acknowledge a remote save.

The MCP catalog contains `repo_exec`, `get_artifact`, `get_asset` and `put_asset`
when the isolated executor and asset services are available. `get_artifact`
returns session-scoped results; the two asset tools retain the bounded external
image transfer channel. `list_directory`, `get_file` and `repo_patch` are removed,
including their request mappings and service dependencies. Unknown tool names are
rejected by the protocol; no alias or compatibility fallback invokes them.

File access requires an enabled, verified executor and `EXECUTE_REPOSITORY`.
Full readers also need `READ_PRIVATE`; public-only execution receives the current
approved projection without private metadata or original history. These scopes,
bounded lifetimes and revocation checks remain owned by the
[worker contract](../../executor-service/README.md). Without an executor, the
catalog retains only available image transfer tools and cannot read or edit text.

The [directory reader](2026-09-08-repository-directory-navigation.md) and the shared
read/patch services remain behind browser HTTP and host operations. Their
authorization, pagination, revision checks and write recovery are unchanged.
Removing a model-facing tool does not remove its underlying domain capability.

## Alternatives and consequences

Keeping standalone tools as a worker-free mode would retain the duplicated
maintenance and two persistence models. A generic server shell would remove the
isolation and host authorization boundary. Neither is retained.

Clients must enable the executor and grant its capability for file work. Existing
prompts that call removed tools need to use ordinary commands and the CLI instead.
The service enforces scope and workspace boundaries; external harnesses continue
to interpret user intent and handle instructions found inside task data. No
reviewer agent or mandatory approval step is added.

## Verification

Catalog tests cover enabled and absent executors. Real HTTP MCP integration checks
the reduced catalog, explicit rejection of old names, image bytes, oversized
requests rejected before uploads run, key/session isolation and revocation.
Artifact and image-admission tests retain result bounds and cleanup coverage.
The [authenticated HTTP client run](../../acceptance/clients/evidence/2026-09-10-codeact-mcp-entrance.json)
exercises the four-tool catalog against real Spring authentication, PostgreSQL
and native SRT. Authoritative readback confirms selected saves and moves preserve
unselected scratch; exact image and artifact bytes, public projection isolation,
capability denial, revocation and fixture cleanup pass. This deterministic client
run does not establish model-driven or final production HTTPS acceptance.
