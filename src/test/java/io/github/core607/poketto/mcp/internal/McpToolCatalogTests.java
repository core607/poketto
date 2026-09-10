package io.github.core607.poketto.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.mcp.RepositoryExecutor;
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
                        new ObjectMapper())
                .specifications();
        assertThat(tools)
                .extracting(specification -> specification.tool().name())
                .containsExactlyInAnyOrder("repo_exec", "get_artifact", "get_asset", "put_asset");
    }

    @Test
    void unavailableExecutorDoesNotRestoreStandaloneFileCrud() {
        var beans = new StaticListableBeanFactory(Map.of("assets", mock(AssetService.class)));
        var tools = new RepositoryMcpTools(
                        null,
                        null,
                        beans.getBeanProvider(AssetService.class),
                        beans.getBeanProvider(RepositoryExecutor.class),
                        new ObjectMapper())
                .specifications();
        assertThat(tools)
                .extracting(specification -> specification.tool().name())
                .containsExactlyInAnyOrder("get_asset", "put_asset");
    }
}
