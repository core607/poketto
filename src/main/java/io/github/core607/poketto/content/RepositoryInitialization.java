package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;

/**
 * Adds the content template's absent files to a connected repository as one commit. A file that
 * exists is never overwritten, existing content is never moved, and publication stays disabled
 * until the owner enables it. An empty repository receives the files as its root commit.
 */
public interface RepositoryInitialization {
    /** The template files, in the order they are added. */
    List<String> FILES = List.of("AGENTS.md", "private/AGENTS.md", "public/AGENTS.md", ".poketto/publishing.yaml");

    /** What initialization would add now. The caller must be a space owner. */
    Status status(AuthPrincipal actor, WorkspaceId workspace);

    /** Adds the absent files on current main, or as the root commit; a repository that has them all is left alone. */
    Outcome apply(AuthPrincipal actor, WorkspaceId workspace);

    /** An empty repository has no commit, and then every template file is missing. */
    record Status(boolean repositoryEmpty, List<String> missingFiles) {
        public Status {
            missingFiles = List.copyOf(missingFiles);
        }
    }

    /** The commit that holds every template file after the call, and the files this call added. */
    record Outcome(String commit, List<String> addedFiles) {
        public Outcome {
            addedFiles = List.copyOf(addedFiles);
        }
    }
}
