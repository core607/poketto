# Java and sandbox execution acceptance

This probe exercises the production Java executor adapter, Unix peer checks,
Ed25519 requests, the Python worker, and SRT on a real Linux systemd host. It uses
a synthetic Git bundle and an explicit authentication stub. It does not replace
PostgreSQL integration tests, the HTTP MCP entrance, or Codex and Claude Code
client acceptance required by [phase one](../notes/proposed/2026-09-05-phase-one-daily-use.md).

Build the reproducible runtime with Java 26:

```sh
./gradlew stageExecutorNativeTest
```

`build/executor-native/runtime` contains only compiled classes, resolved JARs,
and a SHA-256 manifest. Copy that directory, `probe.py`, and the corresponding
worker and launcher sources to isolated host staging. The probe verifies every
manifest entry before running. It never stages operator settings or credentials.

The host needs cgroup v2, systemd, root access, Git, Python with the worker's
pinned dependencies, and its prepared SRT toolchain. An existing Java 26 runtime
can be used; `prepare-jdk.sh NEW_DIRECTORY` instead downloads an isolated
Temurin 26.0.2+10 archive and verifies its pinned SHA-256 without installing it
globally. Runtime classes and JARs must be readable by the probe's temporary
application account.

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
the negative Unix peer-identity path. The controller's narrow test mailbox
observes detached descendants, checks unchanged source bytes, and restarts only
its own transient worker unit.

The probe checks twenty fixed-commit reuses, independent client directories,
source immutability, cancellation and revocation through the actual adapter,
worker restart with its sole Java session slot occupied, and lease expiry after
abrupt Java process loss. A test-only
observer records failed synthetic initialization output without changing worker
results. The supervisor uses production `UMask=0077`.

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
