package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import jakarta.servlet.ServletInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class BrowserBodySecurityTests {
    private AnnotationConfigWebApplicationContext context;
    private FilterChainProxy filters;
    private AuthService auth;

    @BeforeEach
    void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(SecurityFixture.class);
        context.refresh();
        filters = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        auth = context.getBean(AuthService.class);
    }

    @AfterEach
    void close() {
        context.close();
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousAdminRequestsNeverOpenTheBodyOrParseCsrfParameters() throws Exception {
        for (String path :
                new String[] {"/api/admin/assets", "/api/admin/repository/patch", "/api/admin/repository/preview"}) {
            for (String method : new String[] {"GET", "POST"}) {
                for (String type : new String[] {
                    "application/json", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x"
                }) {
                    for (boolean declared : new boolean[] {true, false}) {
                        var request = new CountingRequest(method, path, type, declared);
                        reject(request, 401);
                        assertThat(request.streams).hasValue(0);
                        assertThat(request.parameters).hasValue(0);
                    }
                }
            }
        }
        verifyNoInteractions(auth);
    }

    @Test
    void revokedMembershipAndNonAccountSessionsAreRejectedBeforeBodyReads() throws Exception {
        when(auth.authorize(any(), any())).thenThrow(mock(AuthException.class));
        var revoked = new CountingRequest("POST", "/api/admin/assets", "multipart/form-data; boundary=x", false);
        revoked.setSession(session(AuthPrincipal.Kind.ACCOUNT));
        reject(revoked, 403);
        assertThat(revoked.streams).hasValue(0);
        assertThat(revoked.parameters).hasValue(0);
        var machine = new CountingRequest("POST", "/api/admin/repository/patch", "application/json", false);
        machine.setSession(session(AuthPrincipal.Kind.API_KEY));
        reject(machine, 401);
        assertThat(machine.streams).hasValue(0);
        assertThat(machine.parameters).hasValue(0);
    }

    @Test
    void currentMembershipValidationPrecedesMultipartCsrfParameterAccess() throws Exception {
        var authorized = new AtomicBoolean();
        when(auth.authorize(any(), any())).thenAnswer(invocation -> {
            authorized.set(true);
            return null;
        });
        var request = new CountingRequest("POST", "/api/admin/assets", "multipart/form-data; boundary=x", false) {
            @Override
            public String getParameter(String name) {
                assertThat(authorized).isTrue();
                return super.getParameter(name);
            }
        };
        request.setSession(session(AuthPrincipal.Kind.ACCOUNT));
        reject(request, 403);
        assertThat(authorized).isTrue();
        assertThat(request.parameters).hasValue(1);
    }

    @Test
    void originAndDeclaredOverflowAreRejectedWithoutBodyReads() throws Exception {
        var origin = new CountingRequest("POST", "/api/admin/assets", "application/json", false);
        origin.addHeader("Origin", "https://unexpected.invalid");
        reject(origin, 403);
        assertThat(origin.streams).hasValue(0);
        var overflow = new CountingRequest("POST", "/api/admin/assets", "application/json", true) {
            @Override
            public long getContentLengthLong() {
                return 17 * 1024 * 1024 + 1;
            }
        };
        reject(overflow, 413);
        assertThat(overflow.streams).hasValue(0);
        verifyNoInteractions(auth);
    }

    private void reject(CountingRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        filters.doFilter(request, response, (req, res) -> fail("rejected request reached the application"));
        assertThat(response.getStatus()).isEqualTo(status);
    }

    private static MockHttpSession session(AuthPrincipal.Kind kind) {
        var principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(kind);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }

    private static class CountingRequest extends MockHttpServletRequest {
        final AtomicInteger streams = new AtomicInteger();
        final AtomicInteger parameters = new AtomicInteger();
        final boolean declared;

        CountingRequest(String method, String path, String type, boolean declared) {
            super(method, path);
            this.declared = declared;
            setServletPath(path);
            setContentType(type);
            setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return declared ? super.getContentLength() : -1;
        }

        @Override
        public long getContentLengthLong() {
            return getContentLength();
        }

        @Override
        public ServletInputStream getInputStream() {
            streams.incrementAndGet();
            return super.getInputStream();
        }

        @Override
        public String getParameter(String name) {
            parameters.incrementAndGet();
            return super.getParameter(name);
        }
    }

    @Configuration
    @EnableWebMvc
    @Import(BrowserSecurityConfiguration.class)
    static class SecurityFixture {
        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        WorkspaceCatalog workspaces() {
            var catalog = mock(WorkspaceCatalog.class);
            when(catalog.defaultWorkspace()).thenReturn(new Workspace(WorkspaceId.random(), "Test workspace"));
            return catalog;
        }
    }
}
