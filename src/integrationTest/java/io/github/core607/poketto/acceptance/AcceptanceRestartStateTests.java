package io.github.core607.poketto.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptanceRestartStateTests {
    @TempDir
    Path root;

    @Test
    void ordinaryAcceptanceStillRefusesAnyExistingData() throws Exception {
        assertThat(AcceptanceRestartState.reopen(root, null)).isFalse();
        Files.writeString(root.resolve("existing"), "preserve");
        assertThatThrownBy(() -> AcceptanceRestartState.reopen(root, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(root.resolve("existing"))).isEqualTo("preserve");
    }

    @Test
    void matchingExplicitIdentityReopensWithoutChangingExistingBytes() throws Exception {
        String identity = "a".repeat(32);
        assertThat(AcceptanceRestartState.reopen(root, identity)).isFalse();
        seed();
        AcceptanceRestartState.ready(root, identity, false);
        assertThat(AcceptanceRestartState.reopen(root, identity)).isTrue();
        AcceptanceRestartState.ready(root, identity, true);
        assertThat(Files.readString(root.resolve("remote.git/HEAD"))).isEqualTo("ref: refs/heads/main\n");
        assertThat(Files.readString(root.resolve("data/retained"))).isEqualTo("application-owned bytes");
    }

    @Test
    void missingOrDifferentIdentityAndIncompleteInitializationNeverReseed() throws Exception {
        seed();
        assertThatThrownBy(() -> AcceptanceRestartState.reopen(root, "a".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
        AcceptanceRestartState.ready(root, "a".repeat(32), false);
        assertThatThrownBy(() -> AcceptanceRestartState.reopen(root, "b".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AcceptanceRestartState.reopen(root, null))
                .isInstanceOf(IllegalArgumentException.class);
        Files.delete(root.resolve("remote.git/HEAD"));
        assertThatThrownBy(() -> AcceptanceRestartState.reopen(root, "a".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(root.resolve("data/retained"))).isEqualTo("application-owned bytes");
    }

    private void seed() throws Exception {
        Files.createDirectories(root.resolve("remote.git/objects"));
        Files.writeString(root.resolve("remote.git/HEAD"), "ref: refs/heads/main\n");
        Files.createDirectory(root.resolve("data"));
        Files.writeString(root.resolve("data/retained"), "application-owned bytes");
    }
}
