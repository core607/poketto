package io.github.core607.poketto.executor.internal;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
        close(session);
        SandcrabVolume retained = client.volume(volume);
        require(!retained.busy() && retained.attachedTo() == null, "Closed session retained its volume attachment");
        Files.write(state, SandcrabProtocol.write(new Saved(retained, info.serverId(), commit, fileDigest)));
        checks.add("java-streamed-git-bundle-initializes-durable-volume");
        checks.add("java-session-close-preserves-unsaved-work-and-releases-volume");
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
