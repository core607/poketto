package io.github.core607.poketto.content.internal;

import org.eclipse.jgit.lib.FileMode;

/**
 * Which Git tree entries hold file bytes. Every reader and writer here asks this before opening an
 * entry as a blob, and the answer has to be the same in all of them: a tree, a symlink or a
 * submodule gitlink is not a file, and opening one as a blob would hand a caller the link target or
 * the submodule id as if it were document content.
 */
final class RepositoryBlobs {

    private RepositoryBlobs() {}

    /**
     * Whether the entry is an ordinary file. The executable bit is accepted rather than refused
     * because Git records it on files committed from a Unix checkout, and a repository that carries
     * one is not malformed.
     */
    static boolean isFile(FileMode mode) {
        return FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode);
    }
}
