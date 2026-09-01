package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

interface GitWriteDurability {

    void beforeWrite(WorkspaceId workspaceId, Repository repository);

    GitCommitOutcome commit(
            WorkspaceId workspaceId,
            Repository repository,
            PersonIdent author,
            String message);

    boolean isMirrored(Repository repository, org.eclipse.jgit.lib.ObjectId commit);
}
