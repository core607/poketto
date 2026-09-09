package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Collection;

/** Validates workspace ownership and immutable metadata before an authorized Git index write. */
@FunctionalInterface
public interface RepositoryMediaValidator {
    void validate(WorkspaceId workspace, Collection<RepositoryMediaIndex.Media> originals);
}
