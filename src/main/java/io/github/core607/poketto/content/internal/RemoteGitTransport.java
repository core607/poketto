package io.github.core607.poketto.content.internal;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

interface RemoteGitTransport {

    ObjectId fetchMain(Repository repository, RepositoryBinding binding);

    PushStatus pushMain(
            Repository repository,
            RepositoryBinding binding,
            ObjectId expectedCommit,
            ObjectId candidateCommit);

    enum PushStatus {
        UPDATED,
        CONFLICT
    }
}
