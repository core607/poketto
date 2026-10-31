# Native retained-process acceptance

`native-retained-process.py` launches one disposable `native-host.py` with real
Spring, PostgreSQL, issued API keys, HTTP MCP and Sandcrab. It requests two external
SIGKILLs of that fixture's app JVM: after an acknowledged save, then during a
command whose save is acknowledged while a detached guest descendant remains
live. Sandcrab and PostgreSQL keep their original identities. Repository authority
is the fixture's local Git remote. No legacy executor shim or production fault
switch participates.

```sh
./gradlew stageAcceptanceRuntime buildExecutorRust
sudo python3 acceptance/clients/native-retained-process.py \
  --runtime build/acceptance/runtime --binary build/executor-service/bin/sandcrab \
  --tools /prepared/tools --java /prepared/jdk/bin/java \
  --revision "$(git rev-parse HEAD)" --evidence /tmp/native-retained-process.json
```

Use the [native host prerequisites](README.md), a new evidence path and an isolated
systemd host. The fixture uses one agent, the host's two 512 MiB XFS pools, a
512 MiB native slice, a 768 MiB app cap and 256 MiB PostgreSQL cap. The controller's
900-second lifetime includes both restarts; each new app unit receives only the
remaining time. Root-only control files bind a monotonically increasing sequence
to the exact owned MainPID, process start ticks and cgroup. A pidfd targets that
process; a stale identity refuses the signal. At most two restarts are admitted.
The test-only app reopens only an explicitly matching fixture identity and never
reseeds its existing Git or database.

The observer verifies the same account, copy and initial commit through fresh
logins/transports; unsaved text, binary, home and index bytes; original archive
hash and acknowledged file versions; and the completed save receipt. During the
active-command window it records the native execution ID and an actual detached
PID inside the session slice. Recovery must preserve the interrupted identity,
leave the command's append count at one, close the old session, and prove its
cgroup and observed descendant are gone. A lost HTTP result remains unknown;
neither a JSON refusal nor normal command completion proves session containment.
An authorized recovered save and explicit discard complete the check.

Require all four check labels, `result: PASS`, controller exit zero and
`cleanup: PASS`. Retain failed receipts and raw controller streams alongside the
journal snapshots and process identities. A failure never dispatches another
attempt. Offline tests run with
`python3 -m unittest discover -s acceptance/clients -p 'test_native_*.py' -v`.
They validate observation/control boundaries, not real host recovery.

## Coverage boundaries

The two app-loss windows cover acknowledged and interrupted work. They do not
inject loss between remote push and journal acknowledgement, on either side of
journal rename, during disposal, or after seven days. Domain tests cover their
individual durability rules; this fixture supplies no new real-process evidence
for those windows. It also does not establish production migration, browser or
model-client acceptance.

`account-http.py` retains assumptions from its recorded SRT scope. Its assertions
need these native contracts before reuse:

| Historical assertion | Native contract |
| --- | --- |
| MCP `commit` advances after save/sync | It remains the copy's initial commit; the CLI receipt and `status.baseCommit` identify acknowledged authority. |
| Save automatically resets Git HEAD/index and creates a stash | Plans and explicit installation own local Git changes; retained user bytes and index remain independently observable. |
| Bare `poketto status` probes remote authority | `status` is local; use `status --remote` for remote comparison. |
| Status always exposes `syncPending` and `localBaselinePending` | Native status exposes the plan, write-outcome uncertainty and bounded receipt previews. |
| `sync` installs automatically and exposes old conflict layout | Native plans separate preparation, installation and conflict inspection. |
| Every completed command contains its background tree | Warm-session backgrounds may remain; session close, timeout or recovery fencing supplies containment. |

Independent grants, immutable scope, timeout retention, revocation and exact-copy
disposal remain required behaviors; an unchanged historical receipt does not
verify their native implementation.
