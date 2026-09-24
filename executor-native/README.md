# Java and sandbox execution acceptance

This probe runs the production Java executor adapter, Unix peer checks, Ed25519 requests, the Python
worker and SRT against a real Linux systemd host, so a failure here names the isolation layer.
Authentication and the Git authority are synthetic fixtures on purpose. The probe does not replace
PostgreSQL integration tests, the HTTP MCP entrance, or [client acceptance](../acceptance/clients/README.md)
with real login, issued API keys and actual Codex and Claude Code clients. The worker's own contract and
probes are in the [worker reference](../executor-service/README.md).

## Prepare

Build the runtime with Java 26:

```sh
./gradlew stageExecutorNativeTest
```

`build/executor-native/runtime` contains only compiled classes, resolved JARs and a SHA-256 manifest;
the probe verifies every manifest entry and rejects unlisted files. Copy that directory, `probe.py`,
`rejected_peer.py` and these worker sources to isolated host staging: `worker.py`, `launcher.py`,
`command_channel.py`, `shell_loop.py`, `resource_pool.py`, `native_pool.py`, `bridge.py`, `cli.py`,
`session_files.py`, `binary_capture.py`, `materialize.py`, `artifacts.py` and `disk_pool.py`. Never stage
operator settings or credentials.

The host needs cgroup v2, systemd, root access, Git, Python with the worker's pinned dependencies and
its prepared SRT toolchain. The probe requires a Java 26 runtime; `prepare-jdk.sh NEW_DIRECTORY`
downloads an isolated Temurin 26.0.2+10 archive and verifies its pinned SHA-256 without installing it
globally. Runtime classes and JARs must be readable by the probe's temporary application account.

## Run

```sh
sudo env PYTHONPATH=/prepared/tools/python python3 probe.py \
  --runtime /staged/runtime \
  --worker-source /staged/worker-source \
  --tools /prepared/tools \
  --java /isolated/jdk-26.0.2+10/bin/java
```

`--scenario` selects what runs; each invocation uses a fresh fixture with its own account-copy pool and
synthetic authority, and checks cleanup. A focused result does not prove the omitted scenarios.

| Scenario | Coverage |
|---|---|
| `all` (default) | The complete adapter batch: peer refusal, lifecycle, copy identity, public scope, every CLI mode, media, exports, artifacts, twenty fixed-commit reuses, cancellation, revocation and worker restart with the sole session slot occupied; then lease expiry after abrupt Java process loss |
| `ephemeral-lifecycle` | Account journal handoff between JVMs, then a real five-second timeout, persistent shell state and `freshSandbox`, preserved text and binary bytes, explicit and repeated disposal, and fresh admission in the same transport |
| `account-state` | Account journal handoff between independent JVMs only |
| `exports` | Private and public CLI ZIP flows |
| `media` | Original fetches, historical versions, local collision protection and member projection access while the anonymous website is disabled |
| `public-scope` | Real public projection: no private files, metadata or history, unchanged copy after a permission increase, publication checks on artifact delivery and after withdrawal |
| `admission` | Worker capacity refusal and a retry that does not leak a copy |
| `peer-only` | Refusal of a non-root worker peer before it receives request bytes |
| `cli-save`, `cli-save-recovery`, `cli-media-import`, `cli-media-link`, `cli-move`, `cli-move-installation`, `cli-move-recovery` | One CLI handler each; `cli-save` includes workspace sync and conflicts, and the recovery modes cover uncertain remote acknowledgement or local installation without repeating the original write |
| `retained-process` | Independent producer and recovery JVMs with external SIGKILL; `--process-case` selects one of `acknowledged`, `interrupted`, `uncertain`, `beforepublish`, `afterpublish`, `discarding` or `expired` |

In `retained-process`, the controller verifies the producer's Java executable and kills it after an
acknowledged command, during a command with an acknowledged host save, after a successful remote push,
around final journal publication, or during disposal. A new JVM opens the same authority without
reseeding, attaches the original copy, checks its local work and saves the recovered draft; the uncertain
case reconciles without a second push. The expiry case advances only the fixture clock, checks that a held
writer prevents deletion, then verifies scheduled cleanup and preserved remote commits.

The complete batch has a 360-second deadline and focused batches 240 seconds. These are whole-test
budgets: every command and lease keeps its own timeout, and cleanup still verifies that no execution
processes remain.

## Fixture

The root controller creates a disposable directory under `/var/lib`, two temporary accounts, a signing
key, synthetic history, transient units and an isolated XFS copy pool. Java runs as the application
account, and the worker launches commands as the distinct execution account. The supervisor uses the
production `UMask=0077` and a disposable finite resource slice shared with its commands. The
controller's test mailbox observes detached descendants, checks unchanged source bytes and restarts only
its own transient worker unit.

Repository copies must never be staged on tmpfs or below broadly writable `/tmp`. SRT 0.0.75 mounts
read grants after write grants, so the worker grants read access to specific session paths, never to
the whole session directory; a fixture under `/tmp` inherits a write grant that hides a violation
([supervisor decision](../notes/implemented/2026-09-05-local-execution-supervisor.md#supervisor-and-worker)).

For the peer check, a root-owned socket is served by the application account. The peer records an
accepted connection and zero request bytes, because the production check must reject it before writing;
an ordinary EOF cannot satisfy that assertion.

Success requires exit zero, Java `summary: PASS`, controller `nativeCombined: PASS` and `cleanup: PASS`.
Cleanup stops all temporary units, checks mounts and process ownership, and removes both accounts, the
signing key and the fixture; runtime staging and the isolated JDK remain for later runs. Output records
the scenario, runtime manifest and source hashes, and a different build requires new evidence.

## Isolated peer regression

This container check exercises the peer-identity assertion through the production configuration and
socket client, with root-owned paths and a non-root peer. It does not run SRT or replace the host probe.

```sh
./gradlew stageExecutorNativeTest
docker build --network none -f executor-native/Dockerfile.peer-tests -t poketto-peer-test executor-native
docker run --rm --network none --read-only --memory 512m --cpus 1 --pids-limit 64 \
  --cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add CHOWN --cap-add DAC_OVERRIDE \
  --security-opt no-new-privileges \
  --tmpfs /run:rw,nosuid,nodev,noexec,size=16m,mode=0755 \
  --tmpfs /tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777 \
  --mount type=bind,source="$(pwd)/build/executor-native/runtime",target=/runtime,readonly \
  poketto-peer-test
```

Success requires Java exit zero, `accepted: true`, `requestBytes: 0` and fixture cleanup. A temporary
build without the production peer guard must fail with nonzero request bytes; restore production source
before delivery. The fixture changes only container-local UIDs, paths and processes and opens no network
listener.

## Evidence

[evidence/](evidence/) holds one JSON record per run, named by date and subject. A record describes only
the build and scope it records; it is not acceptance for changed sources. Timings describe the fixture,
not production sizing.
