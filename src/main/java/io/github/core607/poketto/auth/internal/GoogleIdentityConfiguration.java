package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.EmailAccounts;
import io.github.core607.poketto.auth.GoogleAccounts;
import io.github.core607.poketto.auth.GoogleIdentityProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class GoogleIdentityConfiguration {
    @Bean
    GoogleIdentityProvider googleIdentityProvider(
            @Value("${poketto.google.client-id:}") String id,
            @Value("${poketto.google.client-secret:}") String secret,
            @Value("${poketto.oauth.issuer:}") String origin) {
        return new GoogleOidcProvider(id, secret, origin);
    }

    @Bean
    GoogleAccounts googleAccounts(
            JdbcTemplate jdbc,
            AuthService auth,
            Accounts accounts,
            EmailAccounts emails,
            PlatformTransactionManager manager) {
        return new GoogleAccounts(jdbc, auth, accounts, emails, manager);
    }
}
