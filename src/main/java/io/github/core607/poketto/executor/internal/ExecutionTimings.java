package io.github.core607.poketto.executor.internal;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Wall time of each stage of one {@code repo_exec} call, recorded once when the call ends.
 *
 * <p>The record carries durations and a bridge request count only; the request identifier the
 * diagnostics filter attaches joins it to the call's outcome record. Stages run in a fixed order,
 * so a call that ends early stopped in the stage after the last one it finished: that stage
 * reports the time spent in it and is named by {@code stoppedIn}, and only stages the call never
 * entered read {@code -1}. A failure closes that stage when it is caught, so stopping the sandbox
 * and releasing the journal afterwards are reported apart, as {@code cleanupMs}.
 *
 * <p>A command's result also names the stages it spent inside the worker, which the record adds
 * with a {@code worker} prefix, and whether it started a fresh sandbox.
 */
final class ExecutionTimings {
    private static final Logger log = LoggerFactory.getLogger(ExecutionTimings.class);
    private final long started = System.nanoTime();
    private final long[] stages = new long[Stage.values().length];
    private long mark = started;
    private long failedAt;
    private int finished = -1;
    private long bridgeNanos;
    private int bridgeRequests;
    private final Map<String, Object> worker = new LinkedHashMap<>();
    private static final List<String> WORKER_STAGES =
            List.of("roomMs", "lockMs", "prepareMs", "runMs", "releaseMs", "flushMs");

    ExecutionTimings() {
        Arrays.fill(stages, -1);
    }

    /** Ends {@code stage} at the current instant; the next stage starts here. */
    void end(Stage stage) {
        long now = System.nanoTime();
        stages[stage.ordinal()] = now - mark;
        mark = now;
        finished = stage.ordinal();
    }

    /** Marks the instant a failure was caught; only the first mark counts. */
    void failed() {
        if (failedAt == 0) {
            failedAt = System.nanoTime();
        }
    }

    /** Takes the worker's own stage timings from a command result, ignoring any it did not report. */
    void worker(JsonNode result) {
        worker.put("freshSandbox", result.path("freshSandbox").asBoolean(false));
        for (String stage : WORKER_STAGES) {
            JsonNode value = result.path("timings").path(stage);
            if (value.isIntegralNumber() && value.asLong() >= 0 && value.asLong() <= TimeUnit.HOURS.toMillis(1)) {
                worker.put("worker" + Character.toUpperCase(stage.charAt(0)) + stage.substring(1), value.asLong());
            }
        }
    }

    void bridged(long nanos) {
        bridgeNanos += nanos;
        bridgeRequests++;
    }

    void record() {
        long now = System.nanoTime();
        long stopped = failedAt == 0 ? now : failedAt;
        Stage[] all = Stage.values();
        String stoppedIn = "none";
        if (finished < all.length - 1) {
            stages[finished + 1] = Math.max(0, stopped - mark);
            stoppedIn = all[finished + 1].key;
        }
        var entry = log.atInfo().addKeyValue("stoppedIn", stoppedIn);
        var message = new StringBuilder("repo_exec stages stoppedIn=").append(stoppedIn);
        for (Stage stage : Stage.values()) {
            long milliseconds = milliseconds(stages[stage.ordinal()]);
            entry = entry.addKeyValue(stage.key, milliseconds);
            message.append(' ').append(stage.key).append('=').append(milliseconds);
        }
        long total = milliseconds(now - started);
        long cleanup = failedAt == 0 ? 0 : milliseconds(now - failedAt);
        var reported = new StringBuilder();
        for (var stage : worker.entrySet()) {
            entry = entry.addKeyValue(stage.getKey(), stage.getValue());
            reported.append(' ').append(stage.getKey()).append('=').append(stage.getValue());
        }
        entry.addKeyValue("bridgeMs", milliseconds(bridgeNanos))
                .addKeyValue("bridgeRequests", bridgeRequests)
                .addKeyValue("cleanupMs", cleanup)
                .addKeyValue("totalMs", total)
                .setMessage(message.append(" bridgeMs={} bridgeRequests={} cleanupMs={} totalMs={}")
                        .append(reported)
                        .toString())
                .addArgument(milliseconds(bridgeNanos))
                .addArgument(bridgeRequests)
                .addArgument(cleanup)
                .addArgument(total)
                .log();
    }

    private static long milliseconds(long nanos) {
        return nanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    /**
     * In call order. {@link #AUTHORIZE} includes input validation, and {@link #COMMAND} includes the
     * bridge requests answered while it ran.
     */
    enum Stage {
        AUTHORIZE("authorizeMs"),
        JOURNAL("journalMs"),
        PERMIT("permitMs"),
        ADMIT("admitMs"),
        PREPARE("prepareMs"),
        COMMAND("commandMs"),
        COMPLETE("completeMs");

        private final String key;

        Stage(String key) {
            this.key = key;
        }
    }
}
