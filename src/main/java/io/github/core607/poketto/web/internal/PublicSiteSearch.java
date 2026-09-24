package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentSearch;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Complete, bounded searches over current website snapshots; no request fetches remote Git. */
final class PublicSiteSearch {
    private static final Comparator<Match> ORDER = Comparator.comparing(
                    (Match match) -> match.article().createdAt())
            .reversed()
            .thenComparing(match -> match.source().publication().slug())
            .thenComparing(match -> match.article().route());
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final Limits limits;
    private final LongSupplier nanos;
    private final Semaphore searches = new Semaphore(2);

    PublicSiteSearch(WorkspacePublications publications, PublicContentSnapshots snapshots) {
        this(
                publications,
                snapshots,
                new Limits(256, 100_000, 64L * 1024 * 1024, Duration.ofSeconds(5)),
                System::nanoTime);
    }

    PublicSiteSearch(
            WorkspacePublications publications, PublicContentSnapshots snapshots, Limits limits, LongSupplier nanos) {
        this.publications = publications;
        this.snapshots = snapshots;
        this.limits = limits;
        this.nanos = nanos;
    }

    Page search(String query, int offset, int limit) {
        var search = new DocumentSearch(query, "", null, null, offset, limit);
        if (!searches.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "public-search-busy");
        }
        try {
            return search(search, new Budget(limits, nanos));
        } finally {
            searches.release();
        }
    }

    private Page search(DocumentSearch search, Budget budget) {
        List<WorkspacePublications.Publication> spaces = catalogue(budget);
        var sources = new ArrayList<Source>();
        var selected = new PriorityQueue<Match>(ORDER.reversed());
        int total = 0;
        for (var space : spaces) {
            budget.checkTime();
            var source = new Source(space, snapshots.current(space.workspaceId()));
            sources.add(source);
            for (PublicArticle article : source.snapshot().articles()) {
                budget.read(article);
                if (search.matches(article.title(), article.body(), article.tags(), article.createdAt())) {
                    total++;
                    select(selected, new Match(source, article), search.offset() + search.limit());
                }
            }
        }
        List<Result> items = selected.stream()
                .sorted(ORDER)
                .skip(search.offset())
                .map(match -> result(match, search, budget))
                .toList();
        validate(sources, spaces, budget);
        return new Page(items, total, search.offset(), search.limit());
    }

    private static void select(PriorityQueue<Match> selected, Match match, int count) {
        if (selected.size() < count) {
            selected.add(match);
        } else if (ORDER.compare(match, selected.element()) < 0) {
            selected.remove();
            selected.add(match);
        }
    }

    private static Result result(Match match, DocumentSearch search, Budget budget) {
        budget.checkTime();
        PublicArticle article = match.article();
        var publication = match.source().publication();
        return new Result(
                publication.slug(),
                publication.displayName(),
                PublicDocumentSummary.of(
                        article, search.snippet(article.title(), article.body()), publication.authorName()));
    }

    private List<WorkspacePublications.Publication> catalogue(Budget budget) {
        var result = new ArrayList<WorkspacePublications.Publication>();
        Optional<WorkspaceId> after = Optional.empty();
        while (true) {
            budget.checkTime();
            List<WorkspacePublications.Publication> page = publications.publishedAfter(after, 100);
            if (result.size() + page.size() > limits.spaces()) {
                throw capacity();
            }
            result.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(result);
            }
            after = Optional.of(page.getLast().workspaceId());
        }
    }

    private void validate(List<Source> sources, List<WorkspacePublications.Publication> spaces, Budget budget) {
        if (!catalogue(budget).equals(spaces)) {
            throw changed();
        }
        for (Source source : sources) {
            budget.checkTime();
            snapshots.withCurrent(source.publication().workspaceId(), current -> {
                // Within one commit the public set only grows as scheduled articles fall due.
                if (!current.commit().equals(source.snapshot().commit())
                        || current.articles().size()
                                != source.snapshot().articles().size()) {
                    throw changed();
                }
                return null;
            });
        }
        budget.checkTime();
    }

    private static ContentRepositoryException changed() {
        return new ContentRepositoryException("Public search corpus changed; repeat the query");
    }

    private static ResponseStatusException capacity() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "public-search-capacity");
    }

    record Page(List<Result> items, int total, int offset, int limit) {}

    record Result(String space, String spaceName, PublicDocumentSummary document) {}

    record Limits(int spaces, int documents, long sourceCharacters, Duration time) {
        Limits {
            if (spaces < 1) {
                throw new IllegalArgumentException("Search space limit must be positive");
            }
            if (documents < 1) {
                throw new IllegalArgumentException("Search document limit must be positive");
            }
            if (sourceCharacters < 1) {
                throw new IllegalArgumentException("Search source limit must be positive");
            }
            if (time.isNegative() || time.isZero()) {
                throw new IllegalArgumentException("Search deadline must be positive");
            }
        }
    }

    private record Source(WorkspacePublications.Publication publication, PublicContentSnapshot snapshot) {}

    private record Match(Source source, PublicArticle article) {}

    private static final class Budget {
        private final Limits limits;
        private final LongSupplier nanos;
        private final long started;
        private int documents;
        private long characters;

        private Budget(Limits limits, LongSupplier nanos) {
            this.limits = limits;
            this.nanos = nanos;
            started = nanos.getAsLong();
        }

        private void read(PublicArticle article) {
            checkTime();
            if (++documents > limits.documents()) {
                throw capacity();
            }
            characters += article.body().length();
            if (characters > limits.sourceCharacters()) {
                throw capacity();
            }
        }

        private void checkTime() {
            if (nanos.getAsLong() - started > limits.time().toNanos()) {
                throw capacity();
            }
        }
    }
}
