package io.github.core607.poketto.mcp.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"poketto.workspace.catalog.enabled", "spring.ai.mcp.server.enabled"},
        havingValue = "true",
        matchIfMissing = true)
class McpTransportConfiguration {
    @Bean
    McpSessions mcpSessions(
            AuthService auth,
            WorkspaceCatalog workspaces,
            ApplicationEventPublisher events,
            @Value("${poketto.mcp.session-idle-seconds:1800}") long idleSeconds,
            @Value("${poketto.mcp.max-sessions:128}") int maxSessions) {
        return new McpSessions(
                auth, workspaces, events, Clock.systemUTC(), Duration.ofSeconds(idleSeconds), maxSessions);
    }

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper,
            McpSessions sessions,
            @Value("${poketto.mcp.session-idle-seconds:1800}") long idleSeconds,
            @Value("${poketto.mcp.max-sessions:128}") int maxSessions) {
        return WebMvcStreamableServerTransportProvider.builder()
                // Map nulls are protocol values: repo_patch distinguishes deletion from a missing field.
                .jsonMapper(new JacksonMcpJsonMapper(mapper.rebuild()
                        .changeDefaultPropertyInclusion(
                                inclusion -> inclusion.withContentInclusion(JsonInclude.Include.ALWAYS))
                        .build()))
                .mcpEndpoint("/mcp")
                .maxSessions(maxSessions)
                .sessionIdleTimeout(Duration.ofSeconds(idleSeconds))
                .contextExtractor(request -> {
                    var context = new java.util.HashMap<String, Object>();
                    context.put(McpSessions.IDENTITY_CONTEXT, sessions.currentIdentity());
                    Object scope = request.servletRequest().getAttribute(ImageRequestScope.ATTRIBUTE);
                    if (scope instanceof ImageRequestScope) context.put(ImageRequestScope.ATTRIBUTE, scope);
                    return McpTransportContext.create(context);
                })
                .build();
    }

    @Bean
    @Primary
    McpStreamableServerTransportProvider boundMcpTransport(
            WebMvcStreamableServerTransportProvider delegate, McpSessions sessions) {
        return new BoundMcpTransport(delegate, sessions);
    }

    @Bean(name = "webMvcStreamableServerRouterFunction")
    RouterFunction<ServerResponse> mcpRouter(WebMvcStreamableServerTransportProvider delegate, McpSessions sessions) {
        return delegate.getRouterFunction().filter((request, next) -> {
            var headers = request.headers().header("Mcp-Session-Id");
            if (headers.size() > 1) return rejected(400);
            String sessionId = headers.isEmpty() ? null : headers.getFirst();
            try {
                var identity = sessions.currentIdentity();
                if (sessionId != null) {
                    if (!sessionId.matches("[A-Za-z0-9._:-]{1,128}")) return rejected(404);
                    sessions.check(sessionId, identity);
                }
                ServerResponse response = next.handle(request);
                if (request.method().name().equals("DELETE")
                        && sessionId != null
                        && response.statusCode().is2xxSuccessful()) {
                    sessions.remove(sessionId, McpSessionClosed.Reason.CLIENT_DELETE);
                }
                return normalizeError(response);
            } catch (AuthException | SecurityException exception) {
                return rejected(404);
            }
        });
    }

    static ServerResponse normalizeError(ServerResponse response) {
        if (response instanceof EntityResponse<?> entity && entity.entity() instanceof McpError error) {
            // SDK transport errors have no request ID. Never serialize the Throwable envelope.
            return ServerResponse.from(response).body(Map.of("jsonrpc", "2.0", "error", error.getJsonRpcError()));
        }
        return response;
    }

    @Bean
    FilterRegistrationBean<McpBodyLimitFilter> mcpBodyLimitFilter(
            tools.jackson.databind.ObjectMapper json, ImageMemoryAdmission memory) {
        var registration = new FilterRegistrationBean<>(new McpBodyLimitFilter(json, memory));
        registration.setUrlPatterns(java.util.List.of("/mcp"));
        registration.setOrder(-99);
        registration.setAsyncSupported(true);
        return registration;
    }

    private static ServerResponse rejected(int status) {
        return ServerResponse.status(status)
                .header("Cache-Control", "no-store")
                .body(Map.of("type", "about:blank", "title", "MCP session unavailable", "status", status));
    }
}
