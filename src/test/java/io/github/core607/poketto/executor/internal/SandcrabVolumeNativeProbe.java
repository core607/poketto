package io.github.core607.poketto.executor.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Two ordinary Java processes use a synthetic Git bundle and retain unsaved files across daemon restart. */
public final class SandcrabVolumeNativeProbe {
    private final SandcrabClient client;
    private final SandcrabProtocol.Info info;
    private final List<String> checks = new ArrayList<>();

    private SandcrabVolumeNativeProbe(Path socket) {
        client = ExecutorConfiguration.sandcrabClient(socket);
        info = client.info();
        require(info.capabilities().contains("volumes"), "Native fixture requires durable storage");
    }

    public static void main(String[] arguments) throws Exception {
        require(
                arguments.length == 3 || arguments.length == 6,
                "Pass socket, phase, state file and optional seed metadata");
        var probe = new SandcrabVolumeNativeProbe(Path.of(arguments[0]));
        Path state = Path.of(arguments[2]);
        if (arguments[1].equals("prepare") && arguments.length == 6) {
            probe.prepare(state, Path.of(arguments[3]), arguments[4], arguments[5]);
        } else if (arguments[1].equals("resume") && arguments.length == 3) {
            probe.resume(state);
        } else {
            throw new IllegalArgumentException("Unknown native volume probe phase");
        }
        System.out.println(
                new String(SandcrabProtocol.write(new Summary("PASS", probe.checks)), StandardCharsets.UTF_8));
    }

    private void prepare(Path state, Path bundle, String commit, String fileDigest) throws Exception {
        ProtocolValues.hex(commit, 40, "fixture commit");
        ProtocolValues.hex(fileDigest, 64, "fixture file digest");
        require(Files.size(bundle) > SandboxHttp.MAX_REQUEST_BYTES, "Fixture bundle must exercise streaming upload");
        var plan = new SandcrabVolume.Plan(id(), info.serverId(), "python", null, 8L * 1024 * 1024, 256L);
        SandcrabVolume volume = awaitVolume(client.createVolume(plan), "ready");
        require(client.createVolume(plan).equals(volume), "Explicit creation replay changed durable identity");
        SandcrabProtocol.Session session = open(volume);
        uploadBundle(session, bundle);
        String checkout = command(
                session,
                "git clone --quiet --no-hardlinks seed.bundle repository && "
                        + "rm seed.bundle && git -C repository rev-parse HEAD");
        require(checkout.strip().equals(commit), "Consumer initialized another Git revision");
        command(session, "printf unsaved-work > repository/unsaved.txt");
        require(
                command(session, "sha256sum repository/payload.bin").startsWith(fileDigest + " "),
                "Consumer initialized different repository bytes");
        snapshots(session, state, fileDigest);
        hostCall(session);
        close(session);
        SandcrabVolume retained = client.volume(volume);
        require(!retained.busy() && retained.attachedTo() == null, "Closed session retained its volume attachment");
        Files.write(state, SandcrabProtocol.write(new Saved(retained, info.serverId(), commit, fileDigest)));
        checks.add("java-streamed-git-bundle-initializes-durable-volume");
        checks.add("java-session-close-preserves-unsaved-work-and-releases-volume");
    }

    private void snapshots(SandcrabProtocol.Session session, Path state, String fileDigest) throws IOException {
        var authorizations = new AtomicInteger();
        SandcrabFiles.Content text =
                client.snapshot(session, "repository/unsaved.txt", 128, authorizations::incrementAndGet);
        byte[] expected = "unsaved-work".getBytes(StandardCharsets.UTF_8);
        require(Arrays.equals(text.bytes(), expected), "Raw snapshot changed unsaved workspace bytes");
        require(text.sha256().equals(SandcrabProtocol.sha256(expected)), "Raw snapshot returned another digest");
        Path directory = Files.createTempDirectory(state.toAbsolutePath().getParent(), "snapshot-");
        Path target = directory.resolve("payload.bin");
        try {
            SandcrabFiles.Snapshot snapshot = client.snapshot(
                    session,
                    new SandcrabFiles.Plan("repository/payload.bin", "raw", true),
                    3L * 1024 * 1024,
                    target,
                    authorizations::incrementAndGet);
            require(snapshot.bytes() == 2L * 1024 * 1024, "Staged snapshot changed the fixture payload length");
            require(Files.size(target) == snapshot.bytes(), "Staged snapshot receipt differs from its bytes");
            require(snapshot.sha256().equals(fileDigest), "Staged snapshot selected another repository payload");
            require(
                    SandcrabProtocol.sha256(Files.readAllBytes(target)).equals(fileDigest),
                    "Staged snapshot bytes changed");
            require(authorizations.get() > 2, "Snapshot transfer omitted repeated caller authorization");
        } finally {
            Files.deleteIfExists(target);
            Files.delete(directory);
        }
        checks.add("java-native-snapshot-readback-verifies-raw-bytes-and-digest");
    }

