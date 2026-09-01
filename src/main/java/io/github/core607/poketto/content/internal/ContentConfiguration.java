package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ContentProperties.class, RepositoryProperties.class})
class ContentConfiguration {

    @Bean
    WorkspacePaths workspacePaths(ContentProperties properties) {
        return new WorkspacePaths(properties.dataDir());
    }

    @Bean
    CanonicalDocumentCodec canonicalDocumentCodec() {
        return new CanonicalDocumentCodec();
    }

    @Bean
    RepositoryBindingSource repositoryBindingSource(
            RepositoryProperties properties, ObjectProvider<WorkspaceCatalog> workspaces) {
        return new ConfiguredRepositoryBindingSource(properties, workspaces);
    }

    @Bean
    RemoteGitTransport remoteGitTransport(RepositoryProperties properties) {
        return new JGitRemoteGitTransport(properties.timeoutSeconds());
    }

    @Bean
    RepositoryAuthority repositoryAuthority(
            WorkspacePaths paths,
            RepositoryBindingSource bindings,
            RemoteGitTransport transport,
            RepositoryProperties properties) {
        return new JGitRemoteRepositoryAuthority(paths, bindings, transport, properties.cacheMaxWorkspaces());
    }

    @Bean
    JGitContentRepositoryStore contentRepositoryStore(RepositoryAuthority authority, CanonicalDocumentCodec codec) {
        return new JGitContentRepositoryStore(authority, codec);
    }

    @Bean
    DocumentWriteService documentWriteService(
            RepositoryAuthority authority, CanonicalDocumentCodec codec, JGitContentRepositoryStore store) {
        return new JGitDocumentWriteService(authority, codec, store, Clock.systemUTC());
    }

    @Bean
    @Order(100)
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner contentRepositoryInitializer(WorkspaceCatalog workspaces, ContentRepositoryStore repositories) {
        return arguments ->
                repositories.ensureReady(workspaces.defaultWorkspace().id());
    }
}
