package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.executor.enabled", havingValue = "true")
class RepositorySnapshotExportConfiguration {
    @Bean
    RepositorySnapshotExports repositorySnapshotExports(
            RepositoryAuthority authority,
            AuthService auth,
            @Value("${poketto.executor.staging-directory}") Path staging,
            @Value("${poketto.executor.max-bundle-bytes:536870912}") long bytes,
            @Value("${poketto.executor.export-timeout-seconds:30}") long seconds) {
        return new JGitRepositorySnapshotExports(authority, auth, staging, bytes, Duration.ofSeconds(seconds));
    }
}
