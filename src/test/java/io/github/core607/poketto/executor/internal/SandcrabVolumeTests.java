package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Controlled protocol peers exercise durable identities and uncertain cleanup, not XFS containment. */
class SandcrabVolumeTests {
    private static final String ID = "3b45286e-07e5-4aa8-8ddc-99787b075f2e";
    private static final String EPOCH = "13289a65-c1e2-451c-97c9-07cba1a0f58f";
    private static final String LATER = "7355a29f-fe96-4cd3-828e-d1e887bf3ef2";

    private static SandcrabVolume.Plan plan(String epoch) {
        return new SandcrabVolume.Plan(ID, epoch, "python", null, 8L * 1024 * 1024, 256L);
    }

    private static String receipt(String state, boolean busy) {
        return """
                {"id":"%s","profile":"python","state":"%s","code":null,
                 "byteLimit":8388608,"inodeLimit":256,"cloneFrom":null,
                 "planDigest":"%s","busy":%s,"attachedTo":null}
                """.formatted(ID, state, "a".repeat(64), busy);
    }

    private static SandcrabVolume volume(String state, boolean busy) {
        return SandcrabProtocol.read(receipt(state, busy).getBytes(StandardCharsets.UTF_8), SandcrabVolume.class);
    }

    @Test
    void aDeleteRequestRetainsItsIdentityUntilStorageReleaseIsConfirmed() throws Exception {
        var step = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            switch (step.getAndIncrement()) {
                case 0 -> {
                    assertThat(request.head()).startsWith("POST /v1/volumes HTTP/1.1");
                    assertThat(SandcrabProtocol.read(request.body(), SandcrabVolume.Plan.class))
                            .isEqualTo(plan(EPOCH));
                    SandboxPeer.reply(channel, 202, receipt("ready", false));
                }
                case 1 -> {
                    assertThat(request.head()).startsWith("POST /v1/volumes/" + ID + "/delete HTTP/1.1");
                    SandboxPeer.reply(channel, 202, receipt("deleting", false));
                }
                case 2 -> {
                    assertThat(request.head()).startsWith("GET /v1/volumes/" + ID + " HTTP/1.1");
                    SandboxPeer.reply(channel, 200, receipt("deleted", false));
                }
                case 3 -> {
                    assertThat(request.head()).startsWith("POST /v1/volumes/" + ID + "/forget HTTP/1.1");
                    SandboxPeer.reply(channel, 200, "{\"forgotten\":true}");
                }
                default -> throw new AssertionError("Unexpected request after volume cleanup");
            }
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            SandcrabVolume ready = client.createVolume(plan(EPOCH));
            SandcrabVolume deleting = client.deleteVolume(ready);
            assertThat(deleting.forgettable()).isFalse();
            assertThatThrownBy(() -> client.forget(deleting)).isInstanceOf(WorkerUnavailableException.class);
            SandcrabVolume deleted = client.volume(ready);
            assertThat(deleted.forgettable()).isTrue();
            client.forget(deleted);
            assertThat(peer.requests()).isEqualTo(4);
        }
    }

    @Test
    void responseLossDoesNotAllocateAgainAndAnExplicitReplayCanUseANewEpoch() throws Exception {
        var requests = new AtomicInteger();
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.Request request = SandboxPeer.request(channel);
            assertThat(request.head()).startsWith("POST /v1/volumes HTTP/1.1");
            SandcrabVolume.Plan observed = SandcrabProtocol.read(request.body(), SandcrabVolume.Plan.class);
            if (requests.getAndIncrement() == 0) {
                assertThat(observed).isEqualTo(plan(EPOCH));
                return;
            }
            assertThat(observed).isEqualTo(plan(LATER));
            SandboxPeer.reply(channel, 200, receipt("ready", false));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.createVolume(plan(EPOCH)))
                    .isInstanceOfSatisfying(
                            WorkerUnavailableException.class,
                            failure -> assertThat(failure.mayHaveExecuted()).isTrue());
            assertThat(peer.requests()).isEqualTo(1);
            assertThat(client.createVolume(plan(LATER))).isEqualTo(volume("ready", false));
            assertThat(peer.requests()).isEqualTo(2);
        }
    }

    @Test
    void creationRepliesCannotChangeStorageIdentityCloneSourceOrBounds() throws Exception {
        String ready = receipt("ready", false);
        for (String reply : List.of(
                ready.replace(ID, LATER),
                ready.replace("python", "other"),
                ready.replace("\"cloneFrom\":null", "\"cloneFrom\":\"" + LATER + "\""),
                ready.replace("8388608", "4194304"),
                ready.replace("256", "128"))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 202, reply);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.createVolume(plan(EPOCH)))
                        .isInstanceOf(WorkerUnavailableException.class);
                assertThat(peer.requests()).isEqualTo(1);
            }
        }
    }

    @Test
    void inspectionRejectsAReusedIdentityWithADifferentCreationDigest() throws Exception {
        try (var peer = new SandboxPeer(channel -> {
            SandboxPeer.request(channel);
            SandboxPeer.reply(channel, 200, receipt("ready", false).replace("a".repeat(64), "b".repeat(64)));
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.volume(volume("ready", false)))
                    .isInstanceOf(WorkerUnavailableException.class);
        }
    }

    @Test
    void busyClonesAndUncontainedStorageNeverPermitLocalCleanupClaims() throws Exception {
        try (var peer = new SandboxPeer(channel -> {
            throw new AssertionError("Unsafe cleanup must be rejected before dispatch");
        })) {
            var client = new SandcrabClient(peer.client(channel -> {}));
            assertThatThrownBy(() -> client.deleteVolume(volume("ready", true)))
                    .isInstanceOf(WorkerUnavailableException.class);
            for (String state : List.of("creating", "ready", "deleting", "closing")) {
                assertThatThrownBy(() -> client.forget(volume(state, false)))
                        .isInstanceOf(WorkerUnavailableException.class);
            }
            assertThat(peer.requests()).isZero();
        }
    }

    @Test
    void missingOwnershipFieldsAndContradictoryAttachmentAreNotDefaultedToUnowned() throws Exception {
        String ready = receipt("ready", false);
        for (String reply : List.of(
                ready.replace("\"busy\":false,", ""),
                ready.replace(",\"attachedTo\":null", ""),
                ready.replace("\"cloneFrom\":null,", ""),
                ready.replace("\"attachedTo\":null", "\"attachedTo\":\"" + LATER + "\""))) {
            try (var peer = new SandboxPeer(channel -> {
                SandboxPeer.request(channel);
                SandboxPeer.reply(channel, 200, reply);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.volume(ID)).isInstanceOf(WorkerUnavailableException.class);
            }
        }
    }

    @Test
    void inventoryPagesCannotRepeatOrMoveBehindTheirCursor() throws Exception {
        String first = receipt("ready", false);
        String next = first.replace(ID, LATER);
        for (String reply : List.of(
                "{\"volumes\":[" + first + "],\"nextAfter\":null}",
                "{\"volumes\":[" + next + "," + next + "],\"nextAfter\":null}",
                "{\"volumes\":[" + next + "],\"nextAfter\":\"" + ID + "\"}")) {
            try (var peer = new SandboxPeer(channel -> {
                assertThat(SandboxPeer.request(channel).head()).startsWith("GET /v1/volumes?after=" + ID + " HTTP/1.1");
                SandboxPeer.reply(channel, 200, reply);
            })) {
                var client = new SandcrabClient(peer.client(channel -> {}));
                assertThatThrownBy(() -> client.volumes(ID)).isInstanceOf(WorkerUnavailableException.class);
            }
        }
    }
}
