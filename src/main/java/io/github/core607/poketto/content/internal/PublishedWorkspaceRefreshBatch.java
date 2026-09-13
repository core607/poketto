package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Rotates a bounded catalog page without retaining the complete workspace catalog. */
final class PublishedWorkspaceRefreshBatch implements Supplier<List<WorkspaceId>> {
    private static final int PAGE_SIZE = 8;

    private final WorkspacePublications publications;
    private final Supplier<WorkspaceId> defaultWorkspace;
    private Optional<WorkspaceId> cursor = Optional.empty();

    PublishedWorkspaceRefreshBatch(WorkspacePublications publications, Supplier<WorkspaceId> defaultWorkspace) {
        this.publications = publications;
        this.defaultWorkspace = defaultWorkspace;
    }

    @Override
    public synchronized List<WorkspaceId> get() {
        var page = publications.publishedAfter(cursor, PAGE_SIZE);
        if (page.isEmpty() && cursor.isPresent()) {
            page = publications.publishedAfter(Optional.empty(), PAGE_SIZE);
        }
        var workspaces = new LinkedHashSet<WorkspaceId>();
        workspaces.add(defaultWorkspace.get());
        page.forEach(publication -> workspaces.add(publication.workspaceId()));
        cursor = page.size() == PAGE_SIZE ? Optional.of(page.getLast().workspaceId()) : Optional.empty();
        return List.copyOf(workspaces);
    }
}
