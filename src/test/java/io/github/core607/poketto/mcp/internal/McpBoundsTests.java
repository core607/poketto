package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.ObjectMapper;

class McpBoundsTests {
    @Test
    void escapedMaximumCommandsPassTheFilterWithoutAnyGlobalSlot() throws Exception {
        var filter = new McpBodyLimitFilter(new ObjectMapper());
        String command = "\u0061".repeat(16384);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"repo_exec\",\"arguments\":{\"expectedCopyId\":\"new\",\"command\":\""
                + command + "\"}}}";
        var dispatched = new AtomicInteger();
        for (int i = 0; i < 12; i++) {
            filter.doFilter(post(body), new MockHttpServletResponse(), (input, output) -> dispatched.incrementAndGet());
        }
        assertThat(dispatched).hasValue(12);
    }

    @Test
    void theTransportIssuesNoSessionAndServesOnlyPost() throws Exception {
        var harness = new McpTransportHarness(
                new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 16, Duration.ZERO),
                McpTransportHarness.handler(
                        (context, request) -> McpSchema.JSONRPCResponse.result(request.id(), Map.of("ok", true))));
        var initialized = harness.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(initialized.getStatus()).isEqualTo(200);
        assertThat(initialized.getHeader("Mcp-Session-Id")).isNull();
        assertThat(initialized.getContentAsString()).contains("\"ok\":true");
        for (String method : new String[] {"GET", "DELETE"}) {
            var servlet = new MockHttpServletRequest(method, "/mcp");
            var request = ServerRequest.create(servlet, List.of());
            var response = McpTransportHarness.write(harness.transport
                    .routerFunction()
                    .route(request)
                    .orElseThrow()
                    .handle(request));
            assertThat(response.getStatus()).isEqualTo(405);
            assertThat(response.getHeader("Allow")).isEqualTo("POST");
        }
        when(harness.identities.currentIdentity()).thenThrow(new SecurityException("browser session"));
        assertThat(harness.post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void fullBodyIsValidatedBeforeDispatchAndExactBytesAreConsumedOnlyOnce() throws Exception {
        var filter = new McpBodyLimitFilter(new ObjectMapper());
        byte[] oversized = new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES + 1];
        for (int attempt = 0; attempt < 5; attempt++) {
            var request = chunked(oversized);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (input, output) -> fail("oversized data reached SDK"));
            assertThat(response.getStatus()).isEqualTo(413);
        }
        byte[] exact = new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES];
        Arrays.fill(exact, (byte) ' ');
        exact[0] = '{';
        exact[1] = '}';
        filter.doFilter(chunked(exact), new MockHttpServletResponse(), (input, output) -> {
            assertThat(input.getInputStream().read()).isEqualTo('{');
            byte[] remaining = input.getInputStream().readAllBytes();
            assertThat(Arrays.equals(exact, 1, exact.length, remaining, 0, remaining.length))
                    .isTrue();
            assertThat(input.getInputStream().read()).isEqualTo(-1);
        });
    }

    private static MockHttpServletRequest chunked(byte[] bytes) {
        var request = new MockHttpServletRequest("POST", "/mcp") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(bytes);
        return request;
    }

    @Test
    void bodyLimitEnforcesDeclaredAndChunkedBytesAndDoesNotResetOnRepeatedStreamAccess() throws Exception {
        var filter = new McpBodyLimitFilter(new ObjectMapper());
        var declared = new MockHttpServletRequest("POST", "/mcp");
        declared.setContent(new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES + 1]);
        var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (request, output) -> fail("oversized initialization reached SDK"));
        assertThat(response.getStatus()).isEqualTo(413);
        var chunked = new MockHttpServletRequest("POST", "/mcp") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContent(new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES + 1]);
        var chunkedResponse = new MockHttpServletResponse();
        filter.doFilter(
                chunked, chunkedResponse, (request, output) -> fail("oversized chunked initialization reached SDK"));
        assertThat(chunkedResponse.getStatus()).isEqualTo(413);
        var valid = new MockHttpServletRequest("POST", "/mcp");
        byte[] validBytes = new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES];
        Arrays.fill(validBytes, (byte) ' ');
        validBytes[0] = '{';
        validBytes[1] = '}';
        valid.setContent(validBytes);
        for (int i = 0; i < 5; i++) {
            filter.doFilter(
                    valid,
                    new MockHttpServletResponse(),
                    (request, output) -> request.getInputStream().readAllBytes());
        }
    }

    private static MockHttpServletRequest post(String body) {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
