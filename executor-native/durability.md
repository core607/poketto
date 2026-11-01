# Native process durability

`sandcrab_durability_probe.py` runs five producer/resume JVM pairs through
`NativeRepositoryRuntime.create`, the real Unix client, JGit authority and bounded
XFS journals and volumes. Authorization is a fixed synthetic grant; this probe
does not exercise HTTP authentication. Test-only spies pause the real persistence
delegate at the selected checkpoint. No legacy executor shim or production
failure switch participates.

```sh
./gradlew stageExecutorNativeTest buildExecutorRust
sudo python3 executor-native/sandcrab_durability_probe.py \
  --runtime build/executor-native/runtime \
  --binary build/executor-service/bin/sandcrab \
  --tools /prepared/tools --java /prepared/jdk/bin/java \
  --revision "$(git rev-parse HEAD)" --report /tmp/native-durability.json
```

Use the [native host prerequisites](../executor-service/rust/sandcrab/README.md)
and Java 26 on an isolated systemd host with pidfd support. Keep the repository
layout and use a fresh report path. The controller verifies every staged runtime
byte, then owns two 512 MiB XFS images, a 384 MiB native slice and one serial
384 MiB Java unit. Each Java unit has a 180-second runtime limit. Work has a
1500-second deadline followed by 180 seconds for cleanup. No retry starts another
producer after an unknown or failed attempt.

| Window | Required checkpoint and recovery |
| --- | --- |
| Successful remote push, missing acknowledgement | The real remote already names the durable uncertain attempt while its native command remains active. Recovery keeps the exact candidate, reconciles it and makes zero additional pushes. |
| Before `RUNNING` to `READY` publication | The native command has succeeded, but the actual journal remains `RUNNING`. Reopening preserves its interrupted ID, local bytes and immutable baselines without replay. |
| After publication, before response | The actual journal is `READY` and the native command has succeeded. Reopening retains completion and does not invent an interrupted command. |
| Discard after native deletion, before journal removal | The volume is actually `deleted`; the journal remains `DISCARDING`. Reopening finishes removal for that identity and repeated discard is absent. |
| Expiry after process loss | Only the test store clock advances eight days. A held real account lock prevents collection; scheduled maintenance after release confirms native deletion before removing the journal. Saved remote content survives. |

Before signaling, the controller independently checks the checksummed journal
frame, acknowledged file frame, original archive hash, native session/run/volume
states and actual Git remote. The private checkpoint must name the observed
MainPID and exact staged classes. A pidfd targets that JVM only after its start
ticks, executable and cgroup are rechecked. A missing session receipt alone never
proves deletion; the discard checkpoint also requires a deleted volume and an
empty guest cgroup hierarchy.

Success requires all five case results, producer SIGKILL evidence, unchanged
remote history during resume, terminal native records, no guest processes and
`cleanup: PASS`. The controller forgets only confirmed terminal fixture receipts
between cases, keeping the same native epoch. It retains every Java stream,
checkpoint, result and synthetic source/journal archive before cleanup. Failed
containment retains the private root. No partial result establishes production
migration or complete consumer acceptance.

`NativeDurabilityBarriersTests` checks actual file-write ordering around the
test barrier. `python3 executor-native/test_durability_probe.py` checks the disk
and native-state observations and owned pidfd signal. These offline checks do
not substitute for the five real process windows.
