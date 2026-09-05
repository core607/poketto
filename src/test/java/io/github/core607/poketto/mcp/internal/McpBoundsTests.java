package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
        var filter = new McpBodyLimitFilter();
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
        assertThatThrownBy(() -> filter.doFilter(chunked, new MockHttpServletResponse(), (request, output) -> {
                    var bounded = (HttpServletRequest) request;
                    assertThat(bounded.getInputStream().readNBytes(McpBodyLimitFilter.MAX_INITIALIZE_BYTES))
                            .hasSize(McpBodyLimitFilter.MAX_INITIALIZE_BYTES);
                    bounded.getInputStream().read();
                }))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("byte limit");
        var valid = new MockHttpServletRequest("POST", "/mcp");
        valid.setContent(new byte[McpBodyLimitFilter.MAX_INITIALIZE_BYTES]);
        for (int i = 0; i < 5; i++) {
            filter.doFilter(
                    valid,
                    new MockHttpServletResponse(),
                    (request, output) -> request.getInputStream().readAllBytes());
        }
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
