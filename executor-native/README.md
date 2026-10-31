# Java and sandbox execution acceptance

The [native Sandcrab consumer](sandcrab.md) and [client acceptance](../acceptance/clients/README.md)
exercise the public service and current HTTP MCP entry. The isolated peer regression below uses the
production native Java client. `probe.py` exercises the retained signed executor, with synthetic
authentication and Git authority; its results do not establish native repository adoption. Its
process-loss scenarios remain visible until their native replacements provide equivalent evidence.
See the [worker contract and probes](../executor-service/README.md) for the signed protocol.

## Prepare

Build the runtime with Java 26:

```sh
./gradlew stageExecutorNativeTest stageExecutorWorker
```

`build/executor-native/runtime` contains compiled classes, game resources, resolved JARs and a SHA-256 manifest;
the probe verifies every manifest entry and rejects unlisted files. Copy that directory, `probe.py`,
`rejected_peer.py` and `build/executor-service/worker` to isolated host staging. Preserve the latter's
root-level `poketto-supervisor` and `poketto-launch`, `bin/` directory and executable permissions. Never stage operator settings or credentials.

For `--scenario games`, stage `game_fixture.py` beside `probe.py`. Add `sandcrab`
and `game-example.mjs` from `./gradlew stageGameWorker` to the worker sources.
Poketto supplies its game runtime.

The host needs cgroup v2, systemd, root access, Git, Python with the worker's pinned dependencies and
its prepared sandbox toolchain. The probe requires a Java 26 runtime; `prepare-jdk.sh NEW_DIRECTORY`
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
| `admission` | Capacity refusal without copy leakage; startup retries reuse leases without executing rejected commands |
| `peer-only` | Refusal of a non-root worker peer before it receives request bytes |
| `games` | Separate game worker and slice: shared puzzle, complete saves, host/network denial, resource exhaustion, descendants, revocation, restart and repository execution during full game admission |
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

Repository copies must never be staged on tmpfs or below broadly writable `/tmp`. The worker grants
read access to specific session paths, never to the whole session directory; a fixture under `/tmp`
inherits a write grant that hides a violation
([supervisor decision](../notes/implemented/2026-09-05-local-execution-supervisor.md#supervisor-and-worker)).

For the peer check, a root-owned socket is served by the application account. The peer records an
accepted connection and zero request bytes, because the production check must reject it before writing;
an ordinary EOF cannot satisfy that assertion.

Success requires exit zero, Java scenario assertions (`gameNative: PASS` for games),
controller `nativeCombined: PASS` and `cleanup: PASS`.
Cleanup stops all temporary units, checks mounts and process ownership, and removes both accounts, the
signing key and the fixture; runtime staging and the isolated JDK remain for later runs. Output records
the scenario, runtime manifest and source hashes, and a different build requires new evidence.

## Isolated peer regression

`peer_probe.py` runs `NativePeerProbe` through the production native configuration and HTTP socket
client, with root-owned paths and a non-root peer. It requires neither a signing key nor the retained
executor. It does not run a sandbox or replace host containment or application authorization checks.

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

## Launcher comparison

[sandbox_probe.py](sandbox_probe.py) and [sandbox_snapshot.py](sandbox_snapshot.py) compare the
current launcher with a supplied reference on one prepared x64 host, without application copies.
Run as root with `--worker-source`, `--tools` and `--output`. An SRT comparison
also takes `--reference-launcher` and its Python `--reference-driver`.
Each path runs three serial fresh shells at 50% CPU and 192 MiB. The probe verifies live mounts,
permissions, environment differences, seccomp, visible processes and tool tampering, records cgroup
CPU/memory/tasks, and requires each direct/reference median resource ratio to be at most one third.
Add `--toolkit` and [sandbox_toolkit.py](sandbox_toolkit.py) to verify installed image, PDF, Office,
archive and Git operations. Require exit zero and `cleanup: PASS`; these figures do not establish HTTP capacity.

`--reference-worker-source` takes a previous complete staged tree. With a Python
driver, add `--idle-seconds 60`; the idle CPU ratio must be at most one tenth. With
`launcher.py` and the native driver, only the launchers differ and snapshots must match.
Either way startup, memory and task ratios are reported without the SRT threshold.

## Evidence

[evidence/](evidence/) holds one JSON record per run, named by date and subject. A record describes only
the build and scope it records; it is not acceptance for changed sources. Timings describe the fixture,
not production sizing.