    private void hostCall(SandcrabProtocol.Session session) {
        require(info.profiles().get(session.profile()).hostCalls(), "Native fixture must enable opaque host calls");
        byte[] request = new byte[65_536];
        byte[] reply = new byte[1024];
        for (int index = 0; index < request.length; index++) {
            request[index] = (byte) index;
        }
        for (int index = 0; index < reply.length; index++) {
            reply[index] = (byte) (255 - index);
        }
        var plan = new SandcrabProtocol.CommandPlan(
                id(),
                info.serverId(),
                "python3 -c 'import sys; sys.stdout.buffer.write(bytes(range(256))*256)'"
                        + " | sandcrab call - --timeout-ms 10000 2>host-call-correlation"
                        + " && cat host-call-correlation >&2 && rm host-call-correlation && printf native-stderr >&2",
                10_000,
                4096);
        client.command(session, plan);
        var handled = new AtomicInteger();
        var observed = new AtomicReference<SandcrabEvents.Call>();
        // No output is emitted while this call is pending: an identity-only output observer would retain the sole slot.
        SandcrabEventProtocol.Run result = client.events()
                .observeCalls(plan.reference(), Duration.ofSeconds(20), call -> {
                    require(handled.incrementAndGet() == 1, "One opaque request invoked its handler more than once");
                    require(call.run().equals(plan.reference()), "Opaque request changed its command scope");
                    require(Arrays.equals(call.bytes(), request), "Native host-call delivery changed opaque bytes");
                    observed.set(call);
                    return reply;
                });
        require(
                result.terminal() && result.result().success(),
                "Host-call stream did not confirm successful containment");
        require(handled.get() == 1, "Native command did not deliver its opaque host call");
        SandcrabEventProtocol.CallInventory inventory = client.events().inventory(plan.reference());
        require(
                inventory.closed() && inventory.calls().size() == 1,
                "Native call inventory did not retain its terminal receipt");
        require(
                inventory.incarnation().equals(observed.get().cursor().incarnation()),
                "Call inventory changed command incarnation");
        require(
                inventory.calls().getFirst().id().equals(observed.get().id()),
                "Call inventory changed its reply handle");
        require(
                inventory.calls().getFirst().replySha256().equals(SandcrabProtocol.sha256(reply)),
                "Call inventory changed reply bytes");
        String stderr = "requestId=" + observed.get().requestId() + " runId=" + plan.id() + "\nnative-stderr";
        observedOutput(plan.reference(), reply, stderr);
        SandcrabProtocol.Run retained = client.get(plan.reference());
        require(
                retained.terminal() && retained.result().success(),
                "Host-call result was not retained for reconciliation");
        client.forget(retained);
        checks.add("java-native-sse-host-call-reply-and-output-confirm-containment");
    }

