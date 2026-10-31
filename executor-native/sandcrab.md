# Native Sandcrab Java consumer

`sandcrab_volume_java_probe.py` exercises the Java client through the public native
API, using a synthetic Git bundle larger than the client's in-memory request limit.
Two Java processes, separated by a daemon restart, verify the Git baseline,
unsaved files, exclusive attachment, independent reflink edits and record release.
They also verify snapshot bytes and digests, binary host-call replies, and output
SSE continuation. Host calls run with one event-observer slot, including a quiet
pending request, so identity validation cannot consume a second slot.
It does not exercise Poketto authorization or establish a product cutover.

```sh
./gradlew stageExecutorNativeTest buildExecutorRust
sudo python3 executor-native/sandcrab_volume_java_probe.py \
  --runtime build/executor-native/runtime \
  --binary build/executor-service/bin/sandcrab \
  --tools /prepared/tools --java /prepared/jdk/bin/java \
  --report /tmp/sandcrab-volume-java.json
```

Use Java 26, Git, XFS tools and the systemd host prerequisites in the
[native volume reference](../executor-service/rust/sandcrab/README.md#durable-volumes).
Keep this script and `native_probe.py`/`volume_probe.py` in their repository layout.
The controller verifies the runtime manifest and creates a disposable 512 MiB XFS
image and 384 MiB slice; its Java processes have a 192 MiB cap inside that slice.
Use a new report path for each attempt. Success requires `result: PASS`, both Java
phases and `cleanup: PASS`; a cleanup failure retains the fixture for inspection.

The [native receipt](evidence/2026-10-31-sandcrab-java-events.json) records eight
Java checks, fixture cleanup and source hashes. The earlier
[volume receipt](evidence/2026-10-31-sandcrab-java-volumes.json) retains its six
checks and setup failure.
