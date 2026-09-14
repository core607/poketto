# Java and sandbox execution acceptance

Current acceptance exercises account-owned disk copies on an isolated XFS pool. `--scenario ephemeral-lifecycle` covers transport/grant reconnection, timeout, adapter recreation and explicit disposal. `--scenario retained-process` uses independent JVMs, external SIGKILL and real JGit saves/readback; authentication and the Git authority are synthetic fixtures. Historical evidence entries below remain tied to their recorded revisions and do not replace current authenticated service acceptance.

The [content-root run](evidence/2026-09-10-content-roots.json) uses the explicit
`public-root` publication format. All 30 Java scenarios, process-loss lease expiry
and controller cleanup pass with real native worker/SRT execution. Full sessions
retain original history; public sessions expose only the current approved
projection and host-owned media mapping. Authorization in this probe is a
synthetic stub, so it does not replace authenticated HTTP MCP client acceptance.

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
worker, launcher, `resource_pool.py`, `native_pool.py`, `bridge.py`, `cli.py`, `session_files.py`, `binary_capture.py`, `materialize.py`, `artifacts.py`, `checkpoints.py` and `checkpoint_tree.py` sources to isolated host staging. The probe verifies every
manifest entry before running. It never stages operator settings or credentials.

The host needs cgroup v2, systemd, root access, Git, Python with the worker's
pinned dependencies, and its prepared SRT toolchain. An existing Java 26 runtime
can be used; `prepare-jdk.sh NEW_DIRECTORY` instead downloads an isolated
Temurin 26.0.2+10 archive and verifies its pinned SHA-256 without installing it
globally. Runtime classes and JARs must be readable by the probe's temporary
application account.

`--scenario ephemeral-lifecycle` selects a real five-second command timeout,
preservation of unsaved text and binary bytes, explicit disposal without a
generation, repeated disposal and fresh admission in the same transport.
`--scenario exports` selects the real private/public CLI ZIP flows. `--scenario media`
selects original fetches, historical versions, local collision protection and member
projection access while the anonymous website is disabled. The default `--scenario all`
includes both with the complete adapter lifecycle checks and process-loss expiry.
A focused result does not prove the omitted scenarios. Each mode uses a fresh native
fixture and checks cleanup.

`--scenario retained-process` runs independent producer and recovery JVMs. The controller verifies the producer service's Java executable and sends SIGKILL after an acknowledged command, during a command with an acknowledged host save, after a successful remote push, around final journal publication, or during disposal. A new JVM opens the same authority without reseeding, automatically attaches the original copy, checks its local work and saves the recovered draft. The uncertain-write case reconciles the candidate without repeating the push. The expiry case advances only the fixture clock, checks that a held writer prevents deletion, then verifies scheduled cleanup and preservation of remote commits. Use `--process-case` to select one of `acknowledged`, `interrupted`, `uncertain`, `beforepublish`, `afterpublish`, `discarding` or `expired`. Output records that selection. The focused mode checks lease closure and fixture cleanup.

The complete Java batch has a 360-second supervisor and harness deadline to cover its repeated cold opens and intentional worker restarts. Focused batches retain a 240-second deadline. These are whole-test budgets; each command and lease keeps its independently checked timeout, and cleanup still verifies that no execution processes remain.

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

The [integrated content-root run](evidence/2026-09-11-content-roots.json) covers
all 32 scenarios with the public-root format and merged export implementation.
Private/public ZIPs, capacity recovery, media receipts, history isolation,
process-loss expiry and cleanup pass on the recorded native runtime.

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

The [MCP usability run](evidence/2026-09-12-mcp-usability.json) verifies linking an existing original into an unsaved index, exact metadata, idempotent byte preservation, explicit replacement, foreign-original refusal, public-only rejection, selected-file failure reasons and an explicit index save. Its 33 Java scenarios, process-loss lease expiry and fixture cleanup pass with actual worker/SRT execution. Runtime and worker hashes are verified against the staged source. Authentication remains synthetic; reconnect identity guards and durable unsaved-work recovery are outside this refinement.

The [binary-import diagnostic run](evidence/2026-09-12-binary-import-reasons.json) additionally checks `NOT_FOUND` for an absent source and `BINARY_LIMIT` for an oversized sparse source through the real CLI. All 33 Java/SRT scenarios, process-loss expiry and final cleanup pass; the same synthetic-authentication and deployment limitations apply.

The [working-copy identity run](evidence/2026-09-12-copy-identity.json) verifies the combined identity guard and MCP usability refinements. Two chats keep different local files, a normal nonzero result retains its copy ID, and a trusted idle-close event followed by a new transport at the same commit rejects the old expected ID before executing a sentinel write. All 34 Java/SRT scenarios, process-loss expiry and cleanup pass. This injected lifecycle event does not establish real-client idle/reconnect acceptance or durable unsaved-work recovery.

