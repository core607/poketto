package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkerCheckpointRequestsTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void retriesAnExplicitBusyReplyWithDecreasingBudgetAndFreshAuthorization() {
        var remaining = new ArrayList<Duration>();
        var authorizations = new AtomicInteger();
        JsonNode ready = json.readTree("{\"ok\":true}");
        JsonNode result = WorkerCheckpointRequests.retryBusy(
                timeout -> {
                    remaining.add(timeout);
                    return remaining.size() == 1 ? failure("BUSY") : ready;
                },
                authorizations::incrementAndGet);
        assertThat(result).isSameAs(ready);
        assertThat(authorizations.get()).isEqualTo(2);
        assertThat(remaining).hasSize(2);
        assertThat(remaining.get(1)).isLessThan(remaining.getFirst());
    }

    @Test
    void capacityCorruptionAndUncertainPublicationAreNeverRetried() {
        for (String reason : List.of("CAPACITY", "CORRUPT", "UNCERTAIN", "ALREADY_EXISTS", "UNAVAILABLE")) {
            var calls = new AtomicInteger();
            JsonNode rejected = failure(reason);
            assertThat(WorkerCheckpointRequests.retryBusy(
                            timeout -> {
                                calls.incrementAndGet();
                                return rejected;
                            },
                            () -> {}))
                    .isSameAs(rejected);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test
    void lostResponsesDoNotReplayACheckpointWhosePublicationIsUnknown() {
        var calls = new AtomicInteger();
        var lost = new WorkerUnavailableException();
        assertThatThrownBy(() -> WorkerCheckpointRequests.retryBusy(
                        timeout -> {
                            calls.incrementAndGet();
                            throw lost;
                        },
                        () -> {}))
                .isSameAs(lost);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void revocationDuringContentionPreventsTheNextRequest() {
        var calls = new AtomicInteger();
        var checks = new AtomicInteger();
        assertThatThrownBy(() -> WorkerCheckpointRequests.retryBusy(
                        timeout -> {
                            calls.incrementAndGet();
                            return failure("BUSY");
                        },
                        () -> {
                            if (checks.incrementAndGet() == 2) {
                                throw new SecurityException("Execution permission was revoked");
                            }
                        }))
                .isInstanceOf(SecurityException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void contentionCannotExtendTheOriginalCaptureDeadline() {
        var calls = new AtomicInteger();
        long started = System.nanoTime();
        JsonNode busy = failure("BUSY");
        assertThat(WorkerCheckpointRequests.retryBusy(
                        timeout -> {
                            calls.incrementAndGet();
                            return busy;
                        },
                        () -> {},
                        Duration.ofMillis(20)))
                .isSameAs(busy);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    private JsonNode failure(String reason) {
        return json.readTree("{\"ok\":false,\"code\":\"CHECKPOINT_UNAVAILABLE\",\"reason\":\"" + reason + "\"}");
    }
}
