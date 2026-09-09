package io.github.core607.poketto.content;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Optional;

/** Immediate committed Git and indexed-media children in Git tree order; entries do not imply readability. */
public record RepositoryDirectoryPage(
        WorkspaceId workspaceId,
        Optional<String> commit,
        String path,
        boolean expectedAbsence,
        List<Entry> entries,
        Integer nextOffset) {
    public RepositoryDirectoryPage {
        entries = List.copyOf(entries);
    }

    public record Entry(String path, Kind kind) {}

    public enum Kind {
        FILE,
        DIRECTORY,
        SYMLINK,
        SUBMODULE,
        OTHER
    }
}
