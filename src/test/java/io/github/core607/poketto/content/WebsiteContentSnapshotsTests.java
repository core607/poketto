package io.github.core607.poketto.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class WebsiteContentSnapshotsTests {
    @Test
    void withdrawalRejectsBothNewReadsAndResultsPreparedWhileTheSwitchChanges() {
        WorkspaceId workspace = WorkspaceId.random();
        PublicContentSnapshots source = mock(PublicContentSnapshots.class);
        WorkspacePublications publications = mock(WorkspacePublications.class);
        var enabled = new AtomicBoolean(true);
        var snapshot = new PublicContentSnapshot(
                workspace, Optional.of("a".repeat(40)), Instant.EPOCH, Instant.EPOCH.plusSeconds(60), List.of());
        doAnswer(call -> {
                    if (!enabled.get()) {
                        throw new PublicationUnavailableException();
                    }
                    return null;
                })
                .when(publications)
                .requireEnabled(workspace);
        doAnswer(call -> {
                    Function<PublicContentSnapshot, Object> action = call.getArgument(1);
                    return action.apply(snapshot);
                })
                .when(source)
                .withCurrent(eq(workspace), any());
        var website = new WebsiteContentSnapshots(source, publications);
        assertThat(website.current(workspace)).isSameAs(snapshot);
        assertThatThrownBy(() -> website.withCurrent(workspace, current -> {
                    enabled.set(false);
                    return current;
                }))
                .isInstanceOf(ContentRepositoryException.class)
                .hasCauseInstanceOf(PublicationUnavailableException.class);
        assertThatThrownBy(() -> website.withCurrent(workspace, current -> {
                    throw new AssertionError("disabled website must not enter delivery");
                }))
                .isInstanceOf(ContentRepositoryException.class);
        verify(source, never()).refresh(any());
        assertThat(source.withCurrent(workspace, Function.identity())).isSameAs(snapshot);
    }
}
