package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentWriteService;
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
        return new JGitRemoteRepositoryAuthority(paths, bindings, transport, properties.cacheMaxWorkspaces());
    }

    @Bean
    JGitContentRepositoryStore contentRepositoryStore(RepositoryAuthority authority, CanonicalDocumentCodec codec) {
        return new JGitContentRepositoryStore(authority, codec, Clock.systemUTC());
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
            ContentRepositoryStore store, Supplier<WorkspaceId> defaultWorkspaceId, RepositoryProperties properties) {
        return new ContentSnapshotRefresher(
                store, () -> List.of(defaultWorkspaceId.get()), Duration.ofSeconds(properties.refreshSeconds()));
    }

    @Bean(name = "contentSnapshot")
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    ContentSnapshotHealthIndicator contentSnapshotHealthIndicator(
            ContentRepositoryStore store, Supplier<WorkspaceId> defaultWorkspaceId, RepositoryProperties properties) {
        return new ContentSnapshotHealthIndicator(
                store, defaultWorkspaceId, Duration.ofSeconds(properties.staleAfterSeconds()), Clock.systemUTC());
    }

    /**
     * Startup fails without a validated snapshot for the default workspace, so a deployment
     * never reports healthy while its content cannot be served.
     */
    @Bean
    @Order(100)
    @ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner contentRepositoryInitializer(
            Supplier<WorkspaceId> defaultWorkspaceId,
            ContentRepositoryStore repositories,
            ContentSnapshotRefresher refresher) {
        return arguments -> {
            repositories.ensureReady(defaultWorkspaceId.get());
            refresher.start();
        };
    }
}
