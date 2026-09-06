package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.core.publisher.Mono;

class McpBoundsTests {
    @Test
    void onlyThrowableEntitiesAreNormalizedAndResponseMetadataSurvives() {
        var original = org.springframework.web.servlet.function.ServerResponse.badRequest()
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("Allow", "POST, DELETE")
                .cookie(new jakarta.servlet.http.Cookie("fixture", "value"))
                .body(io.modelcontextprotocol.spec.McpError.builder(-32600)
                        .message("Invalid message format")
                        .build());
        var normalized = McpTransportConfiguration.normalizeError(original);
        assertThat(normalized.statusCode()).isEqualTo(original.statusCode());
        assertThat(normalized.headers()).isEqualTo(original.headers());
        assertThat(normalized.cookies()).isEqualTo(original.cookies());
        var body = (java.util.Map<?, ?>)
                ((org.springframework.web.servlet.function.EntityResponse<?>) normalized).entity();
        assertThat(body.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("jsonrpc", "error");
        var rpc = org.springframework.web.servlet.function.ServerResponse.badRequest()
                .body(io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.error(
                        42,
                        io.modelcontextprotocol.spec.McpError.builder(-32600)
                                .message("Invalid request")
                                .build()
                                .getJsonRpcError()));
        assertThat(McpTransportConfiguration.normalizeError(rpc)).isSameAs(rpc);
        var accepted = org.springframework.web.servlet.function.ServerResponse.accepted()
                .build();
        assertThat(McpTransportConfiguration.normalizeError(accepted)).isSameAs(accepted);
    }

    @Test
    void fullBodyIsValidatedBeforeDispatchAndExactBytesAreConsumedOnlyOnce() throws Exception {
        var filter = new McpBodyLimitFilter(
                new tools.jackson.databind.ObjectMapper(),
                new io.github.core607.poketto.assets.ImageMemoryAdmission(256L * 1024 * 1024, 16, Duration.ZERO));
        byte[] oversized = new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES + 1];
        for (int attempt = 0; attempt < 5; attempt++) {
            var request = chunked(oversized);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (input, output) -> fail("oversized data reached SDK"));
            assertThat(response.getStatus()).isEqualTo(413);
        }
        byte[] exact = new byte[McpBodyLimitFilter.MAX_REQUEST_BYTES];
        java.util.Arrays.fill(exact, (byte) ' ');
        exact[0] = '{';
        exact[1] = '}';
        filter.doFilter(chunked(exact), new MockHttpServletResponse(), (input, output) -> {
            assertThat(input.getInputStream().read()).isEqualTo('{');
            byte[] remaining = input.getInputStream().readAllBytes();
            assertThat(java.util.Arrays.equals(exact, 1, exact.length, remaining, 0, remaining.length))
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
        request.addHeader("Mcp-Session-Id", "server-session");
        request.setContent(bytes);
        return request;
    }

    @Test
    void actualSdkSessionIdsAreBoundToKeysExpireAndReleaseCapacity() {
        var time = new MutableClock();
        List<Object> events = new ArrayList<>();
        var workspace = WorkspaceId.random();
        var owner = identity(workspace);
        var other = identity(workspace);
        try (var sessions = new McpSessions(
                mock(AuthService.class), mock(WorkspaceCatalog.class), events::add, time, Duration.ofMinutes(30), 1)) {
            var first = session("server-first");
            sessions.bind(first, owner);
            assertThatThrownBy(() -> sessions.check("server-first", other)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> sessions.check("invented", owner)).isInstanceOf(SecurityException.class);
            var excess = session("server-excess");
            assertThatThrownBy(() -> sessions.bind(excess, owner)).isInstanceOf(IllegalStateException.class);
            verify(excess).closeGracefully();
            time.now = time.now.plusSeconds(1799);
            sessions.check("server-first", owner);
            sessions.remove("server-first", McpSessionClosed.Reason.IDLE_EXPIRY);
            assertThat(events).isEmpty();
            time.now = time.now.plusSeconds(1800);
            assertThatThrownBy(() -> sessions.check("server-first", owner)).isInstanceOf(SecurityException.class);
            sessions.remove("server-first", McpSessionClosed.Reason.IDLE_EXPIRY);
            verify(first).closeGracefully();
            sessions.bind(session("server-second"), owner);
        }
        assertThat(events).hasSize(2);
        assertThat(((McpSessionClosed) events.getFirst()).reason()).isEqualTo(McpSessionClosed.Reason.IDLE_EXPIRY);
        assertThat(((McpSessionClosed) events.getLast()).reason()).isEqualTo(McpSessionClosed.Reason.SHUTDOWN);
    }

    @Test
    void bodyLimitEnforcesDeclaredAndChunkedBytesAndDoesNotResetOnRepeatedStreamAccess() throws Exception {
        var filter = new McpBodyLimitFilter(
                new tools.jackson.databind.ObjectMapper(),
                new io.github.core607.poketto.assets.ImageMemoryAdmission(256L * 1024 * 1024, 16, Duration.ZERO));
        var declared = new MockHttpServletRequest("POST", "/mcp");
        declared.setContent(new byte[McpBodyLimitFilter.MAX_INITIALIZE_BYTES + 1]);
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
        chunked.setContent(new byte[McpBodyLimitFilter.MAX_INITIALIZE_BYTES + 1]);
        var chunkedResponse = new MockHttpServletResponse();
        filter.doFilter(
                chunked, chunkedResponse, (request, output) -> fail("oversized chunked initialization reached SDK"));
        assertThat(chunkedResponse.getStatus()).isEqualTo(413);
        var valid = new MockHttpServletRequest("POST", "/mcp");
        byte[] validBytes = new byte[McpBodyLimitFilter.MAX_INITIALIZE_BYTES];
        java.util.Arrays.fill(validBytes, (byte) ' ');
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

    @Test
    void cancellationHasBoundedReservedAdmissionWhenAllDataPostsAreActive() throws Exception {
        var filter = new McpBodyLimitFilter(
                new tools.jackson.databind.ObjectMapper(),
                new io.github.core607.poketto.assets.ImageMemoryAdmission(256L * 1024 * 1024, 16, Duration.ZERO));
        var held = new ArrayList<MockHttpServletRequest>();
        try {
            for (int i = 0; i < 4; i++) {
                var request = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}");
                filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> input.startAsync());
                held.add(request);
            }
            var rejected = new MockHttpServletResponse();
            filter.doFilter(post("{}"), rejected, (input, output) -> fail("fifth data POST reached SDK"));
            assertThat(rejected.getStatus()).isEqualTo(429);
            for (int i = 0; i < 4; i++) {
                var request = post(
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}");
                filter.doFilter(request, new MockHttpServletResponse(), (input, output) -> input.startAsync());
                held.add(request);
                assertThat(request.isAsyncStarted()).isTrue();
            }
            var excessControl = new MockHttpServletResponse();
            filter.doFilter(
                    post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}"),
                    excessControl,
                    (input, output) -> fail("fifth control POST reached SDK"));
            assertThat(excessControl.getStatus()).isEqualTo(429);
        } finally {
            held.forEach(request -> request.getAsyncContext().complete());
        }
        var admitted = new MockHttpServletResponse();
        filter.doFilter(
                post("{}"), admitted, (input, output) -> output.getWriter().write("released"));
        assertThat(admitted.getContentAsString()).isEqualTo("released");
    }

    private static MockHttpServletRequest post(String body) {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setAsyncSupported(true);
        request.addHeader("Mcp-Session-Id", "server-session");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private static McpSessions.Identity identity(WorkspaceId workspace) {
        var principal = mock(AuthPrincipal.class);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        return new McpSessions.Identity(principal, workspace);
    }

    private static McpStreamableServerSession session(String id) {
        var session = mock(McpStreamableServerSession.class);
        when(session.getId()).thenReturn(id);
        when(session.closeGracefully()).thenReturn(Mono.empty());
        return session;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
