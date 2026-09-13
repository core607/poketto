package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A worker answer is machine output, so these tests are about what happens when it is wrong.
 *
 * <p>Two failures here would be silent and expensive. An unparseable answer must leave as an
 * unavailable worker, never as a rejected request, because telling the agent its selection was
 * invalid invites it to retry a command that may already have run. And a missing number or flag
 * must not arrive as zero or false, because an answer that omits an exit code or an offset would
 * then be read as a command that succeeded at the position the caller expected.
 */
class WorkerAnswerTests {
    @Test
    void binaryCaptureKeepsItsOriginalFileBoundWithoutIncreasingTheTextBudget() {
        String file = "{\"path\":\"private/large.bin\",\"bytes\":%d,\"sha256\":\"" + "a".repeat(64) + "\"}";
        String manifest = "{\"captureId\":\"" + UUID + "\",\"writes\":[%s],\"deletes\":[],\"absent\":[]}";
        for (long size : new long[] {4L * 1024 * 1024 + 1, 128L * 1024 * 1024}) {
            var response = json(manifest.formatted(file.formatted(size)));
            assertThat(WorkerResponses.read(response, WorkerResponses.BinaryCaptureManifest.class)
                            .writes()
                            .getFirst()
                            .bytes())
                    .isEqualTo(size);
            assertThatThrownBy(() -> WorkerResponses.read(response, WorkerResponses.CaptureManifest.class))
                    .isInstanceOf(WorkerUnavailableException.class);
        }
        var empty = json(manifest.formatted(file.formatted(0)));
        assertThat(WorkerResponses.read(empty, WorkerResponses.BinaryCaptureManifest.class)
                        .writes()
                        .getFirst()
                        .bytes())
                .isZero();
        var oversized = json(manifest.formatted(file.formatted(128L * 1024 * 1024 + 1)));
        assertThatThrownBy(() -> WorkerResponses.read(oversized, WorkerResponses.BinaryCaptureManifest.class))
                .isInstanceOf(WorkerUnavailableException.class);
        String overflow = manifest.formatted(file.formatted(Long.MAX_VALUE) + "," + file.formatted(Long.MAX_VALUE));
        assertThatThrownBy(() -> WorkerResponses.read(json(overflow), WorkerResponses.CaptureManifest.class))
                .isInstanceOf(WorkerUnavailableException.class);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UUID = "22222222-2222-4222-8222-222222222222";
    private static final String DIGEST = "d".repeat(64);

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void anUnparseableAnswerLeavesAsAnUnavailableWorkerNotARejectedRequest() {
        for (String malformed : new String[] {
            "{\"captureId\":\"not-a-uuid\",\"writes\":[],\"deletes\":[],\"absent\":[]}",
            "{\"captureId\":\"" + UUID + "\",\"writes\":[{\"path\":\"a.md\",\"bytes\":-1,\"sha256\":\"" + DIGEST
                    + "\"}],\"deletes\":[],\"absent\":[]}",
            "{\"captureId\":\"" + UUID + "\",\"writes\":[{\"path\":\"a.md\",\"bytes\":5000000,\"sha256\":\"" + DIGEST
                    + "\"}],\"deletes\":[],\"absent\":[]}",
            "{\"captureId\":\"" + UUID + "\",\"deletes\":[],\"absent\":[]}"
        }) {
            assertThatThrownBy(() -> WorkerResponses.read(json(malformed), WorkerResponses.CaptureManifest.class))
                    .describedAs(malformed)
                    .isInstanceOf(WorkerUnavailableException.class);
        }
    }

    @Test
    void aMissingNumberOrFlagIsNotReadAsZeroOrFalse() {
        assertThatThrownBy(() -> WorkerResponses.read(json("{\"data\":\"YWJj\"}"), WorkerResponses.ArtifactPage.class))
                .describedAs("an artifact page without an offset")
                .isInstanceOf(WorkerUnavailableException.class);
        assertThatThrownBy(() -> WorkerResponses.read(
                        json("{\"captureId\":\"" + UUID + "\",\"offset\":0,\"data\":\"YWJj\"}"),
                        WorkerResponses.CaptureChunk.class))
                .describedAs("a capture page without an index")
                .isInstanceOf(WorkerUnavailableException.class);
        assertThatThrownBy(() -> WorkerResponses.read(
                        json("{\"stdout\":\"\",\"stderr\":\"\",\"stdoutTruncated\":false,"
                                + "\"stderrTruncated\":false,\"timedOut\":false,\"terminationReason\":\"normal\"}"),
                        WorkerResponses.Execution.class))
                .describedAs("a finished command without an exit code")
                .isInstanceOf(WorkerUnavailableException.class);
    }

    @Test
    void aHandshakeThatAdvertisesAnotherProtocolIsRefused() {
        String good = "{\"ok\":true,\"version\":1,\"maxFrameBytes\":1048576,\"codeActProtocol\":1,"
                + "\"artifactProtocol\":1,\"moveProtocol\":1,\"exportProtocol\":1,\"workerBootId\":\"" + UUID
                + "\",\"leaseSeconds\":60,\"renewAfterSeconds\":20}";
        assertThat(WorkerResponses.read(json(good), WorkerResponses.Handshake.class)
                        .leaseSeconds())
                .isEqualTo(60);
        assertThatThrownBy(() -> WorkerResponses.read(
                        json(good.replace("\"moveProtocol\":1", "\"moveProtocol\":2")),
                        WorkerResponses.Handshake.class))
                .isInstanceOf(WorkerUnavailableException.class);
        assertThatThrownBy(() -> WorkerResponses.read(
                        json(good.replace("\"renewAfterSeconds\":20", "\"renewAfterSeconds\":30")),
                        WorkerResponses.Handshake.class))
                .describedAs("renewal must leave room for two lost attempts")
                .isInstanceOf(WorkerUnavailableException.class);
        for (String identity : new String[] {"\"workerBootId\":\"not-a-uuid\"", "\"workerBootId\":null"}) {
            assertThatThrownBy(() -> WorkerResponses.read(
                            json(good.replace("\"workerBootId\":\"" + UUID + "\"", identity)),
                            WorkerResponses.Handshake.class))
                    .describedAs(identity)
                    .isInstanceOf(WorkerUnavailableException.class);
        }
    }

    @Test
    void aFinishedCommandMustAgreeWithItsOwnTerminationReason() {
        String finished = "{\"exitCode\":0,\"stdout\":\"\",\"stderr\":\"\",\"stdoutTruncated\":false,"
                + "\"stderrTruncated\":false,\"timedOut\":true,\"terminationReason\":\"normal\"}";
        assertThatThrownBy(() -> WorkerResponses.read(json(finished), WorkerResponses.Execution.class)
                        .reason())
                .isInstanceOf(WorkerUnavailableException.class);
        assertThatThrownBy(() -> WorkerResponses.read(
                                json(finished.replace("\"normal\"", "\"invented\"")), WorkerResponses.Execution.class)
                        .reason())
                .describedAs("a termination this application does not know")
                .isInstanceOf(WorkerUnavailableException.class);
    }

    /**
     * Decoding happens after parsing succeeds, so it is the one step where a broken worker could
     * still have been reported as a caller mistake.
     */
    @Test
    void aPageWhoseContentIsNotBase64IsAlsoAnUnavailableWorker() {
        assertThatThrownBy(() -> WorkerResponses.read(
                                json("{\"captureId\":\"" + UUID + "\",\"index\":0,\"offset\":0,\"data\":\"!!\"}"),
                                WorkerResponses.CaptureChunk.class)
                        .decoded())
                .isInstanceOf(WorkerUnavailableException.class);
        assertThatThrownBy(() -> WorkerResponses.read(
                                json("{\"offset\":0,\"data\":\"!!\"}"), WorkerResponses.ArtifactPage.class)
                        .decoded())
                .isInstanceOf(WorkerUnavailableException.class);
    }
}
