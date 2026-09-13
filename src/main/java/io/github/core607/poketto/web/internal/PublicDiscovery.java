package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.DocumentSearch;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final int CARDS_PER_SPACE = 4;
    private static final int PAGE_SIZE = 6;
    private static final Duration LIFETIME = Duration.ofMinutes(30);
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final Clock clock;
    private final Semaphore building = new Semaphore(2);
    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private long textBytes;

    PublicDiscovery(WorkspacePublications publications, PublicContentSnapshots snapshots, Clock clock) {
        this.publications = publications;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    Page page(String id, String afterBatch, int offset) {
        if (offset < 0 || offset > SPACES_PER_BATCH * CARDS_PER_SPACE || offset % PAGE_SIZE != 0) {
            throw new IllegalArgumentException("Discovery offset is outside the batch page bounds");
        }
        if (id != null && afterBatch != null) {
            throw new IllegalArgumentException("Choose either an existing batch or a new batch");
        }
        if (id == null && offset != 0) {
            throw new IllegalArgumentException("A discovery offset requires an existing batch");
        }
        Batch batch = id == null ? create(afterBatch) : lookup(id);
        int end = Math.min(batch.entries().size(), offset + PAGE_SIZE);
        var cards = new ArrayList<Card>();
        for (int index = offset; index < end; index++) {
            Entry entry = batch.entries().get(index);
            if (visible(entry)) {
                cards.add(entry.card());
            }
        }
        return new Page(
                batch.id(),
                batch.expiresAt(),
                List.copyOf(cards),
                offset,
                PAGE_SIZE,
                end < batch.entries().size() ? end : null,
                offset > 0 ? offset - PAGE_SIZE : null);
    }

    private Batch create(String afterBatch) {
        if (!building.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Discovery is busy");
        }
        try {
            Optional<WorkspaceId> after =
                    afterBatch == null ? Optional.empty() : lookup(afterBatch).nextSpace();
            List<WorkspacePublications.Publication> spaces = publications.publishedAfter(after, SPACES_PER_BATCH + 1);
            if (spaces.isEmpty() && after.isPresent()) {
                spaces = publications.publishedAfter(Optional.empty(), SPACES_PER_BATCH + 1);
            }
            var random = new Random();
            var entries = new ArrayList<Entry>();
            spaces.stream().limit(SPACES_PER_BATCH).forEach(space -> entries.addAll(sample(space, random)));
            Collections.shuffle(entries, random);
            Optional<WorkspaceId> next = spaces.size() > SPACES_PER_BATCH
                    ? Optional.of(spaces.get(SPACES_PER_BATCH - 1).workspaceId())
                    : Optional.empty();
            var batch =
                    new Batch(UUID.randomUUID().toString(), clock.instant().plus(LIFETIME), List.copyOf(entries), next);
            remember(batch);
            return batch;
        } finally {
            building.release();
        }
    }

    private List<Entry> sample(WorkspacePublications.Publication space, Random random) {
        try {
            return snapshots.withCurrent(space.workspaceId(), snapshot -> sample(space, snapshot, random));
        } catch (ContentRepositoryException unavailable) {
            return List.of();
        }
    }

    private static List<Entry> sample(
            WorkspacePublications.Publication space, PublicContentSnapshot snapshot, Random random) {
        var selected = new ArrayList<PublicArticle>();
        int seen = 0;
        for (PublicArticle article : snapshot.articles()) {
            int index = random.nextInt(++seen);
            if (selected.size() < CARDS_PER_SPACE) {
                selected.add(article);
            } else if (index < CARDS_PER_SPACE) {
                selected.set(index, article);
            }
        }
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
                                article.folderPage())))
                .toList();
    }

    private boolean visible(Entry entry) {
        try {
            return snapshots.withCurrent(
                    entry.workspace(),
                    snapshot -> snapshot.commit().orElse("").equals(entry.commit())
                            && snapshot.articles().stream()
                                    .anyMatch(article ->
                                            article.route().equals(entry.card().route())));
        } catch (ContentRepositoryException unavailable) {
            return false;
        }
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
        return batch.entries().stream()
                .mapToLong(entry -> {
                    Card card = entry.card();
                    return 2L
                            * (entry.commit().length()
                                    + card.space().length()
                                    + card.spaceName().length()
                                    + card.route().length()
                                    + card.title().length()
                                    + card.snippet().length()
                                    + card.tags().stream()
                                            .mapToInt(String::length)
                                            .sum());
                })
                .sum();
    }

    private record Entry(WorkspaceId workspace, String commit, Card card) {}

    private record Batch(String id, Instant expiresAt, List<Entry> entries, Optional<WorkspaceId> nextSpace) {}

    record Card(
            String space,
            String spaceName,
            String route,
            String title,
            String snippet,
            List<String> tags,
            Instant createdAt,
            boolean folderPage) {}

    record Page(
            String batch,
            Instant expiresAt,
            List<Card> items,
            int offset,
            int limit,
            Integer nextOffset,
            Integer previousOffset) {}
}
