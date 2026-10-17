package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpCancellationTests {
    private static ImageMemoryAdmission memory() {
        return new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 16, Duration.ZERO);
    }

    /** A tool that waits for its cancellation, counting how many calls it saw cancelled. */
    private static final class Waiting {
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger cancelled = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        McpSchema.JSONRPCResponse call(McpCancellation cancellation, McpSchema.JSONRPCRequest request) {
            if (calls.incrementAndGet() == 1) {
                var stopped = new CountDownLatch(1);
                try (var ignored = cancellation.onCancel(stopped::countDown)) {
                    entered.countDown();
                    if (stopped.await(10, TimeUnit.SECONDS)) {
                        cancelled.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }
            return McpSchema.JSONRPCResponse.result(request.id(), Map.of("done", true));
        }
    }

    private static McpTransportHarness harness(Waiting tool) {
        return new McpTransportHarness(
                memory(),
                McpTransportHarness.handler((context, request) ->
                        tool.call((McpCancellation) context.get(McpCancellation.CONTEXT_KEY), request)));
    }

    private static CompletableFuture<String> running(McpTransportHarness harness, Object id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return harness.post(McpTransportHarness.toolCall(id, "probe")).getContentAsString();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @Test
    void theCallersCancellationReachesTheRunningCallAndTheIdIsReusableAfterwards() throws Exception {
        var tool = new Waiting();
        var harness = harness(tool);
        var first = running(harness, 7);
        assertThat(tool.entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(harness.post(McpTransportHarness.cancelled(7)).getStatus()).isEqualTo(202);
        assertThat(first.get(5, TimeUnit.SECONDS)).contains("\"done\":true");
        assertThat(tool.cancelled).hasValue(1);

        assertThat(harness.post(McpTransportHarness.toolCall(7, "probe")).getContentAsString())
                .contains("\"done\":true");
        assertThat(tool.calls).hasValue(2);
    }

    @Test
    void anotherKeyCannotCancelAndAnIdInFlightIsRefused() throws Exception {
        var tool = new Waiting();
        var harness = harness(tool);
        var first = running(harness, "same");
        assertThat(tool.entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(harness.post(McpTransportHarness.toolCall("same", "probe")).getContentAsString())
                .contains("-32600", "request id unavailable");
        var other = mock(AuthPrincipal.class);
        when(other.subjectId()).thenReturn(UUID.randomUUID());
        when(harness.identities.currentIdentity())
                .thenReturn(new McpIdentities.Identity(other, harness.identity.workspace()));
        harness.post(McpTransportHarness.cancelled("same"));
        assertThat(first).isNotDone();
        assertThat(tool.cancelled).hasValue(0);

        harness.calls.revoke(new AuthRevocation(
                harness.identity.workspace(),
                Set.of(),
                Set.of(harness.identity.principal().subjectId())));
        assertThat(first.get(5, TimeUnit.SECONDS)).contains("\"done\":true");
        assertThat(tool.cancelled).hasValue(1);
    }

    @Test
    void closingTheTransportCancelsWorkInFlightAndRefusesNewRequests() throws Exception {
        var tool = new Waiting();
        var harness = harness(tool);
        var first = running(harness, 1);
        assertThat(tool.entered.await(5, TimeUnit.SECONDS)).isTrue();

        harness.transport.closeGracefully().block(Duration.ofSeconds(5));
        assertThat(first.get(5, TimeUnit.SECONDS)).contains("\"done\":true");
        assertThat(tool.cancelled).hasValue(1);
        assertThat(harness.post(McpTransportHarness.toolCall(2, "probe")).getStatus())
                .isEqualTo(503);
    }

    @Test
    void numericAndTextIdsStayDistinct() {
        assertThat(McpCalls.requestKey(1)).isNotEqualTo(McpCalls.requestKey("1"));
        assertThat(McpCalls.requestKey(1.0)).isEqualTo(McpCalls.requestKey(1));
        assertThat(McpCalls.requestKey("x".repeat(129))).isNull();
        assertThat(McpCalls.requestKey(Map.of())).isNull();
    }

    @Test
    void registrationAfterCancellationRunsImmediatelyAndRemovalAndFailureAreContained() {
        var cancellation = new McpCancellation();
        var calls = new AtomicInteger();
        cancellation.onCancel(calls::incrementAndGet).close();
        cancellation.onCancel(() -> {
            throw new IllegalStateException("synthetic termination failure");
        });
        cancellation.onCancel(calls::incrementAndGet);
        cancellation.cancel();
        cancellation.cancel();
        cancellation.onCancel(calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(cancellation.isCancelled()).isTrue();
    }
}
