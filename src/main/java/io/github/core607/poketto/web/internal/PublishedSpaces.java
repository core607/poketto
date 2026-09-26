package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Every published space, read page by page in catalogue order. */
final class PublishedSpaces {
    private static final int PAGE_SIZE = 100;

    private PublishedSpaces() {}

    /** Runs {@code beforePage} before each read and throws {@code overCap}'s exception past {@code cap} spaces. */
    static List<WorkspacePublications.Publication> all(
            WorkspacePublications publications,
            int cap,
            Runnable beforePage,
            Supplier<? extends RuntimeException> overCap) {
        var result = new ArrayList<WorkspacePublications.Publication>();
        Optional<WorkspaceId> after = Optional.empty();
        while (true) {
            beforePage.run();
            List<WorkspacePublications.Publication> page = publications.publishedAfter(after, PAGE_SIZE);
            if (result.size() + page.size() > cap) {
                throw overCap.get();
            }
            result.addAll(page);
            if (page.size() < PAGE_SIZE) {
                return List.copyOf(result);
            }
            after = Optional.of(page.getLast().workspaceId());
        }
    }
}
