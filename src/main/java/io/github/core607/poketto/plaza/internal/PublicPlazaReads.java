package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentSearch;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.plaza.PlazaException;
import io.github.core607.poketto.workspace.PublicAuthorNames;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.springframework.web.util.UriUtils;

/** Current public sources only. No repository executor, remote fetch or historical read is available. */
final class PublicPlazaReads {
    private static final int PAGE = 10;
    private static final int TEXT_PAGE = 8192;
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final Limits limits;
    private final LongSupplier nanos;
    private final String origin;
    private final Semaphore scans = new Semaphore(2);

    PublicPlazaReads(WorkspacePublications publications, PublicContentSnapshots snapshots) {
        this(publications, snapshots, "");
    }

    PublicPlazaReads(WorkspacePublications publications, PublicContentSnapshots snapshots, String origin) {
        this(
                publications,
                snapshots,
                new Limits(256, 100_000, 64L * 1024 * 1024, Duration.ofSeconds(5)),
                System::nanoTime,
                origin);
    }

    PublicPlazaReads(
            WorkspacePublications publications, PublicContentSnapshots snapshots, Limits limits, LongSupplier nanos) {
        this(publications, snapshots, limits, nanos, "");
    }

    private PublicPlazaReads(
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            Limits limits,
            LongSupplier nanos,
            String origin) {
        this.publications = publications;
        this.snapshots = snapshots;
        this.limits = limits;
        this.nanos = nanos;
        this.origin = origin(origin);
    }

    <T> T catalogue(Function<List<Source>, T> operation) {
        if (!scans.tryAcquire()) {
            throw new PlazaException("BUSY", "The street is crowded. Try again shortly.", "look");
        }
        try {
            var budget = new Budget(limits, nanos);
            List<WorkspacePublications.Publication> spaces = spaces(budget);
            var sources = new ArrayList<Source>();
            for (WorkspacePublications.Publication space : spaces) {
                budget.check();
                PublicContentSnapshot snapshot = snapshots.current(space.workspaceId());
                for (PublicArticle article : snapshot.articles()) {
                    budget.read(article);
                }
                sources.add(new Source(space, snapshot));
            }
            T result = operation.apply(List.copyOf(sources));
            validate(sources, spaces, budget);
            return result;
        } finally {
            scans.release();
        }
    }

    Page search(String query, String tag, int offset) {
        var search = new DocumentSearch(query, tag, null, null, offset, PAGE);
        return catalogue(sources -> search(sources, search));
    }

    Page search(List<Source> sources, DocumentSearch search) {
        List<Card> matches = sources.stream()
                .flatMap(source -> source.snapshot().articles().stream()
                        .filter(article ->
                                search.matches(article.title(), article.body(), article.tags(), article.createdAt()))
                        .map(article -> card(source.publication(), article, search)))
                .sorted(Comparator.comparing(Card::createdAt).reversed().thenComparing(Card::reference))
                .toList();
        int end = Math.min(matches.size(), search.offset() + search.limit());
        List<Card> page = search.offset() >= matches.size() ? List.of() : matches.subList(search.offset(), end);
        boolean more = end < matches.size();
        boolean refineQuery = more && end > DocumentSearch.MAX_OFFSET;
        return new Page(page, matches.size(), more && !refineQuery ? end : null, refineQuery);
    }

    Reading read(String reference, int offset) {
        int split = reference.indexOf('/');
        if (split < 1 || reference.length() > 2304) {
            throw missing();
        }
        String slug = reference.substring(0, split);
        String route = reference.substring(split);
        WorkspacePublications.Publication publication =
                publications.findPublished(slug).orElseThrow(PublicPlazaReads::missing);
        return snapshots.withCurrent(publication.workspaceId(), snapshot -> {
            publications.requireEnabled(publication.workspaceId());
            PublicArticle article = snapshot.articles().stream()
                    .filter(item -> item.route().equals(route))
                    .findFirst()
                    .orElseThrow(PublicPlazaReads::missing);
            if (offset < 0 || offset > article.body().length()) {
                throw new PlazaException("INVALID_OFFSET", "Use the reading's nextOffset.", "read " + quote(reference));
            }
            int start = boundary(article.body(), offset);
            int end = boundary(article.body(), Math.min(article.body().length(), start + TEXT_PAGE));
            String text = article.body().substring(start, end);
            publications.requireEnabled(publication.workspaceId());
            return new Reading(
                    card(publication, article, new DocumentSearch("", "", null, null, 0, PAGE)),
                    text,
                    start,
                    end < article.body().length() ? end : null);
        });
    }

