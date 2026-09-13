package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** Retries explicit pre-operation checkpoint-lock refusals for capture, restore, and removal. */
final class WorkerCheckpointRequests {
    private WorkerCheckpointRequests() {}

    static JsonNode retryBusy(Function<Duration, JsonNode> request, Runnable authorize) {
        return retryBusy(request, authorize, Duration.ofSeconds(30));
    }

    static JsonNode retryBusy(Function<Duration, JsonNode> request, Runnable authorize, Duration budget) {
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Checkpoint operations require a positive time budget");
        }
        long deadline = System.nanoTime() + budget.toNanos();
        JsonNode reply;
        do {
            authorize.run();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new WorkerUnavailableException();
            }
            reply = request.apply(Duration.ofNanos(remaining));
            if (!WorkerResponses.read(reply, Status.class).busy()) {
                return reply;
            }
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return reply;
            }
            pause(Math.min(remaining, Duration.ofMillis(50).toNanos()));
        } while (System.nanoTime() < deadline);
        return reply;
    }

    private static void pause(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WorkerUnavailableException(interrupted);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Status(boolean ok, String code, String reason) {
        Status {
            if (!ok && (code == null || code.isBlank())) {
                throw new IllegalArgumentException("Checkpoint failure requires its code");
            }
        }

        boolean busy() {
            return !ok && "CHECKPOINT_UNAVAILABLE".equals(code) && "BUSY".equals(reason);
        }
    }
}
