package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.assets.AssetBytes;
import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import jakarta.servlet.AsyncEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

class McpImageMemoryTests {
    @Test
    void bodyFilterRejectsUnboundedIdsWithoutEchoingOrDispatchingAndAcceptsLegalIds() throws Exception {
        var memory = memory();
        var json = new ObjectMapper();
        var filter = new McpBodyLimitFilter(json, memory);
        for (Object id : new Object[] {
            "图".repeat(129), "x".repeat(1024 * 1024), java.math.BigInteger.TEN.pow(128), Map.of("invalid", "value")
        }) {
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    post(json.writeValueAsString(Map.of("method", "tools/list", "id", id))),
                    response,
                    (input, output) -> fail("unbounded id reached SDK"));
            assertThat(response.getStatus()).isEqualTo(400);
            var error = json.readTree(response.getContentAsString());
            assertThat(error.has("id")).isFalse();
            assertThat(error.path("error").path("code").intValue()).isEqualTo(-32600);
            assertThat(response.getContentAsByteArray()).hasSizeLessThan(200);
            assertThat(memory.reservedBytes()).isZero();
        }
        for (Object id : new Object[] {"图".repeat(128), Long.MIN_VALUE, Long.MAX_VALUE}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    post(json.writeValueAsString(Map.of("method", "tools/list", "id", id))),
                    response,
                    (input, output) -> output.getWriter().write("dispatched"));
            assertThat(response.getContentAsString()).isEqualTo("dispatched");
        }
    }

    @Test
    void envelopeDepthAndTokenLimitsBoundSdkNodeAllocationWithoutRejectingLargeText() throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper(), memory);
        for (String body : new String[] {"[".repeat(33) + "]".repeat(33), "[" + "[],".repeat(2050) + "[]]"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(post(body), response, (input, output) -> fail("unbounded tree reached SDK"));
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(memory.reservedBytes()).isZero();
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(
                post("{\"id\":1,\"params\":{\"text\":\"" + "x".repeat(1024 * 1024) + "\"}}"),
                response,
                (input, output) -> output.getWriter().write("dispatched"));
        assertThat(response.getContentAsString()).isEqualTo("dispatched");
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void getAssetEnvelopeLimitAlsoAppliesWhenToolNameFollowsArguments() throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper(), memory);
        String body = "{\"method\":\"tools/call\",\"id\":1,\"params\":{\"arguments\":{\"ignored\":\""
                + "x".repeat(16384) + "\"},\"name\":\"get_asset\"}}";
        var output = new MockHttpServletResponse();
        filter.doFilter(post(body), output, (input, response) -> fail("large get_asset envelope reached SDK"));
        assertThat(output.getStatus()).isEqualTo(413);
        assertThat(memory.reservedBytes()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothStartAsyncFormsInstallBeforeAnEarlyFailureAndDoNotReleaseTheRunningChain(boolean supplied)
            throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper(), memory);
        var request = post(imageCall("get_asset"));
        var output = new MockHttpServletResponse();
        filter.doFilter(request, output, (input, response) -> {
            var async = (MockAsyncContext) (supplied ? input.startAsync(input, response) : input.startAsync());
            assertThat(async.getListeners()).hasSize(1);
            async.getListeners().getFirst().onError(new AsyncEvent(async, new IOException("early write failure")));
            assertThat(output.getStatus()).isEqualTo(500);
            assertThat(output.getContentAsString()).contains("MCP response unavailable");
            assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        });
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void aSecondAsyncCycleReattachesAndTimeoutFinishesWithoutAnEmptySuccess() throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper(), memory);
        var request = post(imageCall("get_asset"));
        var output = new MockHttpServletResponse();
        filter.doFilter(request, output, (input, response) -> input.startAsync());
        var first = (MockAsyncContext) request.getAsyncContext();
        var second = new MockAsyncContext(request, output);
        first.getListeners().getFirst().onStartAsync(new AsyncEvent(second));
        assertThat(second.getListeners()).hasSize(1);
        second.getListeners().getFirst().onTimeout(new AsyncEvent(second));
        assertThat(output.getStatus()).isEqualTo(503);
        assertThat(output.getContentAsString()).contains("MCP response unavailable");
        assertThat(memory.reservedBytes()).isZero();
        first.complete();
        second.complete();
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualImageToolKeepsAnInterruptedReadAccountedAndLateCallbackCannotStartAnotherRead() throws Exception {
        var memory = memory();
        var scope = memory.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var service = mock(AssetService.class);
        var assets = (ObjectProvider<AssetService>) mock(ObjectProvider.class);
        when(assets.getObject()).thenReturn(service);
        when(assets.getIfAvailable()).thenReturn(service);
        var executors = (ObjectProvider<RepositoryExecutor>) mock(ObjectProvider.class);
        var sessions = mock(McpSessions.class);
        when(sessions.resolve(any()))
                .thenReturn(new McpSessions.Identity(mock(AuthPrincipal.class), WorkspaceId.random()));
        when(service.readExact(any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return new AssetBytes(
                    new AssetSource.Repository(Optional.of("a".repeat(40)), "private/image.png"),
                    "image-revision",
                    "image/png",
                    new byte[] {1});
        });
        var tool = new RepositoryMcpTools(sessions, null, null, null, assets, executors, new ObjectMapper())
                .specifications().stream()
                        .filter(candidate -> candidate.tool().name().equals("get_asset"))
                        .findFirst()
                        .orElseThrow();
        var exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(ImageRequestScope.ATTRIBUTE, scope)));
        var request = new McpSchema.CallToolRequest(
                "get_asset", Map.of("source", Map.of("kind", "repository", "path", "private/image.png")));
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                assertThat(tool.callHandler().apply(exchange, request).isError())
                        .isFalse();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finished.countDown();
            }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            scope.responseComplete();
            assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
            var late = tool.callHandler().apply(exchange, request);
            assertThat(late.isError()).isTrue();
            verify(service, times(1)).readExact(any(), any(), any());
        } finally {
            release.countDown();
        }
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void imageCallsAndLargeEnvelopesShareBrowserCapacityButSmallControlsDoNot() throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper(), memory);
        var download = memory.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        try {
            for (String body : new String[] {imageCall("get_asset"), imageCall("put_asset"), " ".repeat(16385)}) {
                var output = new MockHttpServletResponse();
                filter.doFilter(post(body), output, (request, response) -> fail("image allocation reached"));
                assertThat(output.getStatus()).isEqualTo(429);
                assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.BROWSER_BYTES);
            }
            for (String body : new String[] {
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}",
                "{\"method\":\"tools/list\",\"id\":3}",
                imageCall("get_file")
            }) {
                var output = new MockHttpServletResponse();
                filter.doFilter(post(body), output, (request, response) -> {
                    assertThat(request.getAttribute(ImageRequestScope.ATTRIBUTE))
                            .isNull();
                    response.getWriter().write("control available");
                });
                assertThat(output.getContentAsString()).isEqualTo("control available");
            }
        } finally {
            download.responseComplete();
        }
        filter.doFilter(post(imageCall("get_asset")), new MockHttpServletResponse(), (request, response) -> {
            assertThat(request.getAttribute(ImageRequestScope.ATTRIBUTE)).isInstanceOf(ImageRequestScope.class);
            assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        });
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void asyncTimeoutAndDisconnectCannotReleaseWhileActualProducerContinues() throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper(), memory);
        var request = post(imageCall("get_asset"));
        var actualProducer = new AtomicReference<ImageRequestScope.Producer>();
        filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> {
            actualProducer.set(((ImageRequestScope) input.getAttribute(ImageRequestScope.ATTRIBUTE)).producer());
            input.startAsync();
        });
        var async = (MockAsyncContext) request.getAsyncContext();
        for (var listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
            listener.onError(new AsyncEvent(async, new IOException("disconnected")));
        }
        assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        async.complete();
        assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        actualProducer.get().close();
        assertThat(memory.reservedBytes()).isZero();
    }

    @Test
    void cancelledBlockingSseWriteRetainsBudgetUntilTheActualWriteExits() throws Exception {
        var memory = memory();
        var scope = memory.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var output = mock(McpStreamableServerTransport.class);
        when(output.sendMessage(any())).thenReturn(Mono.fromRunnable(() -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            exited.countDown();
        }));
        var stream = new ImageBudgetTransport(output, scope)
                .sendMessage(McpSchema.JSONRPCResponse.result(1, Map.of("image", "payload")))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            stream.dispose();
            scope.responseComplete();
            assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
            assertThat(memory.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES)).isEmpty();
        } finally {
            release.countDown();
        }
        assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
        awaitReleased(memory);
    }

    @Test
    void actualSdkResponseDetachesIncomingStreamAndCancellationCallbacks() throws Exception {
        var memory = memory();
        var scope = memory.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        var seen = new AtomicReference<McpCancellation>();
        McpRequestHandler<Object> handler = (exchange, parameters) -> Mono.fromCallable(() -> {
            var current = (ImageRequestScope) exchange.transportContext().get(ImageRequestScope.ATTRIBUTE);
            try (var producer = current.producer()) {
                var cancellation = (McpCancellation) exchange.transportContext().get(McpCancellation.CONTEXT_KEY);
                seen.set(cancellation);
                cancellation.onCancel(() -> {});
                return McpSchema.CallToolResult.builder()
                        .addContent(McpSchema.ImageContent.builder("AAAA", "image/png")
                                .build())
                        .build();
            }
        });
        var initialize = new McpSchema.InitializeRequest(
                "2025-11-25",
                McpSchema.ClientCapabilities.builder().build(),
                new McpSchema.Implementation("test", "1"));
        var sdk = new McpStreamableServerSession(
                UUID.randomUUID().toString(),
                initialize.capabilities(),
                initialize.clientInfo(),
                Duration.ofSeconds(5),
                Map.of("tools/call", handler),
                Map.of());
        var session = new CancellableMcpSession(sdk, initialize);
        var output = mock(McpStreamableServerTransport.class);
        when(output.sendMessage(any()))
                .thenAnswer(call -> Mono.fromRunnable(
                        () -> assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES)));
        when(output.closeGracefully()).thenReturn(Mono.empty());
        try {
            session.responseStream(new McpSchema.JSONRPCRequest("tools/call", 1, Map.of()), output)
                    .contextWrite(context -> context.put(
                            McpTransportContext.KEY,
                            McpTransportContext.create(Map.of(ImageRequestScope.ATTRIBUTE, scope))))
                    .block(Duration.ofSeconds(5));
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(sdk, "requestIdToStream"))
                    .isEmpty();
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(session, "active"))
                    .isEmpty();
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(seen.get(), "callbacks"))
                    .isEmpty();
            assertThat(session.replay("any").collectList().block()).isEmpty();
            verify(output).closeGracefully();
            assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        } finally {
            scope.responseComplete();
            session.close();
        }
        assertThat(memory.reservedBytes()).isZero();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("test producer was not released");
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void awaitReleased(ImageMemoryAdmission memory) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (memory.reservedBytes() != 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertThat(memory.reservedBytes()).isZero();
    }

    private static ImageMemoryAdmission memory() {
        return new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 16, Duration.ZERO);
    }

    private static String imageCall(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1,\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}";
    }

    private static MockHttpServletRequest post(String body) {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setAsyncSupported(true);
        request.addHeader("Mcp-Session-Id", "server-session");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
