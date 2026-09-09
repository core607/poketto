package io.github.core607.poketto.content.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/** Host-prepared changes at a checked base; copied objects avoid text-size bounds on directory moves. */
record RepositoryCandidateChanges(
        Map<String, byte[]> replacements, Map<String, ObjectEntry> copies, Set<String> deletions, boolean structural) {
    Set<String> paths() {
        Set<String> result = new HashSet<>(replacements.keySet());
        result.addAll(copies.keySet());
        result.addAll(deletions);
        return result;
    }

    record ObjectEntry(ObjectId objectId, FileMode mode) {}
}
