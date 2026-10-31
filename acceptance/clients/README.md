# Actual MCP client acceptance

This is where the real path is exercised: account login, normally issued API keys, PostgreSQL, the
HTTP MCP entrance and native sandbox service running together. Each run below links the evidence file it
produced, naming the scenarios, the tested revision and the observed result. The layered probe in
[sandbox verification](../../executor-native/README.md) stubs authentication on purpose so that its
result names the isolation layer alone; these runs are the authenticated counterpart.

The [account-copy HTTP run](evidence/2026-09-14-account-copies.json) verifies shared copies across independently issued grants and a new MCP transport on every call. All five scenarios pass through real login, PostgreSQL and native SRT: restricted-scope refusal, timeout preservation, authoritative save readback, public-copy scope preservation, grant revocation without deleting account work, and idempotent account-owned disposal. The fixture uses scaled disk limits.

The [four-tool entrance run](evidence/2026-09-10-codeact-mcp-entrance.json)
uses an independent Python HTTP MCP client with real authentication, PostgreSQL
and native SRT. It verifies image transfer, selected CLI saves, atomic moves,
scratch retention, artifact bytes, public projection isolation, capability denial
and revocation. Authoritative HTTP readback and controller cleanup pass. This
deterministic protocol run supplements the model-driven workflows below.

The [retained HTTP run](evidence/2026-09-13-retained-http.json) uses real account
login, normally issued API keys, PostgreSQL and native SRT. Each tool call opens
and closes a separate MCP transport. Private draft text and binary bytes survive
explicit recovery at the original commit; stale generations cannot execute, and
restored drafts save with authoritative HTTP readback. Discard is owner-bound and
idempotent without undoing remote Git. Public projections recover without private
files, and revoked credentials cannot initialize another session. All five checks
and controller cleanup pass. This deterministic run supplements the model-driven
retained-copy acceptance below.

The [retained model-client run](evidence/2026-09-14-retained-models.json) uses actual
Codex and Claude Code processes with real account authentication, PostgreSQL and
native SRT. For each client, one process leaves verified unsaved text and binary
files; a second explicitly resumes the same copy and generation at its original
commit, saves only the text and discards the retained work. Repeated discard returns
`ABSENT`, and later recovery returns `MISSING_COPY` without executing a command.
Independent tool transcripts, authenticated HTTP and remote Git confirm exactly
two selected-file commits and no saved binary scratch files. Cleanup passes.
Both clients recover from the fixture's absent root guidance; Codex corrects an
unavailable `python` command to `python3`, and Claude removes an unsupported save
option after reading help. These recoveries are recorded rather than counted as
first-attempt success. Final deployed HTTPS and chat-app OAuth acceptance remain
pending.

The [focused CLI move run](evidence/2026-09-10-cli-moves.json) verifies actual
Codex calls through real Spring authentication, PostgreSQL, HTTP MCP and native
SRT. A folder move repairs inbound and outbound links and relocates its indexed
original, which is fetched after the move. Unselected scratch and index edits
remain local; a dirty selected file prevents another move. Independent remote Git
and authenticated HTTP readback confirm exactly two new commits (save and move),
with no unrelated edits included. Fixture cleanup passes. Lost acknowledgements
and lease isolation are covered by the separate native and protocol tests.

The [focused media-list run](evidence/2026-09-10-media-list.json) verifies one
actual Codex client discovers two unsaved imports, follows versioned pages,
rejects a changed index and reads an empty historical catalog. Independent
inspection confirms the synthetic remote still contains only its initial commit.
This run uses real Spring authentication, PostgreSQL, HTTP MCP and native SRT;
public projection isolation remains covered by the native executor probe.

On Linux systemd, `native-host.py` runs synthetic Spring, pinned disposable
PostgreSQL and independent Sandcrab services.
Stage `./gradlew stageAcceptanceRuntime buildExecutorRust`; the runtime includes
a sorted SHA-256 manifest checked before startup. Pass `--runtime`, `--binary`
(`build/executor-service/bin/sandcrab`), `--tools` and `--java`, plus optional
loopback `--port`. `--agents N` (default 4) sets native admission, application
ceilings and the fixture slice, without establishing measured capacity.

Run as root. The ready receipt names the fixture root;
only root can read its disposable password in `client.json`. Obtain API
keys through normal HTTP administration. Forward only the loopback application
port when clients run elsewhere. Create `stop` in the fixture root when finished.
The controller bounds its lifetime and confirms owned process
containment before removing mounts, accounts and secrets. Require `cleanup: PASS`;
failure retains the private fixture for inspection. Readiness establishes setup;
[process-loss acceptance](retained-process.md) covers recovery separately.

