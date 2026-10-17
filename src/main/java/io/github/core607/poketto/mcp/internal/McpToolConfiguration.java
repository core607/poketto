package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ImageTransfers;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.plaza.PlazaService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import java.util.ArrayList;
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
    List<McpStatelessServerFeatures.SyncToolSpecification> repositoryMcpTools(
            McpIdentities identities,
            AuthService auth,
            ObjectProvider<AssetService> assets,
            ObjectProvider<RepositoryExecutor> executors,
            ObjectMapper json,
            ObjectProvider<ImageTransfers> transfers,
            ObjectProvider<PlazaService> plaza) {
        var tools = new ArrayList<>(
                new RepositoryMcpTools(identities, auth, assets, executors, json, transfers).specifications());
        if (plaza.getIfAvailable() != null) {
            tools.add(new PlazaMcpTool(identities, plaza.getObject(), json).specification());
        }
        return List.copyOf(tools);
    }
}