    private void observedOutput(SandcrabProtocol.Reference run, byte[] expected, String expectedError) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        var cursor = new AtomicReference<SandcrabEventProtocol.OutputCursor>();
        SandcrabEventProtocol.Run result = client.events().observe(run, null, Duration.ofSeconds(10), output -> {
            (output.stream().equals("stdout") ? stdout : stderr).writeBytes(output.bytes());
            cursor.set(output.cursor());
        });
        require(result.terminal() && result.result().success(), "Native output stream did not confirm containment");
        require(Arrays.equals(stdout.toByteArray(), expected), "Native output SSE changed binary reply bytes");
        require(
                stderr.toString(StandardCharsets.UTF_8).equals(expectedError),
                "Native output SSE mixed stdout and stderr");
        require(cursor.get() != null, "Native output stream omitted its replay cursor");
        SandcrabEventProtocol.Run resumed = client.events()
                .observe(run, cursor.get(), Duration.ofSeconds(10), ignored -> {
                    throw new IllegalStateException("Explicit native output continuation repeated delivered bytes");
                });
        require(
                resumed.terminal() && resumed.result().success(),
                "Native output continuation lost its retained result");
    }

    private void uploadBundle(SandcrabProtocol.Session session, Path bundle) throws IOException {
        MessageDigest digest = SandcrabProtocol.digest();
        try (InputStream file = Files.newInputStream(bundle);
                var input = new DigestInputStream(file, digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        var plan = new SandcrabProtocol.UploadPlan(
                id(),
                info.serverId(),
                session.id(),
                "seed.bundle",
                "raw",
                Files.size(bundle),
                HexFormat.of().formatHex(digest.digest()),
                false,
                true,
                0600);
        require(client.register(session, plan).state().equals("waiting"), "Bundle registration was not admitted");
        SandcrabProtocol.Upload uploaded = client.send(plan, bundle);
        require(uploaded.succeeded(), "Bundle streaming did not confirm publication");
        client.forget(uploaded);
    }

    private void resume(Path state) throws Exception {
        Saved saved = SandcrabProtocol.read(Files.readAllBytes(state), Saved.class);
        require(!info.serverId().equals(saved.serverId()), "Controller did not restart the native daemon");
        SandcrabVolume original = client.volume(saved.volume());
        SandcrabProtocol.Session reopened = open(original);
        require(
                command(reopened, "git -C repository rev-parse HEAD").strip().equals(saved.commit()),
                "Daemon restart changed the consumer's Git baseline");
        require(command(reopened, "cat repository/unsaved.txt").equals("unsaved-work"), "Restart lost unsaved work");
        require(
                command(reopened, "sha256sum repository/payload.bin").startsWith(saved.fileDigest() + " "),
                "Restart changed repository bytes");
        exclusiveAttachment(original);
        close(reopened);
        checks.add("java-new-consumer-and-daemon-epoch-retain-git-baseline-and-unsaved-work");
        checks.add("java-volume-refuses-a-second-live-workspace-owner");
        cloneAndRelease(original);
        require(client.volumes(null).volumes().isEmpty(), "Fixture retained volume records");
        require(client.sessions(info.serverId(), null).ids().isEmpty(), "Fixture retained session records");
        require(client.runs(info.serverId(), null).ids().isEmpty(), "Fixture retained command results");
        require(client.uploads(null).uploads().isEmpty(), "Fixture retained upload records");
        checks.add("java-volume-session-command-and-upload-records-released");
    }

    private void exclusiveAttachment(SandcrabVolume volume) throws InterruptedException {
        SandcrabProtocol.SessionPlan plan = sessionPlan(volume.id());
        client.open(plan);
        SandcrabProtocol.Session rejected = awaitSession(plan.reference(), true);
        require(
                rejected.state().equals("failed") && "VOLUME_IN_USE".equals(rejected.code()),
                "A second session was not refused by exclusive volume ownership");
        client.forget(rejected);
    }

    private void cloneAndRelease(SandcrabVolume original) throws InterruptedException {
        var plan = new SandcrabVolume.Plan(id(), info.serverId(), "python", original.id(), 8L * 1024 * 1024, 256L);
        SandcrabVolume clone = awaitVolume(client.createVolume(plan), "ready");
        SandcrabProtocol.Session fork = open(clone);
        require(command(fork, "cat repository/unsaved.txt").equals("unsaved-work"), "Reflink clone lost unsaved work");
        command(fork, "printf independent-edit > repository/unsaved.txt");
        close(fork);
        SandcrabProtocol.Session source = open(original);
        require(
                command(source, "cat repository/unsaved.txt").equals("unsaved-work"),
                "Clone write changed source data");
        close(source);
        checks.add("java-reflink-clone-preserves-files-and-isolates-later-edits");
        client.forget(awaitVolume(client.deleteVolume(client.volume(clone)), "deleted"));
        client.forget(awaitVolume(client.deleteVolume(client.volume(original)), "deleted"));
    }

    private SandcrabProtocol.SessionPlan sessionPlan(String volume) {
        return new SandcrabProtocol.SessionPlan(id(), info.serverId(), "python", 60_000, volume);
    }

    private SandcrabProtocol.Session open(SandcrabVolume volume) throws InterruptedException {
        SandcrabProtocol.SessionPlan plan = sessionPlan(volume.id());
        client.open(plan);
        return awaitSession(plan.reference(), false);
    }

    private SandcrabProtocol.Session awaitSession(SandcrabProtocol.Reference reference, boolean terminal)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            SandcrabProtocol.Session session = client.session(reference);
            if (terminal ? session.terminal() : session.state().equals("ready")) {
                return session;
            }
            require(terminal || !session.terminal(), "Volume session failed before readiness");
            Thread.sleep(25);
        }
        throw new IllegalStateException("Volume session did not confirm its required lifecycle state");
    }

    private SandcrabVolume awaitVolume(SandcrabVolume expected, String state) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            SandcrabVolume volume = client.volume(expected);
            if (volume.state().equals(state)) {
                return volume;
            }
            require(
                    !List.of("closing", "failed").contains(volume.state()),
                    "Volume operation failed or is uncontained");
            Thread.sleep(25);
        }
        throw new IllegalStateException("Volume operation did not reach its expected durable state");
    }

    private void close(SandcrabProtocol.Session session) throws InterruptedException {
        client.close(session.reference());
        client.forget(awaitSession(session.reference(), true));
    }

    private String command(SandcrabProtocol.Session session, String source) throws InterruptedException {
        var plan = new SandcrabProtocol.CommandPlan(id(), info.serverId(), source, 10_000, 4096);
        client.command(session, plan);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            SandcrabProtocol.Run run = client.get(plan.reference());
            if (run.terminal()) {
                require(run.state().equals("succeeded") && run.result().success(), "Native repository command failed");
                client.forget(run);
                return run.result().stdout();
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Native repository command did not confirm containment");
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

    record Saved(SandcrabVolume volume, String serverId, String commit, String fileDigest) {
        Saved {
            Objects.requireNonNull(volume, "Retained volume must be present");
            serverId = ProtocolValues.uuid(serverId, "retained daemon epoch");
            commit = ProtocolValues.hex(commit, 40, "retained Git commit");
            fileDigest = ProtocolValues.hex(fileDigest, 64, "retained file digest");
        }
    }

    record Summary(String result, List<String> checks) {}
}
