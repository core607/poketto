package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PublishedWorkspaceRefreshBatchTests {
    @Test
    void rotatesPastTheFirstPageAndKeepsTheDefaultHealthyWithoutDuplicateRefreshes() {
        var publications = mock(WorkspacePublications.class);
        var page = IntStream.range(0, 8)
                .mapToObj(index -> new WorkspacePublications.Publication(
                        WorkspaceId.random(), "space-" + index, "Space", true, true, "", "", false))
                .toList();
        WorkspaceId defaultId = page.getFirst().workspaceId();
        WorkspaceId last = WorkspaceId.random();
        when(publications.publishedAfter(Optional.empty(), 8)).thenReturn(page);
        when(publications.publishedAfter(Optional.of(page.getLast().workspaceId()), 8))
                .thenReturn(List.of(
                        new WorkspacePublications.Publication(last, "last", "Last", true, true, "", "", false)));
        var batch = new PublishedWorkspaceRefreshBatch(publications, () -> defaultId);
        var refreshed = new ArrayList<WorkspaceId>();
        try (var refresher = new ContentSnapshotRefresher(refreshed::add, batch, Duration.ofSeconds(30))) {
            refresher.refreshAll();
            assertThat(refreshed)
                    .containsExactlyElementsOf(page.stream()
                            .map(WorkspacePublications.Publication::workspaceId)
                            .toList());
            refreshed.clear();
            refresher.refreshAll();
            assertThat(refreshed).containsExactly(defaultId, last);
            refreshed.clear();
            refresher.refreshAll();
            assertThat(refreshed).hasSize(8).contains(defaultId);
        }
    }

    @Test
    void anEmptyTailRestartsImmediatelyAndADisabledCatalogStillRefreshesDefault() {
        var publications = mock(WorkspacePublications.class);
        WorkspaceId defaultId = WorkspaceId.random();
        var page = IntStream.range(0, 8)
                .mapToObj(index -> new WorkspacePublications.Publication(
                        WorkspaceId.random(), "space-" + index, "Space", true, true, "", "", false))
                .toList();
        when(publications.publishedAfter(Optional.empty(), 8)).thenReturn(page, List.of());
        when(publications.publishedAfter(Optional.of(page.getLast().workspaceId()), 8))
                .thenReturn(List.of());
        var batch = new PublishedWorkspaceRefreshBatch(publications, () -> defaultId);
        assertThat(batch.get()).hasSize(9);
        assertThat(batch.get()).containsExactly(defaultId);
    }
}
