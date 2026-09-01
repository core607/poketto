package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ContentProperties.class, GitReplicationProperties.class})
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
    ContentRepositoryStore contentRepositoryStore(
            WorkspacePaths paths, CanonicalDocumentCodec codec) {
        return new JGitContentRepositoryStore(paths, codec);
    }

    @Bean(destroyMethod = "close")
    GitReplicationCoordinator gitReplicationCoordinator(
            WorkspacePaths paths,
            ContentRepositoryStore store,
            GitReplicationProperties properties) {
        return new GitReplicationCoordinator(paths, store, properties, Clock.systemUTC());
    }

    @Bean
    DocumentWriteService documentWriteService(
            WorkspacePaths paths,
            CanonicalDocumentCodec codec,
            ContentRepositoryStore store,
            GitReplicationCoordinator replication) {
        return new JGitDocumentWriteService(
                paths,
                codec,
                store,
                replication,
                replication.repositoryLocks(),
                Clock.systemUTC());
    }

    @Bean
    @Order(100)
    @ConditionalOnProperty(
            name = "poketto.workspace.catalog.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ApplicationRunner contentRepositoryInitializer(
            WorkspaceCatalog workspaces, ContentRepositoryStore repositories) {
        return arguments -> repositories.ensureReady(workspaces.defaultWorkspace().id());
    }

    @Bean
    @Order(110)
    @ConditionalOnProperty(
            name = "poketto.workspace.catalog.enabled",
            havingValue = "true",
            matchIfMissing = true)
    ApplicationRunner gitReplicationInitializer(
            WorkspaceCatalog workspaces, GitReplicationCoordinator replication) {
        return arguments -> replication.start(workspaces.defaultWorkspace().id());
    }
}