The [import receipt run](evidence/2026-09-13-retained-import-receipts.json) verifies that `poketto status` retains the exact stored-original receipt after index installation runs out of space, then reports the same asset as indexed after a successful retry. All 39 Java/SRT scenarios, process-loss expiry and cleanup pass. Separate unit tests cover receipt restoration and checkpoint failures. The native adapter still uses ephemeral session state; these results do not establish retained-command recovery or actual-client acceptance.

The [retained command run](evidence/2026-09-13-retained-command-boundaries.json) enables application command checkpoints. It verifies save/import state and nonzero results, then cancels another running command and checks that its unconfirmed marker and prior recovery point remain while the writer lock is released. Signed worker restoration after adapter closure checks the original Git HEAD, saved text, untracked binary and unsaved media index. Public commands retain their host-only projection proof while permission increases and publication withdrawal keep their existing boundaries. All 40 Java/SRT scenarios, process-loss expiry and cleanup pass. This proves command checkpoint integration and worker restoration; the MCP generation-transfer and atomic-resume entrance remain incomplete.

The [lost-close-acknowledgement run](evidence/2026-09-13-retained-close-containment.json) injects failed CLOSE replies after real worker termination. The writer lock remains held after the close-attempt future fails and is released only after reconciliation confirms closure. Restoring the previous future-completion guard fails that lock assertion. All 40 native scenarios, process-loss expiry and cleanup pass with the fix; authentication remains synthetic.

The [explicit recovery run](evidence/2026-09-13-retained-explicit-resume.json) restores through the Java adapter after replacing both the adapter and MCP transport identity. It verifies the original Git HEAD, saved text, unsaved file and media-index bytes, paired save/import state and the prior interrupted command ID. Stale generations leave no write sentinel, parallel chats remain separate, and racing recovery admits one command. Lost RESTORE and CLOSE replies keep the writer lock until confirmed reconciliation; the refused command does not execute. All 40 native scenarios, process-loss expiry and cleanup pass. Six schema/handler tests and the HTTP MCP protocol test pass separately. Authentication in the native run is synthetic, so actual-client recovery acceptance remains pending. Retention is disabled by default while the remaining [continuity work](../notes/proposed/2026-09-12-executor-work-continuity.md) is incomplete.

The [expiry collection run](evidence/2026-09-13-retained-expiry-collection.json) uses an eight-second retention window and verifies acknowledged work, automatic lease closure, metadata collection and explicit fresh admission on the same transport. The host observer confirms that the old acknowledged checkpoint file disappears through periodic worker collection without a client removal request. All 40 native scenarios, process-loss expiry and cleanup pass. Separate Linux tests prove that metadata collection preserves a writer held by another process; worker tests cover full capacity, live checkpoints, busy locks, invalid headers and file aliases. The application collector uses a shortened test interval; production defaults to one minute. Authentication remains synthetic.

The [retained text baseline run](evidence/2026-09-13-retained-text-baselines.json) performs a real SRT move, closes the adapter, restores the same copy through a new adapter and transport, and saves the moved file with its historical reader deliberately unavailable. It verifies retained destination text, source absence and the original pinned commit. Acknowledged save text also survives the existing interrupted-command recovery scenario. All 40 native scenarios, process-loss expiry, independent checkpoint collection and cleanup pass. The 267-test Linux storage run separately covers remote index baselines versus unselected local media mappings, post-push baseline read failure without duplicate moves, independent saved versions, current authorization and remote conflicts. Authentication remains synthetic; original and non-text baseline lookup and actual-client acceptance remain incomplete.

The [original admission run](evidence/2026-09-13-retained-original-admission.json) captures the authoritative private baseline before the initial worker checkpoint and command. It reads original private text from the resulting archive, verifies that a later unsaved binary is absent there, and checks that adapter recovery preserves the exact archive reference. Public copies have no private archive reference. All 40 native scenarios, process-loss expiry, independent checkpoint collection and cleanup pass. Separate Linux tests verify capture ordering, source denial, initial-checkpoint failure and orphan reclamation; record tests reject mismatched owner, workspace, copy, commit and expiry. The required Linux storage suite passes 294 cases, and the HTTP MCP protocol test passes. Authentication remains synthetic; using original archives for executor baseline lookup and actual-client acceptance remain incomplete.

The [original lookup run](evidence/2026-09-13-retained-original-lookup.json) leaves an existing file modified and a new file unsaved, closes the adapter, and saves both after recovery while every historical baseline query is refused. A second copy restores after a competing remote write, rejects a stale save, reports the expected synchronization conflict and saves the resolved content. Both copies retain their original archive reference and pinned commit. All 40 native scenarios, process-loss expiry, independent checkpoint collection and cleanup pass. The 299-case Linux storage suite separately verifies state-copy bindings, reader reuse, missing-archive refusal, permission checks before and after reads, identity and expiry checks, file-descriptor closure and writer release. The HTTP MCP protocol test also passes. Authentication remains synthetic; advanced non-text baselines and actual-client acceptance remain incomplete.

