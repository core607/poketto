package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandcrabInventoryTests {
    private static final String EPOCH = "13289a65-c1e2-451c-97c9-07cba1a0f58f";
    private static final String FIRST = "3b45286e-07e5-4aa8-8ddc-99787b075f2e";
    private static final String SECOND = "7355a29f-fe96-4cd3-828e-d1e887bf3ef2";

    @Test
    void pagesUseTheRetainedEpochAndContinueAfterTheGivenIdentity() throws Exception {
        for (String collection : List.of("runs", "sessions")) {
            try (var peer = new SandboxPeer(channel -> {
                assertThat(SandboxPeer.request(channel).head())
                        .startsWith("GET /v1/" + collection + "?after=" + FIRST + " HTTP/1.1");
                SandboxPeer.reply(channel, 200, page(EPOCH, "[\"" + SECOND + "\"]", "null"));
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                SandcrabProtocol.IdentityPage page =
                        collection.equals("runs") ? client.runs(EPOCH, FIRST) : client.sessions(EPOCH, FIRST);
                assertThat(page.ids()).containsExactly(SECOND);
                assertThat(page.nextAfter()).isNull();
            }
        }
    }

    @Test
    void changedEpochOrBackwardCursorCannotDriveCleanup() throws Exception {
        for (String response : List.of(page(SECOND, "[]", "null"), page(EPOCH, "[\"" + FIRST + "\"]", "null"))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 200, response);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.runs(EPOCH, FIRST)).isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void malformedPagesCannotHideRecordsOrLoopTheConsumer() {
        for (String response : List.of(
                page(EPOCH, "[\"" + FIRST + "\",\"" + FIRST + "\"]", "null"),
                page(EPOCH, "[\"" + SECOND + "\",\"" + FIRST + "\"]", "null"),
                page(EPOCH, "[]", "\"" + FIRST + "\""),
                page(EPOCH, "[\"" + FIRST + "\"]", "\"" + SECOND + "\""),
                page(EPOCH, "[]", "null").replace(",\"nextAfter\":null", ""))) {
            assertThatThrownBy(() -> SandcrabProtocol.read(
                            response.getBytes(StandardCharsets.UTF_8), SandcrabProtocol.IdentityPage.class))
                    .isInstanceOf(WorkerUnavailableException.class);
        }
    }

    private static String page(String epoch, String ids, String next) {
        return "{\"serverId\":\"" + epoch + "\",\"ids\":" + ids + ",\"nextAfter\":" + next + "}";
    }
}
