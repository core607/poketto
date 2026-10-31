package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SandcrabProbeControlTests {
    @TempDir
    Path directory;

    @Test
    void anActualInterruptedObserverCanLogWarningsBeforePublishingItsIndependentResult(CapturedOutput output)
            throws Exception {
        var reference = new SandcrabProtocol.Reference(
                "9ca35360-0845-4fe3-88ef-d3e68ec8a222", "775437cd-82d5-4f00-a99d-a5922b287d2e");
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            Channels.newOutputStream(channel)
                    .write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
        })) {
            var events = new SandcrabEvents(peer.client(channel -> {}));
            assertThatThrownBy(() -> events.observe(reference, null, Duration.ofSeconds(3), ignored -> {}))
                    .isInstanceOf(WorkerUnavailableException.class)
                    .hasRootCauseMessage("incomplete worker response");
        }
        assertThat(output.getOut()).contains("WARN", "Sandbox exchange failed during response-body");
        var summary = new SandcrabRecoveryNativeProbe.Summary("PASS", List.of("observed-interruption"), List.of());
        new SandcrabProbeControl(directory).complete(SandcrabProtocol.write(summary));

        assertThat(SandcrabProtocol.read(
                        Files.readAllBytes(directory.resolve("result.json")),
                        SandcrabRecoveryNativeProbe.Summary.class))
                .isEqualTo(summary);
        assertThat(directory.resolve("result.pending")).doesNotExist();
        assertThat(directory.resolve("result.started")).exists();
    }

    @Test
    void aPublishedResultCannotBeOverwrittenByAnotherPublisher() throws Exception {
        byte[] first = "{\"result\":\"FAIL\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("result.json"), first);

        assertThatThrownBy(() -> new SandcrabProbeControl(directory).complete(new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fixture result already exists");
        assertThat(Files.readAllBytes(directory.resolve("result.json"))).isEqualTo(first);
        assertThat(directory.resolve("result.started")).doesNotExist();
    }

    @Test
    void anInterruptedPublicationCannotBeSilentlyRetried() throws Exception {
        Files.write(directory.resolve("result.started"), new byte[0]);
        Files.writeString(directory.resolve("result.pending"), "original incomplete receipt");

        assertThatThrownBy(() -> new SandcrabProbeControl(directory).complete(new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not publish the fixture result");
        assertThat(Files.readString(directory.resolve("result.pending"))).isEqualTo("original incomplete receipt");
        assertThat(directory.resolve("result.json")).doesNotExist();
    }

    @Test
    void anOversizedResultFailsBeforeStartingPublication() {
        assertThatThrownBy(() -> new SandcrabProbeControl(directory).complete(new byte[1024 * 1024 + 1]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fixture summary exceeded its bound");
        assertThat(directory.resolve("result.started")).doesNotExist();
    }
}
