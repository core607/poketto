package io.github.core607.poketto.executor.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real daemon faults during one Java observer's lifetime; no product repository or external service. */
public final class SandcrabRecoveryNativeProbe {
    private static final Duration OBSERVE = Duration.ofSeconds(60);
    private static final byte[] REQUEST = "opaque-request".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLY = "opaque-reply".getBytes(StandardCharsets.UTF_8);
    private final SandcrabClient client;
    private final SandcrabProbeControl control;
    private final List<String> checks = new ArrayList<>();
    private final List<Recovery> recoveries = new ArrayList<>();

    private SandcrabRecoveryNativeProbe(Path socket, Path directory) {
        client = ExecutorConfiguration.sandcrabClient(socket);
        control = new SandcrabProbeControl(directory);
        SandcrabProtocol.Info info = client.info();
        require(info.profiles().get("python").hostCalls(), "Fixture must enable opaque host calls");
    }

    public static void main(String[] arguments) {
        require(arguments.length == 2, "Pass the fixture socket and control directory");
        var probe = new SandcrabRecoveryNativeProbe(Path.of(arguments[0]), Path.of(arguments[1]));
        probe.outputRestart();
        probe.callsRestart();
        probe.handlerRestart();
        probe.containmentFailure();
        String epoch = probe.client.info().serverId();
        require(probe.client.runs(epoch, null).ids().isEmpty(), "Fixture retained new-epoch command receipts");
        require(probe.client.sessions(epoch, null).ids().isEmpty(), "Fixture created unexpected sessions");
        System.out.println(new String(
                SandcrabProtocol.write(new Summary("PASS", probe.checks, probe.recoveries)), StandardCharsets.UTF_8));
    }

    private SandcrabProtocol.RunRequest plan(String source) {
        return new SandcrabProtocol.RunRequest(
                id(), client.info().serverId(), "python", List.of("/bin/bash", "-c", source), 30_000, 4096);
    }

    private static String waitingOutput() {
        return "python3 -u -c 'import time; print(\"output-ready\",flush=True); time.sleep(120)'";
    }

    private static String waitingCall() {
        return "printf opaque-request | sandcrab call - --timeout-ms 30000";
    }

    private void outputRestart() {
        SandcrabProtocol.RunRequest plan = plan(waitingOutput());
        client.start(plan);
        var delivered = new AtomicInteger();
        var bytes = new ByteArrayOutputStream();
        var cursor = new AtomicReference<SandcrabEventProtocol.OutputCursor>();
        var request = new AtomicReference<SandcrabProbeControl.Request>();
        WorkerUnavailableException failure =
                unavailable(() -> client.events().observe(plan.reference(), null, OBSERVE, output -> {
                    if (!readyOutput(bytes, output)) {
                        return;
                    }
                    require(delivered.incrementAndGet() == 1, "Output was delivered again without explicit resume");
                    cursor.set(output.cursor());
                    eventCapacity(plan.reference());
                    request.set(control.signal("restart-output", plan.reference()));
                }));
        require(delivered.get() == 1 && request.get() != null, "Output failed before the restart rendezvous");
        require(failure.mayHaveExecuted(), "Stream loss incorrectly proved that no request was sent");
        SandcrabProbeControl.Ack ack = restarted(request.get());
        lost(plan, ack);
        rejected(
                404,
                "RUN_NOT_FOUND",
                () -> client.events().observe(plan.reference(), cursor.get(), OBSERVE, output -> {
                    throw new IllegalStateException("An old output cursor delivered new bytes");
                }));
        Fresh fresh = fresh(false, null);
        recoveries.add(recovery("output-stream-restart", plan, ack, "transport-failure", 0, fresh));
        checks.add("java-output-stream-loss-does-not-confirm-old-completion-or-replay");
    }

    private void eventCapacity(SandcrabProtocol.Reference run) {
        rejected(
                429,
                "EVENT_CAPACITY",
                () -> client.events().observe(run, null, Duration.ofSeconds(5), output -> {
                    throw new IllegalStateException("A refused observer received output");
                }));
        rejected(
                429,
                "EVENT_CAPACITY",
                () -> client.events().observeCalls(run, null, Duration.ofSeconds(5), call -> {
                    throw new IllegalStateException("A refused observer received a host call");
                }));
        require(client.get(run).state().equals("running"), "Observer refusal stopped the admitted command");
        checks.add("java-event-capacity-json-refusal-leaves-the-existing-run-active");
    }

