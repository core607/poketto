package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeRepositoryDeadlineTests extends NativeRepositoryTestFixture {
    @Test
    void aBudgetConsumedByRegistrationCannotStartSendingTheBody() {
        NativeRepositoryLease lease = lease();
        lease.open();
        lease.command(UUID.randomUUID(), "export", Duration.ofSeconds(1));
        doAnswer(call -> {
                    Duration budget = call.getArgument(2);
                    assertThat(budget).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
                    Thread.sleep(1100);
                    return upload(call.getArgument(1));
                })
                .when(client)
                .register(any(), any(), any());
        var file = mock(NativeTransferStore.File.class);
        when(file.sha256()).thenReturn("a".repeat(64));
        assertThatThrownBy(() -> lease.upload("work/repository/export.zip", file))
                .isInstanceOf(WorkerUnavailableException.class);
        verify(client).register(any(), any(), any());
        verify(client, never()).send(any(), any(Path.class), any(), any());
        assertThatThrownBy(lease::remaining).isInstanceOf(WorkerUnavailableException.class);
    }

    @Test
    void anAlreadyElapsedCommandCannotEvenRegisterANewUpload() throws Exception {
        NativeRepositoryLease lease = lease();
        lease.open();
        lease.command(UUID.randomUUID(), "expired", Duration.ofMillis(1));
        Thread.sleep(10);
        assertThatThrownBy(() -> lease.upload("work/repository/export.zip", mock(NativeTransferStore.File.class)))
                .isInstanceOf(WorkerUnavailableException.class);
        verify(client, never()).register(any(), any(), any());
        verify(client, never()).send(any(), any(Path.class), any(), any());
        lease.command(UUID.randomUUID(), "next request", Duration.ofSeconds(30));
        assertThat(lease.remaining()).isGreaterThan(Duration.ofSeconds(2));
    }

    private NativeRepositoryLease lease() {
        var storage = new AccountCopyRecord.NativeStorage(UUID.randomUUID(), "repository", 8_388_608, 256);
        var writer = new AccountCopyRecord.Writer(
                principal.subjectId(), UUID.fromString(epoch), UUID.randomUUID(), UUID.randomUUID(), storage);
        return new NativeRepositoryLease(client, storage, writer, 120000, 65536, 8_388_608, () -> {});
    }

    @Test
    void oversizedHostMaterializationIsRefusedBeforeNativeRegistration() {
        NativeRepositoryLease lease = lease();
        lease.open();
        lease.command(UUID.randomUUID(), "export", Duration.ofSeconds(30));
        var file = mock(NativeTransferStore.File.class);
        when(file.size()).thenReturn(lease.transferLimit() + 1);
        assertThatThrownBy(() -> lease.upload("work/repository/export.zip", file))
                .isInstanceOf(NativeTransferStore.Capacity.class);
        verify(client, never()).register(any(), any(), any());
        verify(client, never()).send(any(), any(Path.class), any(), any());
    }
}
