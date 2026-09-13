package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Local Git authorities used only by the isolated browser fixture. */
@TestConfiguration(proxyBeanMethods = false)
public class AcceptanceRepositories {
    private static final Map<WorkspaceId, Path> selected = new ConcurrentHashMap<>();

    public static void register(WorkspaceId workspace, Path remote) {
        selected.put(workspace, remote);
    }

    @Bean
    @Primary
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "poketto.acceptance.managed-connections",
            havingValue = "true")
    io.github.core607.poketto.content.RepositoryConnections acceptanceManagedConnections(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Value("${poketto.repository.credential-key}") String key) {
        return new AcceptanceManagedConnections(jdbc, key);
    }

    @Bean
    @Primary
    RepositoryBindingSource acceptanceRepositorySource(@Value("${poketto.test.repository-path}") String defaultRemote) {
        return workspace -> {
            try {
                return new RepositoryBinding(
                        new URIish(selected.getOrDefault(workspace, Path.of(defaultRemote))
                                .toUri()
                                .toString()),
                        new UsernamePasswordCredentialsProvider("fixture", "fixture"));
            } catch (URISyntaxException exception) {
                throw new IllegalStateException(exception);
            }
        };
    }
}
