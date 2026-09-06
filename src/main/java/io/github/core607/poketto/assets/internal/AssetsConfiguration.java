package io.github.core607.poketto.assets.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryMarkdownInspector;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class AssetsConfiguration {
    @Bean
    AssetService assetService(
            AuthService auth,
            RepositoryContentReader content,
            RepositoryBlobReader blobs,
            RepositoryMarkdownInspector markdown,
            PublicContentSnapshots snapshots,
            @Value("${poketto.data-dir}") Path directory,
            @Value("${poketto.assets.cache-max-bytes:134217728}") long cacheBytes,
            @Value("${poketto.assets.max-grants:2048}") int maxGrants) {
        // Constructing ordinary application services must not require unsupported Windows directory fsync.
        Supplier<ManagedBlobStore> managed = new Supplier<>() {
            private ManagedBlobStore initialized;

            @Override
            public synchronized ManagedBlobStore get() {
                if (initialized == null) initialized = ManagedBlobStore.local(directory.resolve("managed-originals"));
                return initialized;
            }
        };
        return new AssetService(
                auth,
                content,
                blobs,
                markdown,
                snapshots,
                managed,
                directory.resolve("derived/repository-images"),
                cacheBytes,
                maxGrants,
                Clock.systemUTC());
    }
}
