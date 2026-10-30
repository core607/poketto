package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameSandboxJobTests {
    private final SandcrabClient client = mock(SandcrabClient.class);
    private final String epoch = UUID.randomUUID().toString();
    private final GameSandboxJob job = new GameSandboxJob(
            client, new GameRunner.Validation(WorkspaceId.random()), IsolatedGameRunnerTests.info(epoch), "exec true");

    @Test
    void failureBeforeRequestWritingRequiresNoRemoteCleanup() {
        when(client.open(any())).thenThrow(new WorkerUnavailableException(new IOException("peer unavailable"), false));
        assertThatThrownBy(() -> job.execute(new byte[0])).isInstanceOf(WorkerUnavailableException.class);
        job.active = false;
        assertThat(job.reclaim(epoch)).isTrue();
        verify(client, never()).session(any());
        verify(client, never()).close(any());
    }

    @Test
    void repeatedMissingRecordsDoNotProveALostAdmissionNeverArrived() {
        when(client.open(any())).thenThrow(new WorkerUnavailableException());
        when(client.session(any())).thenThrow(new SandcrabClient.Rejected(404, "SESSION_NOT_FOUND"));
        assertThatThrownBy(() -> job.execute(new byte[0])).isInstanceOf(WorkerUnavailableException.class);
        job.active = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(job.reclaim(epoch)).isFalse();
        }
        verify(client, times(1)).open(any());
        verify(client, never()).forget(any(SandcrabProtocol.Session.class));
        assertThat(job.reclaim(UUID.randomUUID().toString())).isTrue();
    }

    @Test
    void uncontainedRootRetainsEveryReceiptUntilCleanupIsConfirmed() {
        when(client.open(any())).thenReturn(session("ready"));
        when(client.register(any(), any())).thenThrow(new WorkerUnavailableException());
        assertThatThrownBy(() -> job.execute(new byte[0])).isInstanceOf(WorkerUnavailableException.class);
        job.active = false;
        when(client.session(any())).thenReturn(session("closing"));
        when(client.close(any())).thenReturn(session("closing"));
        assertThat(job.reclaim(epoch)).isFalse();
        verify(client, never()).upload(any());
        verify(client, never()).get(any());
        when(client.session(any())).thenReturn(session("closed"));
        when(client.upload(any())).thenThrow(new SandcrabClient.Rejected(404, "UPLOAD_NOT_FOUND"));
        when(client.get(any())).thenThrow(new SandcrabClient.Rejected(404, "RUN_NOT_FOUND"));
        assertThat(job.reclaim(epoch)).isTrue();
        verify(client).forget(session("closed"));
    }

    @Test
    void lostForgetReplyKeepsTheConfirmedContainmentForTheNextLookup() {
        when(client.open(any())).thenReturn(session("ready"));
        when(client.register(any(), any())).thenThrow(new WorkerUnavailableException());
        assertThatThrownBy(() -> job.execute(new byte[0])).isInstanceOf(WorkerUnavailableException.class);
        job.active = false;
        when(client.session(any())).thenReturn(session("closed"));
        when(client.upload(any())).thenThrow(new SandcrabClient.Rejected(404, "UPLOAD_NOT_FOUND"));
        when(client.get(any())).thenThrow(new SandcrabClient.Rejected(404, "RUN_NOT_FOUND"));
        doThrow(new WorkerUnavailableException()).when(client).forget(any(SandcrabProtocol.Session.class));
        assertThatThrownBy(() -> job.reclaim(epoch)).isInstanceOf(WorkerUnavailableException.class);
        when(client.session(any())).thenThrow(new SandcrabClient.Rejected(404, "SESSION_NOT_FOUND"));
        assertThat(job.reclaim(epoch)).isTrue();
        verify(client, times(1)).open(any());
        verify(client, times(1)).forget(any(SandcrabProtocol.Session.class));
    }

    private SandcrabProtocol.Session session(String state) {
        return IsolatedGameRunnerTests.session(job.plan.id(), epoch, state);
    }
}
