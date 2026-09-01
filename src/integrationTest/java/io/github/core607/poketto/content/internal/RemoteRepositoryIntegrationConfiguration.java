package io.github.core607.poketto.content.internal;

import java.nio.file.Path;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class RemoteRepositoryIntegrationConfiguration {

    @Bean
    @Primary
    RepositoryBindingSource integrationRepositoryBindingSource(
            @Value("${poketto.test.repository-path}") String remotePath) throws Exception {
        RepositoryBinding binding = new RepositoryBinding(
                new URIish(Path.of(remotePath).toUri().toString()),
                new UsernamePasswordCredentialsProvider("test", "test"));
        return workspaceId -> binding;
    }
}
