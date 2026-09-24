package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.stream.Stream;

/**
 * Adds the content template's absent files to a connected repository as one commit. A file that
 * exists is never overwritten, existing content is never moved, and publication stays disabled
 * until the owner enables it. An empty repository receives the files as its root commit.
 */
public interface RepositoryInitialization {
    /** The base template files, in the order they are added. Every template includes them. */
    List<String> FILES = List.of("AGENTS.md", "private/AGENTS.md", "public/AGENTS.md", ".poketto/publishing.yaml");

    /**
     * A named set of folders and guides added on top of the base files, shipped under
     * {@code content-template/sets/<name>/}. Like the base, a set only creates absent files.
     */
    enum Template {
        GENERAL("general", List.of()),
        JOURNAL("journal", List.of("private/journal/AGENTS.md", "public/journal/AGENTS.md")),
        READING_NOTES("reading-notes", List.of("private/reading/AGENTS.md", "public/reading/AGENTS.md")),
        ALBUMS("albums", List.of("private/albums/AGENTS.md", "public/albums/AGENTS.md")),
        DIGEST("digest", List.of("private/digest/AGENTS.md", "public/digest/AGENTS.md"));

        private final String slug;
        private final List<String> extras;

        Template(String slug, List<String> extras) {
            this.slug = slug;
            this.extras = extras;
        }

        public String slug() {
            return slug;
        }

        /** Files this set adds beyond the base, as repository paths. */
        public List<String> extras() {
            return extras;
        }

        /** The base files followed by this set's own. */
        public List<String> files() {
            return Stream.concat(FILES.stream(), extras.stream()).toList();
        }

        public static Template parse(String slug) {
            if (slug == null || slug.isEmpty()) {
                return GENERAL;
            }
            for (Template template : values()) {
                if (template.slug.equals(slug)) {
                    return template;
                }
            }
            throw new IllegalArgumentException("Unknown content template: " + slug);
        }
    }

    /** What adding this template would add now. The caller must be a space owner. */
    Status status(AuthPrincipal actor, WorkspaceId workspace, Template template);

    /** Adds the absent files of the base and this template; existing files are never changed or moved. */
    Outcome apply(AuthPrincipal actor, WorkspaceId workspace, Template template);

    /** Checks an external operation lease immediately before a remote ref advance. Already complete templates do not write. */
    default Outcome apply(AuthPrincipal actor, WorkspaceId workspace, Template template, Runnable beforeWrite) {
        throw new UnsupportedOperationException("Guarded repository initialization is unavailable");
    }

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
