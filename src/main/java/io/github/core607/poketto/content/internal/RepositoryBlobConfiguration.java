package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.PublicRevisionHistory;
import io.github.core607.poketto.content.RepositoryBlobReader;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RepositoryBlobConfiguration {
    @Bean
    RepositoryBlobReader repositoryBlobReader(RepositoryAuthority authority) {
        return new JGitRepositoryBlobReader(authority);
    }

    @Bean
    PublicRevisionHistory publicRevisionHistory(RepositoryAuthority authority) {
        return new JGitPublicRevisionHistory(authority, Clock.systemUTC());
    }
}
