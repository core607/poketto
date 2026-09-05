package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class McpCancellationTests {
    @Test
    void actualSdkToolReceivesNotificationCancellationAndReusesCompletedRequestId() throws Exception {
        var factory = new AtomicReference<McpStreamableServerSession.Factory>();
        var provider = mock(McpStreamableServerTransportProvider.class);
        when(provider.protocolVersions()).thenReturn(List.of("2025-11-25"));
        when(provider.closeGracefully()).thenReturn(Mono.empty());
        doAnswer(call -> {
                    factory.set(call.getArgument(0));
                    return null;
                })
                .when(provider)
                .setSessionFactory(any());
        var entered = new CountDownLatch(1);
        var terminated = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var cancellationSeen = new AtomicReference<ExecutionCancellation>();
        var tool = new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("probe", Map.of("type", "object", "properties", Map.of()))
                        .build(),
                (exchange, request) -> {
                    var cancellation =
                            (ExecutionCancellation) exchange.transportContext().get(McpCancellation.CONTEXT_KEY);
                    cancellationSeen.set(cancellation);
                    if (calls.incrementAndGet() == 1) {
                        try (var ignored = cancellation.onCancel(terminated::countDown)) {
                            entered.countDown();
                            assertThat(terminated.await(10, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("probe completed")
                            .isError(false)
                            .build();
                });
        var server = McpServer.sync(provider)
                .serverInfo("test", "1")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tool)
                .build();
        var initialize = new McpSchema.InitializeRequest(
                "2025-11-25",
                McpSchema.ClientCapabilities.builder().build(),
                new McpSchema.Implementation("test", "1"));
        var initialized = factory.get().startSession(initialize);
        initialized.initResult().block(Duration.ofSeconds(5));
        var session = new CancellableMcpSession(initialized.session(), initialize);
        var output = mock(McpStreamableServerTransport.class);
        when(output.sendMessage(any())).thenReturn(Mono.empty());
        when(output.closeGracefully()).thenReturn(Mono.empty());
        try {
            var request = new McpSchema.JSONRPCRequest("tools/call", 7, Map.of("name", "probe", "arguments", Map.of()));
            var first = session.responseStream(request, output).toFuture();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            session.accept(new McpSchema.JSONRPCNotification("notifications/cancelled", Map.of("requestId", "7")))
                    .block(Duration.ofSeconds(5));
            assertThat(cancellationSeen.get().isCancelled()).isFalse();
            session.accept(new McpSchema.JSONRPCNotification("notifications/cancelled", Map.of("requestId", 7L)))
                    .block(Duration.ofSeconds(5));
            first.get(5, TimeUnit.SECONDS);
            assertThat(cancellationSeen.get().isCancelled()).isTrue();
            session.responseStream(request, output).block(Duration.ofSeconds(5));
            assertThat(calls.get()).isEqualTo(2);
            assertThat(cancellationSeen.get().isCancelled()).isFalse();
        } finally {
            session.closeGracefully().block(Duration.ofSeconds(5));
            server.closeGracefully();
        }
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

    @Test
    void identicalRequestIdsInDifferentSdkSessionsRemainIsolatedAndCloseCancelsRemainingWork() {
        var signals = new ConcurrentHashMap<String, ExecutionCancellation>();
        McpRequestHandler<Object> handler = (exchange, params) -> Mono.defer(() -> {
            signals.put(exchange.sessionId(), (ExecutionCancellation)
                    exchange.transportContext().get(McpCancellation.CONTEXT_KEY));
            return Mono.never();
        });
        var initialize = new McpSchema.InitializeRequest(
                "2025-11-25",
                McpSchema.ClientCapabilities.builder().build(),
                new McpSchema.Implementation("test", "1"));
        var first = session(initialize, handler);
        var second = session(initialize, handler);
        var output = mock(McpStreamableServerTransport.class);
        when(output.sendMessage(any())).thenReturn(Mono.empty());
        when(output.closeGracefully()).thenReturn(Mono.empty());
        var request = new McpSchema.JSONRPCRequest("tools/call", "same-id", Map.of());
        var firstCall = first.responseStream(request, output).subscribe();
        var secondCall = second.responseStream(request, output).subscribe();
        try {
            first.accept(new McpSchema.JSONRPCNotification("notifications/cancelled", Map.of("requestId", "same-id")))
                    .block(Duration.ofSeconds(5));
            assertThat(signals.get(first.getId()).isCancelled()).isTrue();
            assertThat(signals.get(second.getId()).isCancelled()).isFalse();
            second.closeGracefully().block(Duration.ofSeconds(5));
            assertThat(signals.get(second.getId()).isCancelled()).isTrue();
        } finally {
            firstCall.dispose();
            secondCall.dispose();
            first.closeGracefully().block(Duration.ofSeconds(5));
        }
    }

    private static CancellableMcpSession session(
            McpSchema.InitializeRequest initialize, McpRequestHandler<Object> handler) {
        var sdk = new McpStreamableServerSession(
                UUID.randomUUID().toString(),
                initialize.capabilities(),
                initialize.clientInfo(),
                Duration.ofMinutes(1),
                Map.of("tools/call", handler),
                Map.of());
        return new CancellableMcpSession(sdk, initialize);
    }
}
