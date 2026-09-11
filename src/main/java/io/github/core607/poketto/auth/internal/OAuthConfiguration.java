package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.OAuthService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.oauth.issuer")
class OAuthConfiguration {
    @Bean
    OAuthService oauthService(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            AuthService auth,
            @Value("${poketto.oauth.issuer}") String issuer) {
        return new OAuthService(jdbc, manager, auth, Clock.systemUTC(), issuer);
    }
}