`native-load.py` takes these paths and a fresh `--evidence FILE`. It runs the
consumer checks, issues a full key through real login, then runs one judged
[load suite](../README.md) stage of eight agents. Passing requires
at least 99% successful calls and a 95th percentile below one second after the
first 30 seconds. Logs, process identity, consumer results and cleanup
are retained beside the load receipt. An exclusive marker preserves earlier runs.
CI's `load` lane uses this entrance; its separate
legacy supervisor OOM guard is not evidence for Sandcrab service OOM recovery.

The controller owns two 512 MiB XFS filesystems: one for application journal,
export staging and transfers, and another for native volume data with enforced
project quotas. Sandcrab's control metadata stays outside the volume data
filesystem. Repository copies have 64 MiB and 8,192-inode limits, and the profile
permits opaque host calls while keeping the guest network disabled.

`native-smoke.py --fixture FIXTURE_ROOT --evidence NEW_FILE` exercises real login,
issued grants and independent MCP transports. It correlates the returned
copy with the native volume API, verifies shared unsaved bytes, revoked-key
denial, selected save/delete with authoritative HTTP readback, and idempotent
disposal. Require its `result: PASS` and controller `cleanup: PASS`. The historical
receipts above retain their own backend and scenario scope; these basic checks do
not establish complete CLI, browser, model-client or production acceptance.

Use the isolated [acceptance stack](../README.md) first. Create a workspace API key through administration with `READ_PRIVATE`, `WRITE_PRIVATE`, and explicitly selected `EXECUTE_REPOSITORY`. Keep the key and endpoint in process-local `POKETTO_MCP_TOKEN` and `POKETTO_MCP_URL`. Do not commit real endpoints, tokens, or client transcripts containing private content.

The historical [non-retained HTTP run](evidence/2026-09-14-ephemeral-lifecycle.json) covers the earlier timeout and explicit-disposal implementation at its recorded revision. Current account-copy acceptance uses the controller and client described above.

The [focused artifact acceptance](evidence/2026-09-10-artifacts.json) verifies both
clients receive actual PNG image content, read the exact end of a long text
artifact, receive four exact binary bytes and reject a removed handle. Raw image
and resource bytes are independently compared with returned hashes. Claude Code
writes binary resources to local files instead of exposing their base64 directly
to the model; the verifier reads that file to confirm delivery. The synthetic
remote remains at its initial commit and fixture cleanup completes. This workflow
does not repeat the separate save/media acceptance.

Codex supports Streamable HTTP with a bearer token read from an environment variable; its [official configuration reference](https://learn.chatgpt.com/docs/extend/mcp?surface=cli) documents connection and tool timeouts. The adjacent TOML fragment supplies the isolated endpoint. For a single CLI run, pass the same fields with `codex exec -c` overrides rather than changing global configuration.

Claude Code accepts the adjacent JSON with `--mcp-config` and `--strict-mcp-config`. Its [official MCP documentation](https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcp-json) specifies environment expansion in URLs and authentication headers. A connected status establishes only discovery, not tool acceptance.

For each client, use actual model-driven calls to discover directories, guidance, original history and the private sentinel. Read a Git image through MCP image content. Generate a small valid image inside the repository with Python 3's standard library, import it twice with the same idempotency key and verify the same original identity. Fetch it and compare exact bytes. Save a relative-link note and its index together while leaving an unselected local scratch file. Use authoritative readback to prove the note/index persisted and the scratch file did not. Simulate one competing write through the browser HTTP editor or a second MCP execution session, then exercise a real CLI save conflict, workspace sync, marker resolution and another save preserving both writers' content. Move the committed sample with `poketto move`; verify both paths before deleting only that sample. Keep imported media for independent readback. An unconfirmed write must be reconciled before retry.

Record the client version, tested application commit, actual discovered tools, each tool outcome, and final repository readback. Keep raw sample transcripts outside tracked source and redact credentials. The execution tool is absent while the isolated worker is disabled; that run cannot complete CodeAct acceptance. Repeat the agreed workflows against the final HTTPS installation before marking phase one complete. Protocol tests and HTTP probes supplement these runs; neither replaces them.

The [recorded CodeAct run](evidence/2026-09-10-codeact.json) covers both actual clients with real authentication and native SRT. Independent Git inspection verifies same-commit index/text saves, resolved contents, final deletion and preservation of all pre-existing files. It does not cover dedicated CLI move/export, artifact returns or bootstrap-guide injection. Both clients recovered from filesystem/toolchain misunderstandings; CLI help now states repository-relative paths, per-command `/tmp` lifetime and `python3` explicitly.
