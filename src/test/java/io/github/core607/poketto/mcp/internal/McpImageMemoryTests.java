package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetBytes;
import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.AssetSource;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class McpImageMemoryTests {
    @Test
    void bodyFilterRejectsUnboundedIdsWithoutEchoingOrDispatchingAndAcceptsLegalIds() throws Exception {
        var memory = memory();
        var json = new ObjectMapper();
        var filter = new McpBodyLimitFilter(json);
        for (Object id :
                new Object[] {"图".repeat(129), "x".repeat(16384), BigInteger.TEN.pow(128), Map.of("invalid", "value")
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
        var filter = new McpBodyLimitFilter(new ObjectMapper());
        for (String body : new String[] {"[".repeat(33) + "]".repeat(33), "[" + "[],".repeat(2050) + "[]]"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(post(body), response, (input, output) -> fail("unbounded tree reached SDK"));
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(memory.reservedBytes()).isZero();
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(
                post("{\"id\":1,\"params\":{\"text\":\"" + "x".repeat(16384) + "\"}}"),
                response,
                (input, output) -> output.getWriter().write("dispatched"));
        assertThat(response.getContentAsString()).isEqualTo("dispatched");
        assertThat(memory.reservedBytes()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_asset", "get_artifact"})
    void smallImageEnvelopesReachTheSdkWithoutImageAllocation(String toolName) throws Exception {
        var memory = memory();
        var filter = new McpBodyLimitFilter(new ObjectMapper());
        String body = "{\"method\":\"tools/call\",\"id\":1,\"params\":{\"arguments\":{\"ignored\":\""
                + "x".repeat(16384) + "\"},\"name\":\"" + toolName + "\"}}";
        var output = new MockHttpServletResponse();
        filter.doFilter(
                post(body), output, (input, response) -> response.getWriter().write("dispatched"));
        assertThat(output.getContentAsString()).isEqualTo("dispatched");
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
        var identities = mock(McpIdentities.class);
        when(identities.resolve(any()))
                .thenReturn(new McpIdentities.Identity(mock(AuthPrincipal.class), WorkspaceId.random()));
        when(service.readExact(any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return new AssetBytes(
                    new AssetSource.Repository(Optional.of("a".repeat(40)), "private/image.png"),
                    "image-revision",
                    "image/png",
                    new byte[] {1});
        });
        var tool = new RepositoryMcpTools(identities, null, assets, executors, new ObjectMapper(), null)
                .specifications().stream()
                        .filter(candidate -> candidate.tool().name().equals("get_asset"))
                        .findFirst()
                        .orElseThrow();
        var context = McpTransportContext.create(Map.of(ImageRequestScope.ATTRIBUTE, scope));
        var request = new McpSchema.CallToolRequest(
                "get_asset", Map.of("source", Map.of("kind", "repository", "path", "private/image.png")));
        var failure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                assertThat(tool.callHandler().apply(context, request).isError()).isFalse();
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
            var late = tool.callHandler().apply(context, request);
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
    void anImageResponseHoldsItsBudgetUntilItsBytesAreWritten() throws Exception {
        var memory = memory();
        var seen = new AtomicReference<McpCancellation>();
        var harness = new McpTransportHarness(memory, McpTransportHarness.handler((context, request) -> {
            var scope = (ImageRequestScope) context.get(ImageRequestScope.ATTRIBUTE);
            try (var producer = scope.producer()) {
                var cancellation = (McpCancellation) context.get(McpCancellation.CONTEXT_KEY);
                seen.set(cancellation);
                cancellation.onCancel(() -> {});
                return McpSchema.JSONRPCResponse.result(request.id(), Map.of("image", "AAAA"));
            }
        }));
        var routed = harness.route(McpTransportHarness.toolCall(1, "get_asset"));
        assertThat(memory.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        assertThat(McpTransportHarness.write(routed).getContentAsString()).contains("AAAA");
        assertThat(memory.reservedBytes()).isZero();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(seen.get(), "callbacks"))
                .isEmpty();
    }

    @Test
    void aBusyImageBudgetRefusesTheReadWithoutCallingTheTool() throws Exception {
        var memory = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 1, Duration.ZERO);
        var held = memory.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        var harness = new McpTransportHarness(memory, McpTransportHarness.handler((context, request) -> {
            throw new AssertionError("the tool ran without image memory");
        }));
        assertThat(harness.post(McpTransportHarness.toolCall(1, "get_artifact")).getContentAsString())
                .contains("IMAGE_MEMORY_BUSY");
        held.responseComplete();
        assertThat(memory.reservedBytes()).isZero();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("test producer was not released");
                    }
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitReleased(ImageMemoryAdmission memory) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (memory.reservedBytes() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
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
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
