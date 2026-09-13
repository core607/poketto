package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class PublicationRepositories {
    public static final WorkspaceId SECOND = WorkspaceId.random();

    @Bean
    @Primary
    RepositoryBindingSource publicationBindings(@Value("${poketto.test.repository-path}") String first)
            throws Exception {
        var credentials = new UsernamePasswordCredentialsProvider("test", "test");
        var firstBinding =
                new RepositoryBinding(new URIish(Path.of(first).toUri().toString()), credentials);
        var secondBinding = new RepositoryBinding(
                new URIish(Path.of(first).resolveSibling("second.git").toUri().toString()), credentials);
        return workspace -> workspace.equals(SECOND) ? secondBinding : firstBinding;
    }
}
