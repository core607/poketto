# Native repository and game acceptance

The workspace and game probes exercise Poketto's Java adapters against Sandcrab on
a disposable Linux systemd host. They supply synthetic identities and Git
repositories. [HTTP/MCP client acceptance](../acceptance/clients/README.md) covers
actual login, PostgreSQL and issued grants. Browser iframe behavior uses the
[browser entrance](../acceptance/README.md).

## Prepare

Use Java 26 and a release archive matching the [consumer pin](../executor-service/sandcrab-release.properties).

```sh
./gradlew -PsandcrabBundle=/prepared/sandcrab.zip stageGameWorker stageExecutorNativeTest
```

The staged game bundle contains the executable, Bubblewrap, its source and notices.
`build/executor-native/runtime` contains compiled classes, game resources, JARs
and a SHA-256 manifest. The controllers verify each entry and reject unlisted
files. Keep the controllers in their checkout layout so they can load the shared
fixtures and game example. Make the runtime and isolated JDK readable by the
temporary application account.

The host needs root access, systemd, delegated cgroup v2, Git, Python, XFS tools
and the prepared sandbox toolchain with Node. `prepare-jdk.sh NEW_DIRECTORY`
downloads the pinned Java 26 runtime. Restricted hosts also load the
[AppArmor profile](../acceptance/clients/README.md).

## Release acceptance

Supply a readable release ZIP and a new, empty, writable results directory.

```sh
SANDCRAB_BUNDLE=/prepared/sandcrab.zip \
SANDCRAB_EVIDENCE=/prepared/results SANDCRAB_INSTALL_DISPOSABLE=1 \
  bash .github/ci/sandcrab-release-check.sh
```

This entry checks the running executable's digest, workspace recovery, game
confinement and authenticated clients. It prepares an isolated JDK and toolchain,
runs the bounded client stage and preserves receipts on success and failure.
The public CI gate checks the [maintainer-issued release receipt](../acceptance/clients/README.md).

## Repository workspaces

`overlay_copy_probe.py` exercises the configured Java repository adapter with
commit templates, independent writable layers and XFS journals. It checks
reconnection after process loss, scope-specific projections, selected Git saves
and interrupted baseline installation. Its output directory contains the
aggregate `summary.json` and the individual process logs.

```sh
sudo env SANDCRAB_INSTALL_DISPOSABLE=1 python3 executor-native/overlay_copy_probe.py \
  --uid "$(id -u daemon)" --gid "$(id -g daemon)" \
  --java /prepared/jdk/bin/java --binaries build/game-worker \
  --runtime build/executor-native/runtime --output /prepared/workspace-results
```

[Native Java API and migration acceptance](sandcrab.md) owns the additional
volume, observer-fault and offline-import checks.

## Games

`native_game_java_probe.py` runs the Java game adapter with a separate service,
execution identity and resource slice. The fixed checks cover the example game,
host and network confinement, exhausted resources, revocation, orphan discovery
and daemon restart. Kernel process identities establish that a game job and a
repository command ran together in separate cgroups. The repository adapter
retains its copy across reads and disposes it at the end.

```sh
sudo python3 executor-native/native_game_java_probe.py \
  --runtime build/executor-native/runtime --binary build/game-worker/sandcrab \
  --tools /prepared/tools --java /prepared/jdk/bin/java \
  --report /prepared/game-results/summary.json
```

Use a new report path in an existing private directory. The controller bounds its
own address space, the Java process and the two native services. It owns a
512 MiB XFS image, temporary accounts and transient units. Requests from Java name
fixed actions and carry a sequence and fixture identity. The controller decides
which owned service to inspect or restart.

Success requires Java's structured result, all seven game checks, three
repository reads, simultaneous kernel observations, released session/template
records and `cleanup: PASS`. Failed attempts retain process output, service
journals and the owned cgroup inventory before cleanup. An incomplete cleanup
retains the private fixture for inspection.

## Unix peer regression

This container check exercises the production socket client with a root-owned
socket served by a non-root peer.

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

Require Java exit zero, an accepted connection, zero request bytes and fixture
cleanup. A temporary build with the peer guard removed must transmit bytes and
fail the assertion. Restore the guard before delivery.

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

`--reference-worker-source` selects a staged reference. Python drivers require
`--idle-seconds 60` and an idle CPU ratio at most one tenth. Native-driver snapshots
allow only the [filter replacement's](../notes/implemented/2026-11-02-native-seccomp-filter.md)
init, capability bounding and environment differences. Startup, memory and task
ratios have no SRT threshold.

## Evidence

Commit aggregate summaries under [evidence/](evidence/). Retain raw process and
service output in CI artifacts or protected operator storage. Each result names
the exact binary and runtime manifest it exercised.
