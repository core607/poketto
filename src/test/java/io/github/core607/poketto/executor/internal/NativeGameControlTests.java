package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NativeGameControlTests {
    @TempDir
    Path root;

    private final String token = UUID.randomUUID().toString();

    @Test
    void aMatchingAcknowledgementCompletesOnlyItsExactRequest() throws Exception {
        var request = new NativeGameControl.Request(token, 1, "game-assert-clean");
        Files.write(root.resolve("ack-1.json"), SandcrabProtocol.write(request));
        new NativeGameControl(root, token).run("game-assert-clean");
        assertThat(SandcrabProtocol.read(
                        Files.readAllBytes(root.resolve("request-1.json")), NativeGameControl.Request.class))
                .isEqualTo(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "sequence", "action"})
    void anAcknowledgementCannotChangeTheFixtureSequenceOrAction(String changed) throws Exception {
        var wrong = new NativeGameControl.Request(
                changed.equals("token") ? UUID.randomUUID().toString() : token,
                changed.equals("sequence") ? 2 : 1,
                changed.equals("action") ? "game-restart-worker" : "game-assert-clean");
        Files.write(root.resolve("ack-1.json"), SandcrabProtocol.write(wrong));
        assertThatThrownBy(() -> new NativeGameControl(root, token).run("game-assert-clean"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed identity");
    }

    @Test
    void anUnknownOperationNeverPublishesAControllerRequest() {
        assertThatThrownBy(() -> new NativeGameControl(root, token).run("systemctl stop arbitrary.service"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("request-1.json")).doesNotExist();
    }

    @Test
    void completedEvidenceIsNeverReplaced() throws Exception {
        var control = new NativeGameControl(root, token);
        control.complete(new NativeGameControl.Request(token, 1, "game-assert-clean"));
        byte[] original = Files.readAllBytes(root.resolve("result.json"));
        assertThatThrownBy(() -> control.complete(new NativeGameControl.Request(token, 2, "game-restart-worker")))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(root.resolve("result.json"))).containsExactly(original);
    }

    @Test
    void aPreexistingResultWithoutAMarkerIsAlsoPreserved() throws Exception {
        Files.writeString(root.resolve("result.json"), "prior fixture result");
        assertThatThrownBy(() -> new NativeGameControl(root, token)
                        .complete(new NativeGameControl.Request(token, 1, "game-assert-clean")))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(root.resolve("result.json"))).isEqualTo("prior fixture result");
    }
}
