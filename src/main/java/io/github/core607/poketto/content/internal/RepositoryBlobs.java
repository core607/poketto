package io.github.core607.poketto.content.internal;

import org.eclipse.jgit.lib.FileMode;

/**
 * Which Git tree entries hold file bytes. Readers ask one of these two before opening an entry as a
 * blob, because a tree, a symlink or a submodule gitlink is not a file, and opening one as a blob
 * would hand a caller the link target or the submodule id as if it were content.
 */
final class RepositoryBlobs {

    private RepositoryBlobs() {}

    /**
     * Whether the entry is an ordinary file. The executable bit is accepted rather than refused
     * because Git records it on files committed from a Unix checkout, and a repository that carries
     * one is not malformed. This is the question asked of anything an author may have written.
     */
    static boolean isFile(FileMode mode) {
        return FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode);
    }

    /**
     * The stricter question: an ordinary file with no executable bit. Every reader of the media
     * index asks this one instead, and refuses the tree when the answer is no. This application
     * writes that index itself and never sets the bit, so for a tree it produced the two questions
     * have the same answer; they differ only for a tree that arrived from somewhere else.
     */
    static boolean isPlainFile(FileMode mode) {
        return FileMode.REGULAR_FILE.equals(mode);
    }
}
