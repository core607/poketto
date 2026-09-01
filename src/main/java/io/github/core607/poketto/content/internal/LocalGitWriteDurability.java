package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

/** Test-friendly local durability used when the write service is constructed without replication. */
final class LocalGitWriteDurability implements GitWriteDurability {

    @Override
    public void beforeWrite(WorkspaceId workspaceId, Repository repository) {
        // No remote acknowledgement is required.
    }

    @Override
    public GitCommitOutcome commit(
            WorkspaceId workspaceId,
            Repository repository,
            PersonIdent author,
            String message) {
        try {
            ObjectId commit = Git.wrap(repository)
                    .commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setSign(false)
                    .setMessage(message)
                    .call()
                    .getId();
            return new GitCommitOutcome(commit, false);
        } catch (GitAPIException exception) {
            throw new ContentRepositoryException("document cannot be committed", exception);
        }
    }

    @Override
    public boolean isMirrored(Repository repository, ObjectId commit) {
        return false;
    }
}
