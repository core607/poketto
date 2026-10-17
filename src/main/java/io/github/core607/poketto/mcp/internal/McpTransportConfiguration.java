package io.github.core607.poketto.mcp.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.auth.AuthService;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"poketto.workspace.catalog.enabled", "spring.ai.mcp.server.enabled"},
        havingValue = "true",
        matchIfMissing = true)
class McpTransportConfiguration {
    @Bean
    McpIdentities mcpIdentities(AuthService auth) {
        return new McpIdentities(auth);
    }

    @Bean
    McpCalls mcpCalls() {
        return new McpCalls();
    }

    /** Replaces the SDK's stateless transport, whose bean remains unused; the stateless server takes this one. */
    @Bean
    @Primary
    StatelessMcpTransport statelessMcpTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper mapper,
            McpIdentities identities,
            McpCalls calls,
            ImageMemoryAdmission memory,
            ObjectMapper json) {
        return new StatelessMcpTransport(
                // Preserve explicit null protocol fields, including optional repository source commits.
                new JacksonMcpJsonMapper(mapper.rebuild()
                        .changeDefaultPropertyInclusion(
                                inclusion -> inclusion.withContentInclusion(JsonInclude.Include.ALWAYS))
                        .build()),
                identities,
                calls,
                memory,
                json);
    }

    /** Takes the SDK router's bean name, so only this transport serves /mcp. */
    @Bean(name = "webMvcStatelessServerRouterFunction")
    RouterFunction<ServerResponse> mcpRouter(StatelessMcpTransport transport) {
        return transport.routerFunction();
    }

    @Bean
    FilterRegistrationBean<McpBodyLimitFilter> mcpBodyLimitFilter(ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new McpBodyLimitFilter(json));
        registration.setUrlPatterns(List.of("/mcp"));
        registration.setOrder(-99);
        return registration;
    }
}
