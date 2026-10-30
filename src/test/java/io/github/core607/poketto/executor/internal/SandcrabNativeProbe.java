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

    private void forgotten(SandcrabProtocol.Run run) {
        client.forget(run);
        absent(run.reference());
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
