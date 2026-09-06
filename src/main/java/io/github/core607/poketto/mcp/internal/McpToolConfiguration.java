package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"poketto.workspace.catalog.enabled", "spring.ai.mcp.server.enabled"},
        havingValue = "true",
        matchIfMissing = true)
class McpToolConfiguration {
    @Bean
    List<McpServerFeatures.SyncToolSpecification> repositoryMcpTools(
            McpSessions sessions,
            AuthService auth,
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches,
            ObjectProvider<AssetService> assets,
            ObjectProvider<RepositoryExecutor> executors,
            ObjectMapper json) {
        return new RepositoryMcpTools(sessions, auth, reader, patches, assets, executors, json).specifications();
    }
}
