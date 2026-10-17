package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class McpToolCatalogTests {
    @Test
    void enabledExecutorSuppliesTheFileEntranceAlongsideMediaAndArtifacts() {
        var beans = new StaticListableBeanFactory(
                Map.of("assets", mock(AssetService.class), "executor", mock(RepositoryExecutor.class)));
        var tools = new RepositoryMcpTools(
                        null,
                        null,
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        new ObjectMapper(),
                        null)
                .specifications();
        assertThat(tools)
                .extracting(specification -> specification.tool().name())
                .containsExactlyInAnyOrder("repo_exec", "repo_discard", "get_artifact", "get_asset", "put_asset");
    }

    @Test
    void repoExecStaysBriefAndDescribesEveryParameter() {
        var beans = new StaticListableBeanFactory(
                Map.of("assets", mock(AssetService.class), "executor", mock(RepositoryExecutor.class)));
        var tool = new RepositoryMcpTools(
                        null,
                        null,
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        new ObjectMapper(),
                        null)
                .specifications().stream()
                        .map(McpServerFeatures.SyncToolSpecification::tool)
                        .filter(candidate -> candidate.name().equals("repo_exec"))
                        .findFirst()
                        .orElseThrow();
        // Every client sends this text to its model on each turn; growth costs every agent's context.
        assertThat(tool.description()).hasSizeLessThan(1000);
        var properties = (Map<?, ?>) tool.inputSchema().get("properties");
        for (Object property : properties.values()) {
            assertThat(((Map<?, ?>) property).containsKey("description")).isTrue();
        }
    }

    @Test
    void unavailableExecutorDoesNotRestoreStandaloneFileCrud() {
        var beans = new StaticListableBeanFactory(Map.of("assets", mock(AssetService.class)));
        var tools = new RepositoryMcpTools(
                        null,
                        null,
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        new ObjectMapper(),
                        null)
                .specifications();
        assertThat(tools)
                .extracting(specification -> specification.tool().name())
                .containsExactlyInAnyOrder("get_asset", "put_asset");
    }
}