    private void callsRestart() {
        SandcrabProtocol.RunRequest plan = plan(waitingCall());
        client.start(plan);
        var delivered = new AtomicInteger();
        var observed = new AtomicReference<SandcrabEvents.Call>();
        var request = new AtomicReference<SandcrabProbeControl.Request>();
        WorkerUnavailableException failure =
                unavailable(() -> client.events().observeCalls(plan.reference(), null, OBSERVE, call -> {
                    received(plan, call, delivered);
                    observed.set(call);
                    control.effect("calls");
                    request.set(control.signal("restart-calls", plan.reference()));
                }));
        require(observed.get() != null && request.get() != null, "Call stream failed before its pending request");
        require(failure.mayHaveExecuted(), "Call stream loss incorrectly proved nonexecution");
        SandcrabProbeControl.Ack ack = restarted(request.get());
        rejected(404, "RUN_NOT_FOUND", () -> client.events().reply(observed.get(), REPLY));
        control.verifyEffect("calls");
        require(delivered.get() == 1, "Disconnected call observer repeated the synthetic host effect");
        lost(plan, ack);
        Fresh fresh = fresh(true, observed.get().cursor());
        recoveries.add(recovery("call-stream-restart", plan, ack, "transport-failure-late-reply-refused", 1, fresh));
        checks.add("java-call-stream-loss-retains-host-effect-uncertainty-without-replay");
    }

    private void handlerRestart() {
        SandcrabProtocol.RunRequest plan = plan(waitingCall());
        client.start(plan);
        var handled = new AtomicInteger();
        var observed = new AtomicReference<SandcrabEvents.Call>();
        var acknowledgement = new AtomicReference<SandcrabProbeControl.Ack>();
        rejected(
                404,
                "RUN_NOT_FOUND",
                () -> client.events().observeCalls(plan.reference(), OBSERVE, call -> {
                    received(plan, call, handled);
                    observed.set(call);
                    control.effect("handler");
                    acknowledgement.set(restarted(control.signal("restart-handler", plan.reference())));
                    return REPLY;
                }));
        require(handled.get() == 1 && acknowledgement.get() != null, "Handler did not straddle the daemon restart");
        control.verifyEffect("handler");
        // The refused reply says nothing about the effect already performed by the caller.
        lost(plan, acknowledgement.get());
        Fresh fresh = fresh(true, observed.get().cursor());
        recoveries.add(recovery(
                "handler-effect-before-restart",
                plan,
                acknowledgement.get(),
                "reply-refused-after-effect-without-publication-receipt",
                handled.get(),
                fresh));
        checks.add("java-reply-json-refusal-does-not-reclassify-an-earlier-host-effect");
    }

    private static void received(SandcrabProtocol.RunRequest plan, SandcrabEvents.Call call, AtomicInteger count) {
        require(count.incrementAndGet() == 1, "One observation invoked its host handler twice");
        require(call.run().equals(plan.reference()), "Host-call delivery changed its command or daemon epoch");
        require(Arrays.equals(call.bytes(), REQUEST), "Host-call delivery changed its opaque payload");
    }

    private void containmentFailure() {
        SandcrabProtocol.RunRequest plan = plan(waitingOutput());
        client.start(plan);
        var observed = new AtomicInteger();
        var bytes = new ByteArrayOutputStream();
        WorkerUnavailableException failure =
                unavailable(() -> client.events().observe(plan.reference(), null, OBSERVE, output -> {
                    if (!readyOutput(bytes, output)) {
                        return;
                    }
                    require(observed.incrementAndGet() == 1, "Cleanup fixture emitted unexpected output");
                    SandcrabProbeControl.Ack armed =
                            control.await(control.signal("enable-cleanup-fault", plan.reference()));
                    require(
                            armed.serverId().equals(plan.serverId()) && !armed.containmentConfirmed(),
                            "Arming a cleanup fault claimed a restart or containment");
                    client.cancel(plan.reference());
                }));
        require(
                observed.get() == 1 && causedBy(failure, "CONTAINMENT_UNCONFIRMED"),
                "Output observation did not report the real containment error frame");
        SandcrabProtocol.Run closing = client.get(plan.reference());
        require(closing.state().equals("closing") && !closing.terminal(), "Unconfirmed cleanup became terminal");
        unavailable(() -> client.forget(closing));
        WorkerUnavailableException calls =
                unavailable(() -> client.events().observeCalls(plan.reference(), null, OBSERVE, call -> {
                    throw new IllegalStateException("Closing command delivered a new host call");
                }));
        require(causedBy(calls, "CONTAINMENT_UNCONFIRMED"), "Call observation concealed unconfirmed containment");
        require(client.get(plan.reference()).state().equals("closing"), "Unconfirmed receipt was discarded");
        SandcrabProbeControl.Ack ack = restarted(control.signal("recover-cleanup", plan.reference()));
        lost(plan, ack);
        Fresh fresh = fresh(false, null);
        recoveries.add(recovery("unconfirmed-cleanup", plan, ack, "nonterminal-containment-error", 0, fresh));
        checks.add("java-containment-error-is-unavailable-and-retains-the-closing-receipt");
    }

    private SandcrabProbeControl.Ack restarted(SandcrabProbeControl.Request request) {
        SandcrabProbeControl.Ack ack = control.await(request);
        require(ack.containmentConfirmed(), "Controller did not verify old fixture cgroups and mounts");
        require(!ack.serverId().equals(request.run().serverId()), "Controller did not change the daemon epoch");
        require(client.info().serverId().equals(ack.serverId()), "Java observed another replacement epoch");
        return ack;
    }

