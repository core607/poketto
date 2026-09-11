package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the serialized form of every worker request frame into a fixture that
 * {@code executor-service/test_java_frames.py} replays through the real worker, so a field renamed
 * on this side cannot pass review by agreeing with itself.
 *
 * <p>Run with {@code -Dpoketto.frames.update=true} to rewrite the fixture after deliberately
 * changing a frame; the worker must accept the new shape in the same change.
 */
class WorkerFrameContractTests {

    private static final String FIXTURE = "executor-service/java-frames.json";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final UUID EXPORT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String EXECUTION = "22222222-2222-4222-8222-222222222222";
    private static final String CAPTURE = "33333333-3333-4333-8333-333333333333";
    private static final String TRANSFER = "44444444-4444-4444-8444-444444444444";
    private static final String ARTIFACT = "55555555-5555-4555-8555-555555555555";
    private static final String BRIDGE_REQUEST = "66666666-6666-4666-8666-666666666666";
    private static final String COMMIT = "c".repeat(40);
    private static final String DIGEST = "d".repeat(64);

    /** One frame per protocol operation, in the order the worker reference lists them. */
    private static Map<String, WorkerRequests.Data> frames() {
        var frames = new LinkedHashMap<String, WorkerRequests.Data>();
        frames.put("OPEN", new WorkerRequests.Open(EXPORT, DIGEST, 4096, COMMIT));
        frames.put("EXEC", new WorkerRequests.Exec(EXECUTION, COMMIT, "git log", 1000));
        frames.put("RENEW", new WorkerRequests.Renew());
        frames.put("CLOSE", new WorkerRequests.Close("session_closed"));
        frames.put("REVOKE", new WorkerRequests.Revoke(Set.of(EXPORT), Set.of(EXPORT)));
        frames.put("BRIDGE_POLL", new WorkerRequests.BridgePoll());
        frames.put("BRIDGE_COMPLETE", new WorkerRequests.BridgeComplete(EXECUTION, BRIDGE_REQUEST, Map.of("ok", true)));
        frames.put("CAPTURE_BEGIN", new WorkerRequests.CaptureBegin(EXECUTION, List.of("notes/a.md"), List.of()));
        frames.put("CAPTURE_OPTIONAL", new WorkerRequests.CapturePath(EXECUTION, "notes/a.md"));
        frames.put("CAPTURE_BINARY", new WorkerRequests.CapturePath(EXECUTION, "notes/a.png"));
        frames.put("CAPTURE_READ", new WorkerRequests.CaptureRead(EXECUTION, CAPTURE, 0, 0, 65536));
        frames.put("CAPTURE_RELEASE", new WorkerRequests.CaptureRelease(EXECUTION, CAPTURE));
        frames.put(
                "MATERIALIZE_BEGIN",
                new WorkerRequests.MaterializeBegin(EXECUTION, "notes/a.md", 6, DIGEST, null, false, true));
        frames.put("MATERIALIZE_CHUNK", new WorkerRequests.TransferChunk(EXECUTION, TRANSFER, 0, "YWJj"));
        frames.put("MATERIALIZE_COMMIT", new WorkerRequests.Transfer(EXECUTION, TRANSFER));
        frames.put("MATERIALIZE_ABORT", new WorkerRequests.Transfer(EXECUTION, TRANSFER));
        frames.put("MOVE_BEGIN", new WorkerRequests.MoveBegin(EXECUTION, 64, DIGEST));
        frames.put("MOVE_CHUNK", new WorkerRequests.TransferChunk(EXECUTION, TRANSFER, 0, "YWJj"));
        frames.put("MOVE_CHECK", new WorkerRequests.Transfer(EXECUTION, TRANSFER));
        frames.put("MOVE_COMMIT", new WorkerRequests.Transfer(EXECUTION, TRANSFER));
        frames.put("MOVE_ABORT", new WorkerRequests.Transfer(EXECUTION, TRANSFER));
        frames.put("ARTIFACT_CREATE", new WorkerRequests.ArtifactCreate(EXECUTION, "out.zip", "application/zip"));
        frames.put("ARTIFACT_READ", new WorkerRequests.ArtifactRead(ARTIFACT, 0, 4096));
        frames.put("ARTIFACT_REMOVE", new WorkerRequests.ArtifactRemove(ARTIFACT));
        return frames;
    }

    @Test
    void everyFrameMatchesTheFixtureTheWorkerReplays() throws Exception {
        // The fixture crosses to a Linux worker, so its separator is fixed here, not inherited.
        String rendered = JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(frames())
                        .replace("\r\n", "\n")
                + "\n";
        Path fixture = repositoryRoot().resolve(FIXTURE);
        if (Boolean.getBoolean("poketto.frames.update")) {
            Files.writeString(fixture, rendered, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(fixture, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .describedAs("run with -Dpoketto.frames.update=true after changing a frame, "
                        + "and make the worker accept the new shape in the same change")
                .isEqualTo(rendered);
    }

    /**
     * The worker reads an absent {@code expectedSha256} as "no precondition" but requires the key,
     * so a null must survive serialization. A global non-null inclusion would silently drop it.
     */
    @Test
    void materializeKeepsAnAbsentPreconditionAsAnExplicitNull() {
        var frame = new WorkerRequests.MaterializeBegin(EXECUTION, "notes/a.md", 6, DIGEST, null, false, true);
        assertThat(JSON.writeValueAsString(frame)).contains("\"expectedSha256\":null");
    }

    @Test
    void constructionRejectsEachViolatedRuleAndNamesItsField() {
        assertThatThrownBy(() -> new WorkerRequests.Open(EXPORT, "short", 1, COMMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bundleSha256");
        assertThatThrownBy(() -> new WorkerRequests.Open(EXPORT, DIGEST, 0, COMMIT))
                .hasMessageContaining("bundleBytes");
        assertThatThrownBy(() -> new WorkerRequests.Exec(EXECUTION, COMMIT, "a\0b", 1))
                .hasMessageContaining("command");
        assertThatThrownBy(() -> new WorkerRequests.Exec(EXECUTION, COMMIT, "x".repeat(65537), 1))
                .hasMessageContaining("command");
        assertThatThrownBy(() -> new WorkerRequests.Exec("not-a-uuid", COMMIT, "x", 1))
                .hasMessageContaining("executionId");
        assertThatThrownBy(() -> new WorkerRequests.Close("elsewhere")).hasMessageContaining("reason");
        assertThatThrownBy(() -> new WorkerRequests.CaptureRead(EXECUTION, CAPTURE, 0, 0, 65537))
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> new WorkerRequests.MoveBegin(EXECUTION, 0, DIGEST))
                .hasMessageContaining("bytes");
        assertThatThrownBy(() -> new WorkerRequests.ArtifactRead("not-a-uuid", 0, 1))
                .hasMessageContaining("artifactId");
    }

    /** Gradle runs tests from the project directory, but a probe launcher may not. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate).describedAs("repository root").isNotNull();
        return candidate;
    }
}