The [non-text baseline run](evidence/2026-09-13-retained-nontext-baselines.json) moves a Git binary and a materialized managed file, closes the adapter, and restores both while historical baseline queries are refused. Their bytes, presence, available byte revisions, diagnostics and source absences remain intact. CLI media linking rejects the Git destination as a collision and accepts the relocated managed destination; text save rejects the binary without creating a pending write. All 40 native scenarios, independent checkpoint collection, process-loss expiry and cleanup pass. The 301-case Linux storage suite separately covers pending and installed non-text move recovery. Tagged-state serialization, identity, completeness, socket, module, style and HTTP MCP protocol checks pass. Authentication remains synthetic; further crash-window and actual-client acceptance remain incomplete.

The [worker lifecycle run](evidence/2026-09-13-retained-worker-lifecycle.json) kills and restarts the actual native supervisor before explicit recovery. Private text and binary drafts survive at the original commit, with the same original archive reference. A denied private-read check prevents recovery from changing metadata. A public copy retains its draft and projection proof after another worker restart; increased permission does not expose private files or private authority history, and publication withdrawal rejects recovery without changing metadata. All 42 native scenarios, independent checkpoint collection, process-loss expiry and cleanup pass. Authorization uses a synthetic fixture; real-account and actual-client recovery acceptance remain pending.

The [explicit discard run](evidence/2026-09-13-retained-discard.json) refuses stale generations and busy writers, then loses real CLOSE replies and verifies that metadata and the writer remain intact until containment is confirmed. Retrying discards the copy; another retry returns `ABSENT`, recovery is refused, and a fresh copy includes remote saves without the discarded draft. Owners can also discard after private-read denial or publication withdrawal. All 43 native scenarios, independent checkpoint collection, process-loss expiry and cleanup pass. Separately, 306 Linux storage cases and MCP input, catalog, socket, module, style and HTTP protocol checks pass. Authentication remains synthetic; additional crash-window and actual-client acceptance are pending.

The [contention and interruption run](evidence/2026-09-14-retained-contention-and-interruption.json) injects two explicit pre-capture `BUSY` replies at the real adapter's worker boundary. Subsequent SRT execution and the selected remote save occur once, and acknowledged local edits remain recoverable. A real command timeout returns the retained copy and generation through `ExecutionUnconfirmedException`; a new adapter recovers the original commit and acknowledged bytes with the interrupted command identity. All 45 main scenarios, additional lifecycle checks and cleanup pass using the standard fixture profile. An optional `/var/lib` full-batch attempt reached the aggregate six-minute deadline after both new cases passed; that attempt is recorded as incomplete with successful cleanup. Native authorization remains synthetic, and final HTTPS acceptance is separate.

The [JVM process-loss run](evidence/2026-09-13-retained-jvm-process-loss.json) verifies external SIGKILL at both focused-mode boundaries. New JVMs recover original commits, draft text and binary bytes, original archives, per-file baselines and save receipts; the interrupted command remains identified. Saving the restored draft succeeds and authoritative Git readback confirms its content. Both cases, worker lease closure and fixture cleanup pass. Authentication remains synthetic. The run does not cover the remaining persistence/push/response crash windows or actual-client acceptance.

The [post-push process-loss run](evidence/2026-09-13-retained-push-process-loss.json) adds external SIGKILL after the real remote accepts a commit but before the host writer receives its reply. Recovery retains the exact candidate and pending state. `poketto recover` confirms the candidate, clears uncertainty and preserves the saved file with zero additional pushes. All three focused process-loss cases, lease closure and cleanup pass. Authentication remains synthetic; command-acknowledgement publication boundaries and real-account/client acceptance remain pending.

The [publication-boundary run](evidence/2026-09-13-retained-publication-process-loss.json) adds SIGKILL immediately before and after the final command metadata replacement. Before publication, recovery selects the prior acknowledged draft and reports interruption. After durable publication, it restores the completed draft even though the response was never delivered. Both restored versions can be saved and read from authoritative Git. All five focused process-loss cases, lease closure and cleanup pass. Authentication remains synthetic; real-account and actual-client acceptance remain pending. Process termination does not establish storage-device or host power-loss durability.

The `--scenario public-scope --fixture-parent /var/lib` probe uses the real disk worker and Git public projection to check permission expansion, unchanged copy identity and content, artifact delivery, and withdrawal checks. Its authorization principal is synthetic; it does not claim external client acceptance.
