# Native consumer and sandbox acceptance

The [Sandcrab consumer probes](sandcrab.md), [durability probes](durability.md) and
[HTTP client acceptance](../acceptance/clients/README.md) exercise the public service
and current application entrypoints. Each entrance documents its isolated accounts,
resource bounds, staged inputs and required cleanup result. The peer regression below
checks the production Java transport without running a sandbox.

Stage the runtime required by the selected entrance with Java 26. Native Java probes
use `./gradlew stageExecutorNativeTest buildExecutorRust`; HTTP clients additionally
use `./gradlew stageAcceptanceRuntime`. The staged runtimes include sorted SHA-256
manifests that controllers verify before startup. Stage only the listed runtime,
programs and toolchain, never operator settings or credentials.

A successful command or ready receipt alone is not acceptance. Require the entrance's
scenario assertions and confirmed process, mount and account cleanup. Preserve failed
attempts and match evidence to the tested runtime and binary hashes. Protocol peers,
real native fixtures and production checks establish different boundaries.

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
