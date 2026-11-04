package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.core607.poketto.mcp.ExecutionAdmissionException;
import org.junit.jupiter.api.Test;

class NativeTemplateAdmissionTests extends NativeRepositoryTestFixture {
    @Test
    void failedTemplatePublicationCreatesNoCopyOrUserSession() {
        org.mockito.Mockito.doThrow(new WorkerUnavailableException())
                .when(overlays)
                .bind(any(), any(), any(), any(), any(), any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
        }
        assertThat(journal.record(owner)).isEmpty();
        assertThat(commands).isEmpty();
        verify(client, never()).createVolume(any());
        verify(client, never()).open(any());
        verify(exports).release(any());
    }

    @Test
    void anUnknownSessionAdmissionPreservesThePinnedTemplateWithoutReplayingTheCommand() {
        doAnswer(call -> {
                    assertThat(journal.record(owner)
                                    .orElseThrow()
                                    .writer()
                                    .storage()
                                    .templateId())
                            .hasSize(64);
                    throw new WorkerUnavailableException();
                })
                .when(client)
                .open(any());
        var executor = executor((run, timeout, handler) -> {});
        try {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            var retained = journal.record(owner).orElseThrow();
            assertThat(retained.phase()).isEqualTo(AccountCopyRecord.Phase.INITIALIZING);
            assertThatThrownBy(() -> execute(executor, retained.copyId().toString()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner)).contains(retained);
        } finally {
            assertThatThrownBy(executor::close).isInstanceOf(WorkerUnavailableException.class);
        }
        assertThat(commands).isEmpty();
        verify(client).open(any());
        verify(client, never()).openChild(any(), any());
    }
}
