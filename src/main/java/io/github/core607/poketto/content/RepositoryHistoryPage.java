package io.github.core607.poketto.content;

import java.time.Instant;
import java.util.List;

/** First-parent changes to one literal path; presence does not imply that a version is readable text. */
public record RepositoryHistoryPage(String commit, String path, List<Entry> entries, Integer nextOffset) {
    public RepositoryHistoryPage {
        entries = List.copyOf(entries);
    }

    public record Entry(String commit, String subject, String author, Instant committedAt, boolean present) {}
}
