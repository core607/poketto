package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.PublicAlbumCover;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentSearch;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.PublicAuthorNames;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Batches retain bounded card metadata, never document bodies or publication authority. */
final class PublicDiscovery {
    private static final int MAX_BATCHES = 256;
    private static final long MAX_TEXT_BYTES = 8L * 1024 * 1024;
    private static final int SPACES_PER_BATCH = 32;
    private static final int CARDS_PER_SPACE = DiscoverySelection.LIMIT;
    private static final int PAGE_SIZE = 6;
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final AssetService assets;
    private final Clock clock;
    private final Semaphore building = new Semaphore(2);
    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private long textBytes;

    PublicDiscovery(
            WorkspacePublications publications, PublicContentSnapshots snapshots, AssetService assets, Clock clock) {
        this.publications = publications;
        this.snapshots = snapshots;
        this.assets = assets;
        this.clock = clock;
    }

    Page page(String id, String afterBatch, int offset, String tag) {
        if (offset < 0 || offset > SPACES_PER_BATCH * CARDS_PER_SPACE || offset % PAGE_SIZE != 0) {
            throw new IllegalArgumentException("Discovery offset is outside the batch page bounds");
        }
        if (id != null && afterBatch != null) {
            throw new IllegalArgumentException("Choose either an existing batch or a new batch");
        }
        if (id == null && offset != 0) {
            throw new IllegalArgumentException("A discovery offset requires an existing batch");
        }
        String requestedTag = tag == null ? null : new DocumentSearch("", tag.strip(), null, null, 0, PAGE_SIZE).tag();
        Batch batch = id == null ? create(afterBatch, requestedTag) : lookup(id);
        requireTag(batch, requestedTag);
        int end = Math.min(batch.entries().size(), offset + PAGE_SIZE);
        var spaces = new LinkedHashMap<WorkspaceId, List<Entry>>();
        for (int index = offset; index < end; index++) {
            Entry entry = batch.entries().get(index);
            spaces.computeIfAbsent(entry.workspace(), ignored -> new ArrayList<>())
                    .add(entry);
        }
        var visible = new HashMap<Entry, Card>();
        spaces.values().forEach(entries -> visible.putAll(visibleCards(entries)));
        var cards = new ArrayList<Card>();
        for (int index = offset; index < end; index++) {
            Card card = visible.get(batch.entries().get(index));
            if (card != null) {
                cards.add(card);
            }
        }
        return new Page(
                batch.id(),
                batch.tag(),
                batch.expiresAt(),
                List.copyOf(cards),
                offset,
                PAGE_SIZE,
                end < batch.entries().size() ? end : null,
                offset > 0 ? offset - PAGE_SIZE : null);
    }

    private Batch create(String afterBatch, String requestedTag) {
        if (!building.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Discovery is busy");
        }
        try {
            Batch previous = afterBatch == null ? null : lookup(afterBatch);
            if (previous != null) {
                requireTag(previous, requestedTag);
            }
            String tag = previous != null ? previous.tag() : requestedTag == null ? "" : requestedTag;
            Optional<WorkspaceId> after = previous == null ? Optional.empty() : previous.nextSpace();
            List<WorkspacePublications.Publication> spaces = publications.publishedAfter(after, SPACES_PER_BATCH + 1);
            if (spaces.isEmpty() && after.isPresent()) {
                spaces = publications.publishedAfter(Optional.empty(), SPACES_PER_BATCH + 1);
            }
            var random = new Random();
            var entries = new ArrayList<Entry>();
            spaces.stream().limit(SPACES_PER_BATCH).forEach(space -> entries.addAll(sample(space, tag, random)));
            Collections.shuffle(entries, random);
            Optional<WorkspaceId> next = spaces.size() > SPACES_PER_BATCH
                    ? Optional.of(spaces.get(SPACES_PER_BATCH - 1).workspaceId())
                    : Optional.empty();
            var batch = new Batch(
                    UUID.randomUUID().toString(), tag, clock.instant().plus(LIFETIME), List.copyOf(entries), next);
            remember(batch);
            return batch;
        } finally {
            building.release();
        }
    }

    private static void requireTag(Batch batch, String requestedTag) {
        if (requestedTag != null && !batch.tag().equals(requestedTag)) {
            throw new IllegalArgumentException("Changing the discovery tag requires a new batch");
        }
    }

    private List<Entry> sample(WorkspacePublications.Publication space, String tag, Random random) {
        try {
            return snapshots.withCurrent(space.workspaceId(), snapshot -> sample(space, snapshot, tag, random));
        } catch (ContentRepositoryException unavailable) {
            return List.of();
        }
    }

    private static List<Entry> sample(
            WorkspacePublications.Publication space, PublicContentSnapshot snapshot, String tag, Random random) {
        List<PublicArticle> selected = DiscoverySelection.select(snapshot.articles(), tag, random);
        var search = new DocumentSearch("", "", null, null, 0, CARDS_PER_SPACE);
        return selected.stream()
                .map(article -> new Entry(
                        space.workspaceId(),
                        snapshot.commit().orElse(""),
                        new Card(
                                space.slug(),
                                space.displayName(),
                                article.route(),
                                article.title(),
                                search.snippet(article.title(), article.body()),
                                article.tags().stream().limit(3).toList(),
                                article.createdAt(),
                                article.folderPage(),
                                article.publicAuthor(),
                                false,
                                collectionLanding(snapshot, article),
                                null)))
                .toList();
    }

