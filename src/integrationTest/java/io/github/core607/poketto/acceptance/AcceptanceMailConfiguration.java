package io.github.core607.poketto.acceptance;

import io.github.core607.poketto.auth.EmailPurpose;
import io.github.core607.poketto.auth.VerificationMail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Disposable outbox for real browser identity flows; absent from the production application. */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.acceptance.email", havingValue = "true")
class AcceptanceMailConfiguration {
    @Bean
    @Primary
    VerificationMail acceptanceMail(@Value("${POKETTO_ACCEPTANCE_ROOT}") Path root) {
        return new VerificationMail() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void send(String email, String code, EmailPurpose purpose, UUID messageId) {
                if (!email.endsWith("@example.test")) {
                    throw new IllegalArgumentException("Synthetic outbox accepts only example.test recipients");
                }
                try {
                    Path directory = Files.createDirectories(root.resolve("outbox"));
                    Files.writeString(
                            directory.resolve(messageId + ".txt"), email + "\n" + purpose + "\n" + code + "\n");
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot record synthetic mail delivery", failure);
                }
            }
        };
    }
}
