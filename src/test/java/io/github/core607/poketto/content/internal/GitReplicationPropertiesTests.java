package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.core607.poketto.content.GitAcknowledgementMode;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GitReplicationPropertiesTests {

    @Test
    void suppliesSafeLocalDefaults() {
        GitReplicationProperties properties =
                new GitReplicationProperties(null, null, null, null, null);

        assertThat(properties.acknowledgement()).isEqualTo(GitAcknowledgementMode.LOCAL);
        assertThat(properties.remote()).isEqualTo("origin");
        assertThat(properties.initialRetry()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.maxRetry()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.networkTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsInvalidRetryWindows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitReplicationProperties(
                        GitAcknowledgementMode.LOCAL,
                        "origin",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5)))
                .withMessageContaining("max-retry");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitReplicationProperties(
                        GitAcknowledgementMode.LOCAL,
                        "origin",
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5)))
                .withMessageContaining("must be positive");
    }
}
