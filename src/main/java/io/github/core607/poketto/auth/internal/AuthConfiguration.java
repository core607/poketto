package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.SitePolicyService;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class AuthConfiguration {
    @Bean
    SitePolicyService sitePolicyService(
            JdbcTemplate jdbc, Accounts accounts, AuthService auth, PlatformTransactionManager transactions) {
        return new SitePolicyService(jdbc, accounts, auth, transactions);
    }

    @Bean
    Accounts accounts(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new Accounts(jdbc, transactionManager);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    AuthService authService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events) {
        return new AuthService(jdbc, transactionManager, passwordEncoder, events, Clock.systemUTC());
    }
}
