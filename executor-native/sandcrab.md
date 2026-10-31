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

## Live observer faults

`sandcrab_recovery_java_probe.py` keeps one Java process alive while its disposable
daemon is killed and restarted during output observation, call observation and a
host handler. Synthetic host effects stay in the fixture; a refused late reply
does not undo them or prove that the earlier command never executed. The probe
checks narrow event-capacity and call-cursor refusals separately from stream loss
and unconfirmed containment, then executes new identities in the replacement epoch.
Recovery commands are finite native runs; an interrupted observation supplies no
terminal result for the old command.

```sh
sudo python3 executor-native/sandcrab_recovery_java_probe.py \
  --runtime build/executor-native/runtime \
  --binary build/executor-service/bin/sandcrab \
  --tools /prepared/tools --java /prepared/jdk/bin/java \
  --report /tmp/sandcrab-recovery-java.json
```

Use the same staged runtime and repository layout. This fixture uses the native
controller's 384 MiB slice and a 192 MiB Java cap, without a volume image. A temporary
helper refuses cleanup for one owned job; its flag is removed before recovery and
final cleanup. The controller verifies old cgroups, units and runtime directories
after each restart. Stream failure itself never supplies that proof. All four Java
recoveries and final `cleanup: PASS` are required; every attempt uses a new report
path. Java publishes its structured result in an exclusive control file; the
controller retains it as `.java.json` before parsing. Standard output and error
remain diagnostic streams. On Linux, running
`python3 executor-native/test_sandcrab_recovery_java_probe.py` checks this result
boundary without a host probe. These checks do not establish complete Poketto
consumer acceptance.
