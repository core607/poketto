package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NativeRepositoryStatusTests {
    private static final String BASE = "a".repeat(40);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AccountCommand held = mock(AccountCommand.class);
    private final AccountCopyRecord record = mock(AccountCopyRecord.class);
    private final NativeRepositoryLease lease = mock(NativeRepositoryLease.class);
    private final SelectedFileSaves saves = mock(SelectedFileSaves.class);
    private final NativeRepositoryHost host = new NativeRepositoryHost(
            saves,
            mock(NativeRepositoryHost.SnapshotReader.class),
            mock(NativeArtifactCommands.class),
            mock(NativeMediaCommands.class),
            mock(NativePlanCommands.class),
            mock(NativeExportCommands.class));

    @BeforeEach
    void initialize() {
        var owner = new AccountCopyRecord.Owner(UUID.randomUUID(), workspace.value(), true);
        when(held.owner()).thenReturn(owner);
        when(held.record()).thenReturn(record);
        when(record.owner()).thenReturn(owner);
        when(record.copyId()).thenReturn(UUID.randomUUID());
        when(record.state()).thenReturn(new SelectedFileSaves.State(BASE).snapshot());
    }

    @Test
    void defaultStatusReadsOnlyLocalAcknowledgedState() {
        JsonNode result = reply("{}").path("result");
        assertThat(result.path("initialCommit").stringValue()).isEqualTo(BASE);
        assertThat(result.path("baseCommit").stringValue()).isEqualTo(BASE);
        assertThat(result.has("gitCommit")).isFalse();
        assertThat(result.has("remote")).isFalse();
        verifyNoInteractions(saves);
    }

    @Test
    void explicitRemoteStatusComparesCurrentMainWithTheHostBaseline() {
        for (Optional<String> remote :
                List.of(Optional.of(BASE), Optional.of("b".repeat(40)), Optional.<String>empty())) {
            when(saves.currentCommit(actor, workspace)).thenReturn(remote);
            JsonNode result = reply("{\"remote\":true}").path("result").path("remote");
            assertThat(result.path("state").stringValue())
                    .isEqualTo(remote.filter(BASE::equals).isPresent() ? "MATCHES_BASE" : "DIFFERS_FROM_BASE");
            assertThat(result.path("commit").isNull()).isEqualTo(remote.isEmpty());
            remote.ifPresent(
                    value -> assertThat(result.path("commit").stringValue()).isEqualTo(value));
        }
    }

    @Test
    void unavailableRemotePreservesLocalStatusAndDeniedAccessDoesNot() {
        when(saves.currentCommit(actor, workspace)).thenThrow(new ContentRepositoryException("fixture offline"));
        JsonNode unavailable = reply("{\"remote\":true}");
        assertThat(unavailable.path("ok").booleanValue()).isTrue();
        assertThat(unavailable.path("result").path("baseCommit").stringValue()).isEqualTo(BASE);
        assertThat(unavailable.path("result").path("remote").path("state").stringValue())
                .isEqualTo("UNAVAILABLE");
        doThrow(new AuthException(AuthException.Code.DENIED)).when(saves).currentCommit(actor, workspace);
        assertThat(reply("{\"remote\":true}").path("code").stringValue()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void publicRemoteStatusNeverReadsOrExposesThePrivateAuthorityCommit() {
        var owner = new AccountCopyRecord.Owner(UUID.randomUUID(), workspace.value(), false);
        when(record.owner()).thenReturn(owner);
        RepositorySnapshotExports.PublicExport projection = mock(RepositorySnapshotExports.PublicExport.class);
        when(projection.export())
                .thenReturn(new RepositorySnapshotExports.Export(UUID.randomUUID(), BASE, "f".repeat(64), 1));
        when(record.publicExport()).thenReturn(projection);
        JsonNode remote = reply("{\"remote\":true}").path("result").path("remote");
        assertThat(remote.path("state").stringValue()).isEqualTo("PUBLIC_PROJECTION");
        assertThat(remote.path("commit").stringValue()).isEqualTo(BASE);
        verifyNoInteractions(saves);
    }

    @Test
    void invalidStatusFlagsCannotTriggerAReadAndCurrentAuthorizationStillApplies() {
        for (String arguments : List.of(
                "{\"remote\":1}", "{\"remote\":null}", "{\"other\":true}", "{\"remote\":true,\"extra\":true}")) {
            assertThat(reply(arguments).path("code").stringValue()).isEqualTo("INVALID_SELECTION");
        }
        verifyNoInteractions(saves);
        doThrow(new AuthException(AuthException.Code.DENIED)).when(lease).authorize();
        assertThatThrownBy(() -> reply("{\"remote\":true}")).isInstanceOf(AuthException.class);
        verifyNoInteractions(saves);
    }

    @Test
    void twoMaximumLegacyReceiptsStayDurableAndAreDeliveredInDigestBoundPages() {
        BridgeReplies.RestoredReceipt retained = receipt(65536);
        var state = new SelectedFileSaves.State(BASE);
        state.lastSave = retained;
        state.lastImport = retained;
        RetainedSaveState durable = state.snapshot();
        when(record.state()).thenReturn(durable);
        JsonNode status = reply("{}");
        assertThat(SandcrabProtocol.write(status).length).isLessThanOrEqualTo(65536);
        assertThat(status.path("result").path("lastSave").path("omitted").booleanValue())
                .isTrue();
        assertThat(status.path("result").path("lastImport").path("omitted").booleanValue())
                .isTrue();
        assertThat(status.path("result").path("lastSave").has("value")).isFalse();
        for (String kind : List.of("save", "import")) {
            var output = new ByteArrayOutputStream();
            String digest = null;
            int pages = 0;
            while (true) {
                JsonNode page = receiptReply(new BridgeArguments.Receipt(kind, output.size(), 16384, digest))
                        .path("result");
                assertThat(SandcrabProtocol.write(page).length).isLessThanOrEqualTo(65536);
                assertThat(page.path("offset").intValue()).isEqualTo(output.size());
                assertThat(page.path("bytes").intValue()).isEqualTo(65536);
                digest = page.path("sha256").stringValue();
                output.writeBytes(Base64.getDecoder().decode(page.path("base64").stringValue()));
                pages++;
                if (page.path("nextOffset").isNull()) {
                    break;
                }
                assertThat(page.path("nextOffset").intValue()).isEqualTo(output.size());
            }
            assertThat(pages).isEqualTo(4);
            assertThat(output.toByteArray()).containsExactly(SandcrabProtocol.write(retained));
            assertThat(digest).isEqualTo(SandcrabProtocol.sha256(output.toByteArray()));
        }
        assertThat(record.state()).isSameAs(durable);
        verifyNoInteractions(saves);
    }

    @Test
    void receiptPreviewCountsEncodedBytesAndKeepsTheExactBoundary() {
        for (int size : new int[] {2, 16384, 16385}) {
            BridgeReplies.Recorded retained = size == 2 ? new BridgeReplies.Absent() : receipt(size);
            NativeRepositoryReceipts.Preview preview = NativeRepositoryReceipts.preview(retained);
            assertThat(preview.bytes()).isEqualTo(size);
            assertThat(preview.omitted()).isEqualTo(size > 16384);
            assertThat(preview.value()).isEqualTo(size > 16384 ? null : retained);
        }
    }

    @Test
    void changedReceiptAndInvalidContinuationsCannotJoinDifferentAcknowledgements() {
        JsonNode changed = receiptReply(new BridgeArguments.Receipt("save", 0, 16, "f".repeat(64)));
        assertThat(changed.path("code").stringValue()).isEqualTo("RECEIPT_CHANGED");
        String absentDigest = SandcrabProtocol.sha256("{}".getBytes(StandardCharsets.UTF_8));
        JsonNode invalid = receiptReply(new BridgeArguments.Receipt("save", 3, 16, absentDigest));
        assertThat(invalid.path("code").stringValue()).isEqualTo("INVALID_SELECTION");
        for (String arguments : List.of(
                "{\"kind\":\"save\",\"offset\":1,\"limit\":16,\"sha256\":null}",
                "{\"kind\":\"save\",\"offset\":0.5,\"limit\":16,\"sha256\":null}",
                "{\"kind\":\"save\",\"offset\":0,\"limit\":16385,\"sha256\":null}",
                "{\"kind\":\"other\",\"offset\":0,\"limit\":16,\"sha256\":null}")) {
            JsonNode reply = hostReply("receipt", JsonMapper.builder().build().readTree(arguments));
            assertThat(reply.path("code").stringValue()).isEqualTo("INVALID_SELECTION");
        }
        verifyNoInteractions(saves);
    }

    private static BridgeReplies.RestoredReceipt receipt(int bytes) {
        int payload = bytes - SandcrabProtocol.write(new ReceiptBody("")).length;
        var body = new ReceiptBody("汉".repeat(payload / 3) + "x".repeat(payload % 3));
        return new BridgeReplies.RestoredReceipt(JsonMapper.builder().build().valueToTree(body));
    }

    private JsonNode receiptReply(BridgeArguments.Receipt query) {
        return hostReply("receipt", JsonMapper.builder().build().valueToTree(query));
    }

    private JsonNode hostReply(String operation, JsonNode arguments) {
        byte[] request = SandcrabProtocol.write(new NativeRepositoryHost.Request(operation, arguments));
        byte[] response = host.reply(actor, held, lease, request);
        assertThat(response.length).isLessThanOrEqualTo(65536);
        return JsonMapper.builder().build().readTree(response);
    }

    private record ReceiptBody(String text) {}

    private JsonNode reply(String arguments) {
        byte[] request =
                ("{\"operation\":\"status\",\"arguments\":" + arguments + "}").getBytes(StandardCharsets.UTF_8);
        return JsonMapper.builder().build().readTree(host.reply(actor, held, lease, request));
    }
}