    private static boolean collectionLanding(PublicContentSnapshot snapshot, PublicArticle article) {
        var navigation = snapshot.collections().forArticle(article.route());
        return article.folderPage()
                && navigation.available()
                && !navigation.entries().isEmpty();
    }

    private Map<Entry, Card> visibleCards(List<Entry> entries) {
        try {
            Entry first = entries.getFirst();
            PublicContentSnapshot selected = snapshots.withCurrent(first.workspace(), snapshot -> snapshot);
            var articles = new LinkedHashMap<Entry, PublicArticle>();
            for (Entry entry : entries) {
                currentArticle(selected, entry).ifPresent(article -> articles.put(entry, article));
            }
            // Source I/O and image admission must not run under the public snapshot installation lock.
            Map<String, PublicAlbumCover> covers = assets.publicAlbumCovers(
                    selected,
                    articles.values().stream().filter(PublicArticle::folderPage).toList());
            return snapshots.withCurrent(first.workspace(), snapshot -> {
                var publication = publications
                        .findPublished(first.card().space())
                        .filter(space -> space.workspaceId().equals(first.workspace()));
                if (publication.isEmpty()) {
                    return Map.of();
                }
                var cards = new HashMap<Entry, Card>();
                for (var entry : articles.entrySet()) {
                    PublicArticle expected = entry.getValue();
                    PublicAlbumCover cover =
                            expected.folderPage() ? covers.get(expected.route()) : new PublicAlbumCover(false, null);
                    if (cover != null
                            && currentArticle(snapshot, entry.getKey())
                                    .filter(expected::equals)
                                    .isPresent()) {
                        cards.put(
                                entry.getKey(),
                                entry.getKey()
                                        .card()
                                        .withPresentation(
                                                publication.orElseThrow().authorName(), cover));
                    }
                }
                return cards;
            });
        } catch (ContentRepositoryException unavailable) {
            return Map.of();
        }
    }

    private static Optional<PublicArticle> currentArticle(PublicContentSnapshot snapshot, Entry entry) {
        if (!snapshot.commit().orElse("").equals(entry.commit())) {
            return Optional.empty();
        }
        return snapshot.articles().stream()
                .filter(article -> article.route().equals(entry.card().route()))
                .findFirst();
    }

    private synchronized void remember(Batch batch) {
        expire();
        long bytes = weight(batch);
        if (bytes > MAX_TEXT_BYTES) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Discovery batch exceeds its bounds");
        }
        while (batches.size() >= MAX_BATCHES || textBytes + bytes > MAX_TEXT_BYTES) {
            String oldest = batches.keySet().iterator().next();
            textBytes -= weight(batches.remove(oldest));
        }
        batches.put(batch.id(), batch);
        textBytes += bytes;
    }

    private synchronized Batch lookup(String id) {
        expire();
        Batch batch = batches.get(id);
        if (batch == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Discovery batch expired; start a new batch");
        }
        return batch;
    }

    private void expire() {
        Instant now = clock.instant();
        var iterator = batches.values().iterator();
        while (iterator.hasNext()) {
            Batch batch = iterator.next();
            if (!now.isBefore(batch.expiresAt())) {
                textBytes -= weight(batch);
                iterator.remove();
            }
        }
    }

    private static long weight(Batch batch) {
        return 2L * batch.tag().length()
                + batch.entries().stream()
                        .mapToLong(entry -> {
                            Card card = entry.card();
                            return 2L
                                    * (entry.commit().length()
                                            + card.space().length()
                                            + card.spaceName().length()
                                            + card.route().length()
                                            + card.title().length()
                                            + card.snippet().length()
                                            + card.authorName().length()
                                            + card.tags().stream()
                                                    .mapToInt(String::length)
                                                    .sum());
                        })
                        .sum();
    }

    private record Entry(WorkspaceId workspace, String commit, Card card) {}

    private record Batch(
            String id, String tag, Instant expiresAt, List<Entry> entries, Optional<WorkspaceId> nextSpace) {}

    record Card(
            String space,
            String spaceName,
            String route,
            String title,
            String snippet,
            List<String> tags,
            Instant createdAt,
            boolean folderPage,
            String authorName,
            boolean album,
            boolean collection,
            String cover) {
        Card withPresentation(String workspaceAuthor, PublicAlbumCover preview) {
            return new Card(
                    space,
                    spaceName,
                    route,
                    title,
                    snippet,
                    tags,
                    createdAt,
                    folderPage,
                    PublicAuthorNames.select(authorName, workspaceAuthor),
                    preview.album(),
                    collection,
                    preview.src());
        }
    }

    record Page(
            String batch,
            String tag,
            Instant expiresAt,
            List<Card> items,
            int offset,
            int limit,
            Integer nextOffset,
            Integer previousOffset) {}
}
