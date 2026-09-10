# Java and sandbox execution acceptance

This probe exercises the production Java executor adapter, Unix peer checks,
Ed25519 requests, the Python worker, and SRT on a real Linux systemd host. It uses
a synthetic Git bundle and an explicit authentication stub. It does not replace
PostgreSQL integration tests, the HTTP MCP entrance, or Codex and Claude Code
client acceptance required by [phase one](../notes/proposed/2026-09-05-phase-one-daily-use.md).

The signed-open result checks production socket ownership, peer identity and signatures. Its authentication stub supplies the allowed principal; that result does not validate database permissions. Recorded evidence retains its original labels and source hashes.

The public-scope scenario uses a separate synthetic Git authority with the production publication and export services. It searches the actual worker copy for private metadata and content, checks that source commit objects are absent, preserves the public scope after a permission increase, and rejects execution after publication withdrawal. The real `poketto status` command exchanges FIFO requests with the host while Unix socket creation stays blocked. Separate clients cannot access each other's bridge, and replies remain read-only. The [recorded projection and bridge run](evidence/2026-09-10-public-projection.json) identifies the tested classes and runtime manifest. The selected-save scenario freezes the actual cgroup, transfers UTF-8 bytes in chunks, and commits through real synthetic Git authority despite tampered sandbox Git state. It covers repeated saves, explicit deletion, unselected local edits and remote conflicts. A lost-response scenario confirms the original commit through `poketto recover` without replaying newer local edits or issuing a duplicate push. Single-file synchronization exercises the signed incoming-file channel, frozen compare-and-replace, three-way conflict markers, explicit local deletions and independent per-file baselines. Indexed-media scenarios fetch exact local originals in the same command, retain historical versions, preserve local edits and restrict public sessions to their host-owned projection mapping. Media import captures binary bytes through the frozen cgroup, preserves idempotent local index formatting, saves the index and referring text atomically, and retains old original versions after replacement. Returned artifacts and HTTP MCP client acceptance remain separate requirements.

The [scoped artifact run](evidence/2026-09-10-scoped-artifacts.json) verifies
immutable file capture, protected artifact storage, isolation by workspace/key/client,
public withdrawal, complete long-output delivery, and output-limit preservation of
the captured prefix and unsaved work. It includes the lifecycle suite and successful
lease/storage cleanup. Actual-client image and binary delivery remains a separate
acceptance requirement.

The [media-list run](evidence/2026-09-10-media-list.json) verifies unsaved import
discovery, versioned pagination, historical catalogs and public metadata isolation
despite local index tampering. Publication withdrawal prevents further listing.
Its 25 Java scenarios, process-loss lease expiry and fixture cleanup pass on native
Linux; authentication remains the explicit synthetic stub described above.

The [CLI move run](evidence/2026-09-10-cli-moves.json) verifies reference repair,
retention of unselected index edits, materialized and absent originals, refusal
of dirty selected files, and recovery of the original uncertain commit without
a duplicate push. All 28 Java scenarios, process-loss lease expiry and cleanup
pass. The [actual-client run](../acceptance/clients/evidence/2026-09-10-cli-moves.json)
separately covers real HTTP MCP and database authentication.

The [move recovery run](evidence/2026-09-10-cli-move-recovery.json) additionally
verifies a lost real installation reply leaves the session usable and recovery
preserves newer local edits. An injected installation refusal exercises
`recover --skip-local`, conservative save conflicts and explicit synchronization
through the actual CLI. All 30 scenarios, process-loss expiry and cleanup pass;
the evidence distinguishes injected faults from real worker behavior.

The [CLI export run](evidence/2026-09-10-cli-exports.json) verifies private/public
ZIP bytes, projection path translation, local collision protection, retained edits,
unchanged remote Git and package cleanup through the real worker. All 32 Java
scenarios, process-loss lease expiry and cleanup pass. The separate
[HTTP MCP client run](../acceptance/clients/evidence/2026-09-10-cli-exports.json)
adds real PostgreSQL identity checks, MCP sessions and artifact byte return.

The [export-capacity run](evidence/2026-09-11-export-capacity.json) fills a real
lease filesystem until only 512 KiB remain. Export returns `MATERIALIZE_CAPACITY`
without losing the session's unsaved files; freeing space permits another command
and identical ZIP reuse. All 32 native scenarios, process-loss expiry and cleanup
pass on the updated worker and adapter. The [authenticated HTTP replay](../acceptance/clients/evidence/2026-09-11-export-capacity.json)
also verifies this recovery through real PostgreSQL identity and MCP sessions.

Build the reproducible runtime with Java 26:

```sh
./gradlew stageExecutorNativeTest
```

