package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryOriginalTransfers;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class PortableExportConfiguration {
    @Bean
    LocalPortableContentExports portableContentExports(
            AuthService auth,
            RepositoryContentReader reader,
            RepositoryBlobReader blobs,
            PublicContentSnapshots snapshots,
            RepositoryOriginalTransfers originals,
            @Value("${poketto.data-dir}") Path data,
            @Value("${poketto.exports.max-zip-bytes:838860800}") long zip,
            @Value("${poketto.exports.max-retained-bytes:2147483648}") long retained,
            @Value("${poketto.exports.max-workspace-bytes:1677721600}") long workspace,
            @Value("${poketto.exports.max-packages:8}") int packages,
            @Value("${poketto.exports.lifetime-seconds:600}") long lifetime,
            @Value("${poketto.exports.build-seconds:120}") long build) {
        return new LocalPortableContentExports(
                auth,
                new PortableContentPlanner(auth, reader, blobs, snapshots, originals),
                data.resolve("portable-exports"),
                Clock.systemUTC(),
                new LocalPortableContentExports.Limits(
                        zip, retained, workspace, packages, Duration.ofSeconds(lifetime), Duration.ofSeconds(build)));
    }
}
