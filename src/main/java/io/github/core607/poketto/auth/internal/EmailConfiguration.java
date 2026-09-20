package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.EmailChallenges;
import io.github.core607.poketto.auth.VerificationMail;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class EmailConfiguration {
    @Bean
    ResendVerificationMail verificationMail(
            @Value("${poketto.email.api-key:}") String credential, @Value("${poketto.email.from:}") String from) {
        return new ResendVerificationMail(credential, from);
    }

    @Bean
    EmailChallenges emailChallenges(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            VerificationMail mail,
            @Value("${poketto.email.api-key:}") String credential,
            @Value("${poketto.email.daily-limit:100}") int dailyLimit) {
        return new EmailChallenges(jdbc, manager, mail, credential, dailyLimit, Clock.systemUTC());
    }
}