`build/executor-native/runtime` contains only compiled classes, resolved JARs,
and a SHA-256 manifest. Copy that directory, `probe.py`, `rejected_peer.py`, and the corresponding
worker, launcher, `resource_pool.py`, `native_pool.py`, `bridge.py`, `cli.py`, `session_files.py`, `binary_capture.py`, `materialize.py` and `artifacts.py` sources to isolated host staging. The probe verifies every
manifest entry before running. It never stages operator settings or credentials.

The host needs cgroup v2, systemd, root access, Git, Python with the worker's
pinned dependencies, and its prepared SRT toolchain. An existing Java 26 runtime
can be used; `prepare-jdk.sh NEW_DIRECTORY` instead downloads an isolated
Temurin 26.0.2+10 archive and verifies its pinned SHA-256 without installing it
globally. Runtime classes and JARs must be readable by the probe's temporary
application account.

`--scenario exports` selects only the real private/public CLI ZIP flows for
focused diagnosis. The default `--scenario all` includes them with the complete
adapter lifecycle checks and process-loss expiry. A focused result does not prove
the omitted scenarios. Each mode uses a fresh native fixture and checks cleanup.

```sh
sudo env PYTHONPATH=/prepared/tools/python python3 probe.py \
  --runtime /staged/runtime \
  --worker-source /staged/worker-source \
  --tools /prepared/tools \
  --java /isolated/jdk-26.0.2+10/bin/java
```

The root controller creates a disposable directory under `/run`, two temporary
accounts, a signing key, synthetic history, and transient units. Java runs as
the application account and the worker launches commands as the distinct
execution account. A root-owned socket served by the application account tests
the negative Unix peer-identity path. The peer records an accepted connection
and zero request bytes: the production check must reject before writing, so an
ordinary EOF cannot satisfy the assertion. The controller's narrow test mailbox
observes detached descendants, checks unchanged source bytes, and restarts only
its own transient worker unit.

The probe checks twenty fixed-commit reuses, independent client directories,
source immutability, cancellation and revocation through the actual adapter,
worker restart with its sole Java session slot occupied, and lease expiry after
abrupt Java process loss. A test-only
observer records failed synthetic initialization output without changing worker
results. The supervisor uses production `UMask=0077` and a disposable finite
resource slice shared with its transient commands. The separate
[aggregate pool probe](../executor-service/README.md#verification) checks retained
tmpfs charges and deployment preflight without replacing this SRT acceptance.

Run the same probe with `--fixture-parent /var/lib` to compare filesystem
topologies without weakening ownership or sandbox checks. SRT 0.0.75 restores
write mounts before read mounts in `pushReadDenyDirMounts`. A read allowance for
the whole session directory can therefore mount its writable children read-only.
The worker must allow only its bootstrap directory and initialization bundle as
read-only paths, while retaining precise write allowances for work and home.
A fixture below broadly writable `/tmp` can hide this mount-order defect.

Success requires exit zero, Java `summary: PASS`, controller
`nativeCombined: PASS`, and `cleanup: PASS`. Cleanup stops all temporary units,
checks mounts and process ownership, removes both accounts, and deletes the
temporary signing key and fixture. Public runtime staging and the isolated JDK
remain available for subsequent tests. Evidence identifies the runtime manifest,
adapter class, worker, launcher, and probe hashes; a different build requires
new acceptance evidence.

The [recorded synthetic run](evidence/2026-09-05-combined.json) retains the
machine-readable results and hashes for both `/run` and `/var/lib`. These timings
describe the fixture, not production sizing. Restart tests deliberately report
unconfirmed old leases; the test requires the old session to stay unusable and a
new session to connect successfully after a real HELLO readiness check.
Saturated admission can retire old leases only when a protected root peer returns
a different boot identity. The worker completes exclusive startup cleanup before
serving HELLO. A failed probe or unchanged identity preserves occupied capacity;
the retired MCP session cannot reopen.

The [media-import capacity run](evidence/2026-09-11-import-capacity.json) verifies
that a stored original retains its receipt when local index materialization runs
out of space. Retrying the same key after freeing space preserves the asset ID
and completes the index update. All 32 native scenarios, process-loss expiry and
cleanup pass. Its [authenticated HTTP replay](../acceptance/clients/evidence/2026-09-11-import-capacity.json)
checks the same recovery through MCP and repeats the export checks.

## Isolated peer regression

The historical combined report predates the accepted-connection and byte-count
assertions. Its unavailable-result assertion could also pass on an ordinary EOF.
The following bounded local check exercises the strengthened assertion through
the production configuration and socket client, using root-owned paths and a
non-root peer inside one container. It does not run SRT or replace the final
combined host acceptance.

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

Success requires Java exit zero, `accepted: true`, `requestBytes: 0`, and fixture
cleanup. A temporary test build omitting the production peer guard must fail
with nonzero observed request bytes; restore production source before delivery.
The fixture changes only container-local UIDs, paths and processes and opens no
network listener. Its runtime manifest identifies the exact compiled input.
