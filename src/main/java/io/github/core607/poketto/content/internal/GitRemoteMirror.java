package io.github.core607.poketto.content.internal;

import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** Remote transport seam kept small enough to test failure and crash boundaries deterministically. */
interface GitRemoteMirror {

    /** Reports whether the repository names a remote to replicate to; without one, replication is off. */
    default boolean configured(Repository repository) {
        return true;
    }

    Optional<ObjectId> main(Repository repository);

    void pushMain(Repository repository, ObjectId candidate, Optional<ObjectId> expectedRemote);
}
