package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthService;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    AuthService authService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events,
            @Value("${poketto.auth.initialization-token:}") String initializationToken) {
        return new AuthService(
                jdbc, transactionManager, passwordEncoder, events, Clock.systemUTC(), initializationToken);
    }
}
