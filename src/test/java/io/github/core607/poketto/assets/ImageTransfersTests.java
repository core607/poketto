package io.github.core607.poketto.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.internal.PublicImageDownloader;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImageTransfersTests {
    @Test
    void grantSurvivesTransportChangesButExpiresAndRechecksOriginalAuthority() throws Exception {
        AuthService auth = mock(AuthService.class);
        AuthPrincipal actor = mock(AuthPrincipal.class);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        var workspace = WorkspaceId.random();
        AssetService assets = mock(AssetService.class);
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var memory = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 1, Duration.ZERO);
        var transfers = new ImageTransfers(
                auth, assets, memory, mock(PublicImageDownloader.class), clock, "https://example.com");
        String key = UUID.randomUUID().toString();
        var upload = transfers.prepare(actor, workspace, key);
        assertThat(transfers.prepare(actor, workspace, key)).isEqualTo(upload);
        String token = upload.uploadUrl().substring(upload.uploadUrl().lastIndexOf('/') + 1);
        assertThatThrownBy(() -> transfers.status(token))
                .isInstanceOf(ImageTransferException.class)
                .hasMessage("UPLOAD_PENDING");
        var asset = new ManagedAsset(new ManagedAssetReference(UUID.randomUUID(), "a".repeat(64)), "image/png", 3);
        when(assets.upload(eq(actor), eq(workspace), eq(key), any())).thenReturn(asset);
        var scope = transfers.reserve(token);
        try (var producer = scope.producer()) {
            assertThat(transfers.upload(token, new byte[] {1, 2, 3})).isEqualTo(ImageTransfers.Receipt.of(asset));
        } finally {
            scope.responseComplete();
        }
        assertThat(transfers.status(token)).isEqualTo(ImageTransfers.Receipt.of(asset));
        assertThat(memory.reservedBytes()).isZero();
        doThrow(new AuthException(AuthException.Code.DENIED))
                .when(auth)
                .authorize(actor, workspace, Capability.WRITE_PRIVATE);
        assertThatThrownBy(() -> transfers.status(token)).isInstanceOf(AuthException.class);
        when(clock.instant()).thenReturn(now.plus(Duration.ofMinutes(15)));
        assertThatThrownBy(() -> transfers.status(token))
                .isInstanceOf(ImageTransferException.class)
                .hasMessage("UPLOAD_EXPIRED");
    }

    @Test
    void incomingBodyIsBoundedBeforeCallingDurableStorage() throws Exception {
        AuthService auth = mock(AuthService.class);
        AuthPrincipal actor = mock(AuthPrincipal.class);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        var memory = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 1, Duration.ZERO);
        var transfers = new ImageTransfers(
                auth,
                mock(AssetService.class),
                memory,
                mock(PublicImageDownloader.class),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                "https://example.com");
        var upload =
                transfers.prepare(actor, WorkspaceId.random(), UUID.randomUUID().toString());
        String token = upload.uploadUrl().substring(upload.uploadUrl().lastIndexOf('/') + 1);
        assertThatThrownBy(() -> transfers.upload(token, new byte[ManagedBlobStore.MAX_UPLOAD_BYTES + 1]))
                .isInstanceOf(AssetStorageException.class);
        assertThatThrownBy(() -> transfers.status(token)).hasMessage("UPLOAD_PENDING");
    }
}
