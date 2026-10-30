package io.github.core607.poketto.executor.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An ordinary Java consumer of the real native service; the controller owns fixture cleanup. */
public final class SandcrabNativeProbe {
    private final SandcrabClient client;
    private final SandcrabProtocol.Info info;
    private final SandcrabProtocol.Profile profile;
    private final List<String> checks = new ArrayList<>();

    private SandcrabNativeProbe(Path socket) {
        client = ExecutorConfiguration.sandcrabClient(socket);
        info = client.info();
        profile = info.profiles().get("python");
        require(profile != null, "Native fixture must expose its Python profile");
    }

    public static void main(String[] arguments) throws Exception {
        require(arguments.length == 1, "Pass the fixture's native socket");
        var probe = new SandcrabNativeProbe(Path.of(arguments[0]));
        probe.completedAndReplay();
        probe.cancellation();
        probe.failedCommand();
        probe.outputBound();
        probe.epochRefusal();
        probe.workspace();
        probe.workspaceCancellation();
        probe.inventoryReleased();
        System.out.println(
                new String(SandcrabProtocol.write(new Summary("PASS", probe.checks)), StandardCharsets.UTF_8));
    }

    private SandcrabProtocol.RunRequest plan(String source, int outputBytes) {
        return new SandcrabProtocol.RunRequest(
                UUID.randomUUID().toString(),
                info.serverId(),
                "python",
                List.of("python3", "-c", source),
                Math.min(profile.timeoutMillis(), 10_000),
                Math.min(profile.outputLimitBytes(), outputBytes));
    }

