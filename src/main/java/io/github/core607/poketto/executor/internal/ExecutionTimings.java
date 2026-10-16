package io.github.core607.poketto.executor.internal;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wall time of each stage of one {@code repo_exec} call, recorded once when the call ends.
 *
 * <p>The record carries durations and a bridge request count only; the request identifier the
 * diagnostics filter attaches joins it to the call's outcome record. A stage the call never
 * reached is reported as {@code -1}, so a refusal shows where it stopped.
 */
final class ExecutionTimings {
    private static final Logger log = LoggerFactory.getLogger(ExecutionTimings.class);
    private final long started = System.nanoTime();
    private final long[] stages = new long[Stage.values().length];
    private long mark = started;
    private long bridgeNanos;
    private int bridgeRequests;

    ExecutionTimings() {
        Arrays.fill(stages, -1);
    }

    /** Ends {@code stage} at the current instant; the next stage starts here. */
    void end(Stage stage) {
        long now = System.nanoTime();
        stages[stage.ordinal()] = now - mark;
        mark = now;
    }

    void bridged(long nanos) {
        bridgeNanos += nanos;
        bridgeRequests++;
    }

    void record() {
        var entry = log.atInfo();
        var message = new StringBuilder("repo_exec stages");
        for (Stage stage : Stage.values()) {
            long milliseconds = milliseconds(stages[stage.ordinal()]);
            entry = entry.addKeyValue(stage.key, milliseconds);
            message.append(' ').append(stage.key).append('=').append(milliseconds);
        }
        long total = milliseconds(System.nanoTime() - started);
        entry.addKeyValue("bridgeMs", milliseconds(bridgeNanos))
                .addKeyValue("bridgeRequests", bridgeRequests)
                .addKeyValue("totalMs", total)
                .setMessage(message.append(" bridgeMs={} bridgeRequests={} totalMs={}")
                        .toString())
                .addArgument(milliseconds(bridgeNanos))
                .addArgument(bridgeRequests)
                .addArgument(total)
                .log();
    }

    private static long milliseconds(long nanos) {
        return nanos < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    /** In call order; {@link #COMMAND} includes the bridge requests answered while it ran. */
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
