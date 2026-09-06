package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryPatchService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class RepositoryPatchConfiguration {
    @Bean
    AuthorizedRepositoryReader authorizedRepositoryReader(AuthService auth, RepositoryContentReader reader) {
        return new AuthorizedRepositoryReader(auth, reader);
    }

    @Bean
    RepositoryPatchService repositoryPatchService(
            RepositoryAuthority authority, AuthService auth, JGitPublicContentSnapshots snapshots) {
        return new JGitRepositoryPatchService(
                authority, auth, Clock.systemUTC(), snapshots::installAcknowledged, snapshots::closePublication);
    }
}
