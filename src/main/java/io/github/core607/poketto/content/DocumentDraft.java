package io.github.core607.poketto.content;

import java.util.List;
import java.util.Objects;

/**
 * Complete caller-supplied document state for a create or an update. An update replaces every
 * field it carries, so a caller that omits one erases it rather than leaving it untouched.
 */
public record DocumentDraft(String repositoryPath, String title, List<String> tags, String body) {

    public DocumentDraft {
        Objects.requireNonNull(repositoryPath, "document repository path must not be null");
        Objects.requireNonNull(title, "document title must not be null");
        Objects.requireNonNull(tags, "document tags must not be null");
        Objects.requireNonNull(body, "document body must not be null");
        tags = List.copyOf(tags);
    }
}