    private SandcrabProtocol.Run await(SandcrabProtocol.Reference reference) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            SandcrabProtocol.Run run = client.get(reference);
            if (run.terminal()) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Native run did not confirm containment within its bound");
    }

    private void completedAndReplay() throws InterruptedException {
        SandcrabProtocol.RunRequest request = plan("import uuid; print(uuid.uuid4())", 4096);
        client.start(request);
        SandcrabProtocol.Run result = await(request.reference());
        require(result.state().equals("succeeded") && result.result().success(), "Native command did not succeed");
        ProtocolValues.uuid(result.result().stdout().strip(), "command output");
        require(client.start(request).equals(result), "Explicit same-plan replay changed the retained result");
        forgotten(result);
        checks.add("native-admission-epoch-output-explicit-replay-and-record-release");
    }

    private void cancellation() throws InterruptedException {
        SandcrabProtocol.RunRequest request = plan("import time; time.sleep(30)", 4096);
        client.start(request);
        client.cancel(request.reference());
        SandcrabProtocol.Run result = await(request.reference());
        require(result.state().equals("cancelled"), "Cancellation did not reach a contained terminal result");
        forgotten(result);
        checks.add("native-cancellation-confirmed-before-forgetting");
    }

    private void failedCommand() throws InterruptedException {
        SandcrabProtocol.RunRequest request = plan("raise SystemExit(7)", 4096);
        client.start(request);
        SandcrabProtocol.Run result = await(request.reference());
        require(result.state().equals("failed") && result.result().exitCode() == 7, "Command failure was lost");
        forgotten(result);
        checks.add("native-nonzero-command-is-a-failed-result");
    }

    private void outputBound() throws InterruptedException {
        SandcrabProtocol.RunRequest request = plan("print('猫' * 4000)", 1024);
        client.start(request);
        SandcrabProtocol.Run result = await(request.reference());
        require(result.state().equals("output_limit") && result.result().truncated(), "Output bound was not enforced");
        forgotten(result);
        checks.add("native-output-bound-and-incomplete-utf8-remain-observable");
    }

    private void epochRefusal() {
        SandcrabProtocol.RunRequest request = plan("raise SystemExit('must not execute')", 1024);
        var stale = new SandcrabProtocol.RunRequest(
                request.id(),
                UUID.randomUUID().toString(),
                request.profile(),
                request.command(),
                request.timeoutMillis(),
                request.outputLimitBytes());
        try {
            client.start(stale);
            throw new IllegalStateException("A stale server epoch admitted new work");
        } catch (SandcrabClient.Rejected refusal) {
            require(refusal.code().equals("SERVER_CHANGED"), "Epoch refusal changed its meaning");
        }
        absent(request.reference());
        checks.add("native-stale-epoch-refuses-before-admission");
    }

    private SandcrabProtocol.Session workspaceOpen() throws InterruptedException {
        var plan = new SandcrabProtocol.SessionPlan(
                UUID.randomUUID().toString(),
                info.serverId(),
                "python",
                Math.min(profile.sessionTtlMillis(), 60_000),
                null);
        client.open(plan);
        return awaitSession(plan.reference(), false);
    }

    private SandcrabProtocol.Session awaitSession(SandcrabProtocol.Reference reference, boolean terminal)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            SandcrabProtocol.Session session = client.session(reference);
            if (terminal ? session.terminal() : session.state().equals("ready")) {
                return session;
            }
            require(terminal || !session.terminal(), "Native session failed before readiness");
            Thread.sleep(25);
        }
        throw new IllegalStateException("Native session did not reach the required lifecycle state");
    }

    private SandcrabProtocol.UploadPlan uploadPlan(SandcrabProtocol.Session session, String path, byte[] data) {
        return new SandcrabProtocol.UploadPlan(
                UUID.randomUUID().toString(),
                session.serverId(),
                session.id(),
                path,
                "raw",
                data.length,
                SandcrabProtocol.sha256(data),
                false,
                true,
                0640);
    }

    private SandcrabProtocol.CommandPlan commandPlan(SandcrabProtocol.Session session, String source) {
        return new SandcrabProtocol.CommandPlan(
                UUID.randomUUID().toString(),
                session.serverId(),
                source,
                Math.min(profile.timeoutMillis(), 10_000),
                Math.min(profile.outputLimitBytes(), 4096));
    }

    private String command(SandcrabProtocol.Session session, String source) throws InterruptedException {
        SandcrabProtocol.CommandPlan plan = commandPlan(session, source);
        client.command(session, plan);
        SandcrabProtocol.Run run = await(plan.reference());
        require(run.state().equals("succeeded") && run.result().success(), "Workspace command failed");
        forgotten(run);
        return run.result().stdout();
    }

    private SandcrabProtocol.Upload upload(
            SandcrabProtocol.Session session, SandcrabProtocol.UploadPlan plan, byte[] data) {
        require(client.register(session, plan).state().equals("waiting"), "New upload did not await its content");
        SandcrabProtocol.Upload receipt = client.send(plan, data);
        require(receipt.succeeded() && receipt.publication().equals("committed"), "Upload was not published");
        require(client.upload(plan.reference()).equals(receipt), "Retained upload receipt changed");
        return receipt;
    }

    private void workspace() throws InterruptedException {
        SandcrabProtocol.Session session = workspaceOpen();
        byte[] data = new byte[512 * 1024];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) (index * 17);
        }
        SandcrabProtocol.UploadPlan plan = uploadPlan(session, "input.bin", data);
        SandcrabProtocol.Upload input = upload(session, plan, data);
        SandcrabProtocol.Upload empty = upload(session, uploadPlan(session, "empty", new byte[0]), new byte[0]);
        String digest = command(session, """
                python3 -c 'from pathlib import Path; import hashlib,stat; p=Path("input.bin"); assert stat.S_IMODE(p.stat().st_mode)==0o640; assert Path("empty").stat().st_size==0; print(hashlib.sha256(p.read_bytes()).hexdigest())'
                """);
        require(digest.strip().equals(plan.sha256()), "Sandbox file bytes differ from the uploaded body");
        checks.add("native-session-binary-and-empty-upload-readback");
        waitingUploadCancellation(session);
        command(session, "printf later > input.bin");
        require(client.register(session, plan).equals(input), "Registration replay changed its original receipt");
        require(command(session, "cat input.bin").equals("later"), "Registration replay overwrote later edits");
        checks.add("native-upload-registration-replay-preserves-later-workspace-edits");
        forgetUpload(input);
        forgetUpload(empty);
        client.close(session.reference());
        forgetSession(awaitSession(session.reference(), true));
    }

    private void waitingUploadCancellation(SandcrabProtocol.Session session) throws InterruptedException {
        SandcrabProtocol.UploadPlan plan = uploadPlan(session, "never-sent", new byte[0]);
        client.register(session, plan);
        SandcrabProtocol.Upload receipt = client.cancelUpload(plan.reference());
        require(
                receipt.state().equals("failed") && receipt.publication().equals("none"),
                "Cancellation before content did not confirm nonpublication");
        require(
                command(session, "test ! -e never-sent && printf absent").equals("absent"),
                "Cancelled upload created a file");
        forgetUpload(receipt);
        checks.add("native-upload-cancellation-before-content-publishes-nothing");
    }

    private void workspaceCancellation() throws InterruptedException {
        SandcrabProtocol.Session session = workspaceOpen();
        SandcrabProtocol.CommandPlan plan = commandPlan(session, "sleep 30");
        client.command(session, plan);
        client.close(session.reference());
        SandcrabProtocol.Session closed = awaitSession(session.reference(), true);
        SandcrabProtocol.Run run = await(plan.reference());
        require(run.state().equals("cancelled"), "Session closure did not cancel its active command");
        forgotten(run);
        forgetSession(closed);
        checks.add("native-session-close-confirms-command-containment-and-record-release");
    }

    private void forgetUpload(SandcrabProtocol.Upload upload) {
        require(
                client.uploads(null).uploads().stream()
                        .anyMatch(retained -> retained.id().equals(upload.id())),
                "Upload inventory omitted a retained receipt");
        client.forget(upload);
        try {
            client.upload(upload.reference());
            throw new IllegalStateException("Native upload record was retained after forgetting");
        } catch (SandcrabClient.Rejected refusal) {
            require(
                    refusal.status() == 404 && refusal.code().equals("UPLOAD_NOT_FOUND"),
                    "Unexpected missing-upload response");
        }
    }

    private void forgetSession(SandcrabProtocol.Session session) {
        require(
                client.sessions(session.serverId(), null).ids().contains(session.id()),
                "Session inventory omitted a terminal session");
        client.forget(session);
        try {
            client.session(session.reference());
            throw new IllegalStateException("Native session record was retained after forgetting");
        } catch (SandcrabClient.Rejected refusal) {
            require(
                    refusal.status() == 404 && refusal.code().equals("SESSION_NOT_FOUND"),
                    "Unexpected missing-session response");
        }
    }

    private void forgotten(SandcrabProtocol.Run run) {
        require(client.runs(run.serverId(), null).ids().contains(run.id()), "Run inventory omitted a retained result");
        client.forget(run);
        absent(run.reference());
    }

    private void inventoryReleased() {
        require(client.runs(info.serverId(), null).ids().isEmpty(), "Run inventory retained forgotten IDs");
        require(client.sessions(info.serverId(), null).ids().isEmpty(), "Session inventory retained forgotten IDs");
        require(client.uploads(null).uploads().isEmpty(), "Upload inventory retained forgotten IDs");
        checks.add("native-inventories-observe-retained-records-and-explicit-release");
    }

    private void absent(SandcrabProtocol.Reference reference) {
        try {
            client.get(reference);
            throw new IllegalStateException("Native run record was not absent");
        } catch (SandcrabClient.Rejected refusal) {
            require(
                    refusal.status() == 404 && refusal.code().equals("RUN_NOT_FOUND"),
                    "Unexpected missing-run response");
        }
    }

    private static void require(boolean satisfied, String message) {
        if (!satisfied) {
            throw new IllegalStateException(message);
        }
    }

    private record Summary(String result, List<String> checks) {}
}
