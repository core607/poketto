package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.RepositoryBlobReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RepositoryBlobConfiguration {
    @Bean
    RepositoryBlobReader repositoryBlobReader(RepositoryAuthority authority) {
        return new JGitRepositoryBlobReader(authority);
    }
}
