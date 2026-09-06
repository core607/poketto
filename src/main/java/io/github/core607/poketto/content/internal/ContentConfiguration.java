package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.DocumentWriteService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePaths;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
        return new JGitRemoteRepositoryAuthority(
                paths, bindings, transport, properties.cacheMaxWorkspaces(), Clock.systemUTC());
    }

    @Bean
    JGitContentRepositoryStore contentRepositoryStore(RepositoryAuthority authority, CanonicalDocumentCodec codec) {
        return new JGitContentRepositoryStore(authority, codec, Clock.systemUTC());
    }

    @Bean
    RepositoryContentReader repositoryContentReader(RepositoryAuthority authority) {
        return new JGitRepositoryContentReader(authority);
    }

    @Bean
    JGitPublicContentSnapshots publicContentSnapshots(RepositoryAuthority authority, RepositoryProperties properties) {
        return new JGitPublicContentSnapshots(
                authority, Clock.systemUTC(), Duration.ofSeconds(properties.staleAfterSeconds()));
    }

    @Bean
    DocumentWriteService documentWriteService(
            RepositoryAuthority authority, CanonicalDocumentCodec codec, JGitContentRepositoryStore store) {
        return new JGitDocumentWriteService(authority, codec, store, Clock.systemUTC());
    }

    /**
     * The default workspace is created once by the database migration and never changes, so the
     * refresher and the health probe resolve it once instead of querying on every call.
     */
    @Bean
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    Supplier<WorkspaceId> defaultWorkspaceId(WorkspaceCatalog workspaces) {
        AtomicReference<WorkspaceId> resolved = new AtomicReference<>();
        return () -> {
            WorkspaceId id = resolved.get();
            if (id == null) {
                id = workspaces.defaultWorkspace().id();
                resolved.compareAndSet(null, id);
            }
            return id;
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    ContentSnapshotRefresher contentSnapshotRefresher(
            PublicContentSnapshots store, Supplier<WorkspaceId> defaultWorkspaceId, RepositoryProperties properties) {
        return new ContentSnapshotRefresher(
                store::refresh,
                () -> List.of(defaultWorkspaceId.get()),
                Duration.ofSeconds(properties.refreshSeconds()));
    }

    @Bean(name = "contentSnapshot")
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    PublicSnapshotHealthIndicator contentSnapshotHealthIndicator(
            PublicContentSnapshots store, Supplier<WorkspaceId> defaultWorkspaceId) {
        return new PublicSnapshotHealthIndicator(store, defaultWorkspaceId);
    }

    /**
     * Public readiness stays unavailable when initialization fails. The application and refresher
     * still start so authenticated administration can diagnose and repair repository configuration.
     */
    @Bean
    @Order(100)
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner contentRepositoryInitializer(
            Supplier<WorkspaceId> defaultWorkspaceId,
            PublicContentSnapshots repositories,
            ContentSnapshotRefresher refresher) {
        return arguments -> {
            try {
                repositories.ensureReady(defaultWorkspaceId.get());
            } catch (io.github.core607.poketto.content.ContentRepositoryException unavailable) {
                org.slf4j.LoggerFactory.getLogger(ContentConfiguration.class)
                        .warn("public content initialization is unavailable; background refresh will retry");
            }
            refresher.start();
        };
    }
}
