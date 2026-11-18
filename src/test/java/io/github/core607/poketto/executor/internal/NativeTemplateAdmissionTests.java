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
    void anUnknownWorkspaceCreationKeepsTheTemplateAndNeverStartsTheUserCommand() {
        doAnswer(call -> {
                    var pending = journal.record(owner).orElseThrow();
                    assertThat(pending.phase()).isEqualTo(AccountCopyRecord.Phase.ATTACHING);
                    assertThat(pending.writer().storage().templateId()).hasSize(64);
                    throw new WorkerUnavailableException();
                })
                .when(keyed)
                .ensure(any(), any(), any());
        try (var executor = executor((run, timeout, handler) -> {})) {
            assertThatThrownBy(() -> execute(executor, "new")).isInstanceOf(ExecutionAdmissionException.class);
            var retained = journal.record(owner).orElseThrow();
            assertThat(retained.writer().storage().workspaceGeneration()).isNull();
            assertThatThrownBy(() -> execute(executor, retained.copyId().toString()))
                    .isInstanceOf(ExecutionAdmissionException.class);
            assertThat(journal.record(owner).orElseThrow().original()).isEqualTo(retained.original());
            assertThat(commands).isEmpty();
            verify(keyed, never()).submit(any(), any(), any());
        }
    }
}
