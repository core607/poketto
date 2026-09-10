package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.util.*;

/** Translates visible public paths using the host-owned projection, never the command's filesystem. */
final class SessionExportSelection {
    private SessionExportSelection() {}

    static List<String> resolve(List<String> selections, RepositorySnapshotExports.PublicExport projection) {
        if (selections.isEmpty() || selections.size() > 128) throw new IllegalArgumentException();
        var normalized = new LinkedHashSet<String>();
        for (String selection : selections) {
            String path = selection.equals(".") ? "" : selection;
            if (!path.isEmpty()) validate(path);
            if (!normalized.add(path)) throw new IllegalArgumentException();
        }
        if (projection == null) return List.copyOf(normalized);
        var sources = new TreeSet<String>();
        for (String selection : normalized) {
            boolean matched = false;
            for (var entry : projection.sourcePaths().entrySet()) {
                String path = entry.getKey();
                if (selection.isEmpty() || path.equals(selection) || path.startsWith(selection + "/")) {
                    validate(entry.getValue());
                    sources.add(entry.getValue());
                    matched = true;
                    if (sources.size() > 128) throw new IllegalArgumentException();
                }
            }
            if (!matched) throw new IllegalArgumentException();
        }
        return List.copyOf(sources);
    }

    static void validate(String path) {
        RepositoryPaths.validate(path);
        for (String part : path.split("/"))
            if (part.startsWith(".") || part.equalsIgnoreCase("AGENTS.md")) throw new IllegalArgumentException();
    }
}
