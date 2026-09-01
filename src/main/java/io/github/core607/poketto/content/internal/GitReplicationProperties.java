package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.GitAcknowledgementMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("poketto.git")
record GitReplicationProperties(
        GitAcknowledgementMode acknowledgement,
        String remote,
        Duration initialRetry,
        Duration maxRetry,
        Duration networkTimeout) {

    private static final Duration DEFAULT_INITIAL_RETRY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_RETRY = Duration.ofMinutes(1);
    private static final Duration DEFAULT_NETWORK_TIMEOUT = Duration.ofSeconds(10);

    GitReplicationProperties {
        acknowledgement = acknowledgement == null ? GitAcknowledgementMode.LOCAL : acknowledgement;
        remote = remote == null || remote.isBlank() ? "origin" : remote.trim();
        initialRetry = initialRetry == null ? DEFAULT_INITIAL_RETRY : positive(initialRetry, "initial retry");
        maxRetry = maxRetry == null ? DEFAULT_MAX_RETRY : positive(maxRetry, "maximum retry");
        networkTimeout = networkTimeout == null
                ? DEFAULT_NETWORK_TIMEOUT
                : positive(networkTimeout, "network timeout");
        if (maxRetry.compareTo(initialRetry) < 0) {
            throw new IllegalArgumentException(
                    "poketto.git.max-retry must not be shorter than initial-retry");
        }
    }

    private static Duration positive(Duration value, String label) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("poketto.git " + label + " must be positive");
        }
        return value;
    }
}
