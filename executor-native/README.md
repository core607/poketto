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

## Native toolkit

[native_toolkit_probe.py](native_toolkit_probe.py) executes [sandbox_toolkit.py](sandbox_toolkit.py)
through the public native API after the [content tools](../executor-service/install-sandbox-tools.sh)
are installed. Run as root with `--binary`, `--tools` and a new `--report` path. The disposable
384 MiB slice contains the supervisor and a 192 MiB, thirty-second command with 8 MiB temporary
storage. Assertions cover image/font/WebP, HTML/XML, DOCX, XLSX, PDF copy/render, ZIP, awk and Git.
Require `result: PASS` and `cleanup: PASS`. The `load` CI lane runs this entrance before HTTP load;
it establishes tool behavior in the native boundary, not production capacity.

## Launcher comparison

The dated [launcher comparison](evidence/2026-10-27-sandcrab-launcher-comparison.json) and other
historical receipts retain their original binaries, limits and conclusions. Their retired signed
launcher is not built by the current tree. Use the public native probes for current containment;
a new performance comparison must fix its workload, bounds and repetition rules before collection.

## Evidence

[evidence/](evidence/) holds one JSON record per run, named by date and subject. A record describes only
the build and scope it records; it is not acceptance for changed sources. Timings describe the fixture,
not production sizing.