    Page mirror(WorkspaceId workspace, int offset) {
        WorkspacePublications.Publication publication = publications.settings(workspace);
        if (!publication.publiclyEnabled()) {
            return new Page(List.of(), 0, null, false);
        }
        return snapshots.withCurrent(workspace, snapshot -> {
            var budget = new Budget(limits, nanos);
            for (PublicArticle article : snapshot.articles()) {
                budget.read(article);
            }
            Page result = search(
                    List.of(new Source(publication, snapshot)), new DocumentSearch("", "", null, null, offset, PAGE));
            publications.requireEnabled(workspace);
            if (!publication.equals(publications.settings(workspace))) {
                throw changed();
            }
            budget.check();
            return result;
        });
    }

    private static int boundary(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset))
                ? offset - 1
                : offset;
    }

    private Card card(WorkspacePublications.Publication publication, PublicArticle article, DocumentSearch search) {
        String reference = publication.slug() + article.route();
        return new Card(
                reference,
                origin
                        + UriUtils.encodePath(
                                "/s/" + publication.slug() + "/read" + article.route(), StandardCharsets.UTF_8),
                article.title(),
                search.snippet(article.title(), article.body()),
                article.tags(),
                article.createdAt(),
                PublicAuthorNames.select(article.publicAuthor(), publication.authorName()));
    }

    private List<WorkspacePublications.Publication> spaces(Budget budget) {
        var result = new ArrayList<WorkspacePublications.Publication>();
        Optional<WorkspaceId> after = Optional.empty();
        while (true) {
            budget.check();
            List<WorkspacePublications.Publication> page = publications.publishedAfter(after, 100);
            if (result.size() + page.size() > limits.spaces()) {
                throw capacity();
            }
            result.addAll(page);
            if (page.size() < 100) {
                return List.copyOf(result);
            }
            Optional<WorkspaceId> next = Optional.of(page.getLast().workspaceId());
            if (next.equals(after)) {
                throw capacity();
            }
            after = next;
        }
    }

    private void validate(List<Source> sources, List<WorkspacePublications.Publication> before, Budget budget) {
        if (!before.equals(spaces(budget))) {
            throw changed();
        }
        for (Source source : sources) {
            budget.check();
            snapshots.withCurrent(source.publication().workspaceId(), current -> {
                publications.requireEnabled(source.publication().workspaceId());
                if (!current.commit().equals(source.snapshot().commit())
                        || current.articles().size()
                                != source.snapshot().articles().size()) {
                    throw changed();
                }
                return null;
            });
        }
        budget.check();
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String origin(String value) {
        if (value.isEmpty()) {
            return "";
        }
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Plaza public URL must be an HTTP(S) origin");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Plaza public URL cannot contain credentials, a query or a fragment");
        }
        if (!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
            throw new IllegalArgumentException("Plaza public URL cannot contain a path");
        }
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static PlazaException missing() {
        return new PlazaException("NOT_FOUND", "That pocket is not open to the street.", "look");
    }

    private static ContentRepositoryException changed() {
        return new ContentRepositoryException("Public plaza changed; repeat the read");
    }

    private static PlazaException capacity() {
        return new PlazaException(
                "CAPACITY",
                "The complete street exceeds scan capacity. Read a known article or inspect your own space.",
                "--help");
    }

    record Source(WorkspacePublications.Publication publication, PublicContentSnapshot snapshot) {}

    record Card(
            String reference,
            String url,
            String title,
            String snippet,
            List<String> tags,
            Instant createdAt,
            String author) {}

    record Page(List<Card> items, int total, Integer nextOffset, boolean refineQuery) {}

    record Reading(Card article, String text, int offset, Integer nextOffset) {}

    record Limits(int spaces, int documents, long characters, Duration time) {}

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
            check();
            characters += (long) article.body().length()
                    + article.title().length()
                    + article.route().length()
                    + article.publicAuthor().length();
            for (String tag : article.tags()) {
                characters += tag.length();
            }
            if (++documents > limits.documents() || characters > limits.characters()) {
                throw capacity();
            }
        }

        private void check() {
            if (nanos.getAsLong() - started >= limits.time().toNanos()) {
                throw capacity();
            }
        }
    }
}
