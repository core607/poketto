package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.function.Function;

/** Anonymous delivery checks the current website switch as well as repository publication. */
public final class WebsiteContentSnapshots implements PublicContentSnapshots {
    private final PublicContentSnapshots snapshots;
    private final WorkspacePublications publications;

    public WebsiteContentSnapshots(PublicContentSnapshots snapshots, WorkspacePublications publications) {
        this.snapshots = snapshots;
        this.publications = publications;
    }

    @Override
    public void ensureReady(WorkspaceId workspace) {
        snapshots.ensureReady(workspace);
    }

    @Override
    public PublicContentSnapshot refresh(WorkspaceId workspace) {
        snapshots.refresh(workspace);
        return current(workspace);
    }

    @Override
    public PublicContentSnapshot current(WorkspaceId workspace) {
        return withCurrent(workspace, Function.identity());
    }

    @Override
    public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> action) {
        try {
            publications.requireEnabled(workspace);
            T result = snapshots.withCurrent(workspace, action);
            publications.requireEnabled(workspace);
            return result;
        } catch (PublicationUnavailableException unavailable) {
            throw new ContentRepositoryException("Workspace website is unavailable", unavailable);
        }
    }
}
