package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContentPropertiesTests {

    @Test
    void requiresAnExplicitAbsoluteDataDirectory() {
        Path absolute =
                Path.of(System.getProperty("java.io.tmpdir"), "poketto-data").toAbsolutePath();

        assertThat(new ContentProperties(absolute).dataDir()).isEqualTo(absolute.normalize());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentProperties(null))
                .withMessageContaining("must be configured as an absolute path");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ContentProperties(Path.of("relative-data")))
                .withMessageContaining("must be absolute");
    }

    @Test
    void repositoryConfigurationFailsClosedAndRedactsSecrets() {
        RepositoryProperties missing = new RepositoryProperties(null, null, null, null, null, null, null);
        RepositoryProperties configured = new RepositoryProperties(
                "https://git.example.invalid/private.git", "operator", "secret-token", 4, 30, null, null);

        assertThatThrownBy(missing::requiredBinding).hasMessageContaining("requires poketto.repository.remote-uri");
        assertThat(configured.requiredBinding().toString()).isEqualTo("RepositoryBinding[redacted]");
        assertThat(configured.toString()).doesNotContain("git.example.invalid", "operator", "secret-token");
    }

    @Test
    void rejectsCredentialsInTheAddressAndFileTransportOutsideTests() {
        RepositoryProperties embedded = new RepositoryProperties(
                "https://user:token@git.example.invalid/private.git", "operator", "secret-token", 4, 30, null, null);
        RepositoryProperties file = new RepositoryProperties(
                Path.of(System.getProperty("java.io.tmpdir"), "remote.git")
                        .toUri()
                        .toString(),
                "test",
                "test",
                4,
                30,
                null,
                null);

        assertThatThrownBy(embedded::requiredBinding)
                .hasMessageNotContaining("user")
                .hasMessageNotContaining("token")
                .hasMessageNotContaining("git.example.invalid");
        assertThatThrownBy(file::requiredBinding).hasMessageContaining("HTTPS URI");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryProperties(
                        "https://git.example.invalid/private.git", "operator", "secret-token", 4, 0, null, null))
                .withMessageContaining("timeout-seconds must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryProperties(
                        "https://git.example.invalid/private.git", "operator", "secret-token", 4, 30, 0, null))
                .withMessageContaining("refresh-seconds must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RepositoryProperties(
                        "https://git.example.invalid/private.git", "operator", "secret-token", 4, 30, 60, 30))
                .withMessageContaining("stale-after-seconds must not be shorter than refresh-seconds");
        RepositoryProperties defaults = new RepositoryProperties(null, null, null, null, null, null, null);
        assertThat(defaults.refreshSeconds()).isEqualTo(30);
        assertThat(defaults.staleAfterSeconds()).isEqualTo(3600);
    }
}