    private void lost(SandcrabProtocol.RunRequest plan, SandcrabProbeControl.Ack ack) {
        rejected(404, "RUN_NOT_FOUND", () -> client.get(plan.reference()));
        rejected(404, "RUN_NOT_FOUND", () -> client.events().inventory(plan.reference()));
        require(client.runs(ack.serverId(), null).ids().isEmpty(), "Observer resubmitted work after daemon restart");
        rejected(409, "SERVER_CHANGED", () -> client.start(plan));
        require(client.runs(ack.serverId(), null).ids().isEmpty(), "A stale admission created a new-epoch run");
    }

    private Fresh fresh(boolean calls, SandcrabEventProtocol.CallCursor oldCursor) {
        SandcrabProtocol.RunRequest plan = plan(calls ? waitingCall() : "printf fresh-output");
        client.start(plan);
        if (calls) {
            rejected(
                    409,
                    "CALL_CURSOR_MISMATCH",
                    () -> client.events().observeCalls(plan.reference(), oldCursor, OBSERVE, call -> {
                        throw new IllegalStateException("Foreign call cursor invoked a host handler");
                    }));
            require(client.get(plan.reference()).state().equals("running"), "Cursor refusal completed the new command");
            var handled = new AtomicInteger();
            SandcrabEventProtocol.Run result = client.events().observeCalls(plan.reference(), OBSERVE, call -> {
                received(plan, call, handled);
                return REPLY;
            });
            require(
                    result.terminal() && result.result().success() && handled.get() == 1,
                    "New-epoch host-call execution did not complete once");
            SandcrabEventProtocol.CallInventory inventory = client.events().inventory(plan.reference());
            require(inventory.closed() && inventory.calls().size() == 1, "New-epoch call inventory was not closed");
            require(
                    inventory.calls().getFirst().replySha256().equals(SandcrabProtocol.sha256(REPLY)),
                    "New-epoch reply receipt changed its bytes");
        } else {
            var bytes = new ByteArrayOutputStream();
            SandcrabEventProtocol.Run result = client.events()
                    .observe(plan.reference(), null, OBSERVE, output -> bytes.writeBytes(output.bytes()));
            require(result.terminal() && result.result().success(), "New-epoch output execution did not complete");
            require(bytes.toString(StandardCharsets.UTF_8).equals("fresh-output"), "New-epoch output bytes changed");
        }
        SandcrabProtocol.Run retained = client.get(plan.reference());
        require(retained.terminal() && retained.result().success(), "New-epoch receipt did not confirm completion");
        client.forget(retained);
        return new Fresh(plan.id(), plan.serverId(), retained.state());
    }

    private static Recovery recovery(
            String phase,
            SandcrabProtocol.RunRequest old,
            SandcrabProbeControl.Ack ack,
            String observation,
            int effects,
            Fresh fresh) {
        require(
                !old.id().equals(fresh.id()) && ack.serverId().equals(fresh.serverId()),
                "Recovery reused the old command identity or daemon epoch");
        return new Recovery(
                phase, old.id(), old.serverId(), observation, false, false, effects, ack.containmentConfirmed(), fresh);
    }

    private static void rejected(int status, String code, Runnable operation) {
        try {
            operation.run();
        } catch (SandcrabClient.Rejected failure) {
            require(
                    failure.status() == status && failure.code().equals(code),
                    "Native refusal changed its classification");
            return;
        }
        throw new IllegalStateException("Expected a narrow native JSON refusal: " + code);
    }

    private static WorkerUnavailableException unavailable(Runnable operation) {
        try {
            operation.run();
        } catch (WorkerUnavailableException failure) {
            return failure;
        }
        throw new IllegalStateException("Observation returned without the expected unconfirmed failure");
    }

    private static boolean causedBy(Throwable failure, String text) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean readyOutput(ByteArrayOutputStream bytes, SandcrabEvents.Output output) {
        require(output.stream().equals("stdout"), "Fixture emitted unexpected stderr");
        bytes.writeBytes(output.bytes());
        byte[] expected = "output-ready\n".getBytes(StandardCharsets.UTF_8);
        require(bytes.size() <= expected.length, "Fixture emitted output beyond its rendezvous marker");
        if (bytes.size() < expected.length) {
            return false;
        }
        require(Arrays.equals(bytes.toByteArray(), expected), "Output observer did not see the admitted command");
        return true;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

    record Fresh(String id, String serverId, String state) {}

    record Recovery(
            String phase,
            String oldRunId,
            String oldServerId,
            String observation,
            boolean oldTerminalObserved,
            boolean oldReplyReceiptConfirmed,
            int syntheticHostEffects,
            boolean controllerConfirmedOldContainment,
            Fresh fresh) {}

    record Summary(String result, List<String> checks, List<Recovery> recoveries) {}
}
