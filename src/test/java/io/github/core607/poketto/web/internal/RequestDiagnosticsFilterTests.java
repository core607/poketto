package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The recorded route is the only part of a request that reaches a log, so these cover what it must
 * not carry rather than only what it does.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestDiagnosticsFilterTests {

    private final RequestDiagnosticsFilter filter = new RequestDiagnosticsFilter();

    @Test
    void aCompletedRequestIsRecordedWithItsRouteStatusAndCaller(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/public/documents");
        var response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).contains("http request");
        assertThat(output).contains("/api/public/documents");
        assertThat(output).contains("anonymous");
    }

    @Test
    void anAdminRouteIsRecordedWithoutItsWorkspaceIdentifierInline(CapturedOutput output) throws Exception {
        WorkspaceId workspace = WorkspaceId.random();
        var request = new MockHttpServletRequest("POST", "/api/admin/workspaces/" + workspace + "/repository/patch");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).contains("/api/admin/repository/patch");
        assertThat(output).contains(workspace.toString());
    }

    @Test
    void aQueryStringNeverReachesTheRecord(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/admin/workspaces/" + WorkspaceId.random() + "/file");
        request.setQueryString("path=private/letters/2026-01-secret.md");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).doesNotContain("2026-01-secret");
        assertThat(output).doesNotContain("private/letters");
    }

    @Test
    void aServerFailureIsRecordedAtWarning(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/public/documents");
        var response = new MockHttpServletResponse();
        response.setStatus(503);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).contains("WARN");
        assertThat(output).contains("http request");
    }

    @Test
    void containerProbesAreNotRecorded(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).doesNotContain("http request");
    }

    @Test
    void aMalformedAdminRouteStillProducesARecord(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/admin/workspaces/not-a-workspace");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).contains("http request");
    }

    @Test
    void theRequestIdentifierIsAvailableToOtherLoggingAndClearedAfterwards() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/public/documents");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.observed).isNotNull();
        assertThat(UUID.fromString(chain.observed)).isNotNull();
        assertThat(org.slf4j.MDC.get(RequestDiagnosticsFilter.REQUEST_ID)).isNull();
    }

    private static final class MockFilterChain implements FilterChain {
        private String observed;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            observed = org.slf4j.MDC.get(RequestDiagnosticsFilter.REQUEST_ID);
            SecurityContextHolder.clearContext();
        }
    }
}
