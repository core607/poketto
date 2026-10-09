package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.games.GameRunner;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Background validation never runs in an anonymous request or while holding a snapshot guard. */
final class DefaultGameLibrary implements GameLibrary, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultGameLibrary.class);
    private static final int MAX_GAMES = 64;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;
    private final GamePackages packages;
    private final GameRunner runner;
    private final ObjectMapper json;
    private final GameChecks checks = new GameChecks();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("game-validation").factory());
    private volatile Map<Key, Entry> entries = Map.of();
    private WorkspaceId cursor;
    private WorkspaceId scanning;
    private String scannedCommit = "";
    private int offset;
    private Set<UUID> priority = Set.of();

    DefaultGameLibrary(
            WorkspacePublications publications,
            PublicContentSnapshots snapshots,
            GamePackages packages,
            GameRunner runner,
            ObjectMapper json) {
        this.publications = publications;
        this.snapshots = new WebsiteContentSnapshots(snapshots, publications);
        this.packages = packages;
        this.runner = runner;
        this.json = json;
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public Optional<Published> find(String space, UUID articleId) {
        return publications.findPublished(space).flatMap(value -> find(value.workspaceId(), articleId));
    }

    @Override
    public Optional<Published> find(WorkspaceId workspace, UUID articleId) {
        if (articleId == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(new Key(workspace, articleId));
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return snapshots.withCurrent(workspace, snapshot -> current(entry, snapshot));
        } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
            return Optional.empty();
        }
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn("Game validation cycle failed", failure);
        }
    }

    @Override
    public boolean recognizes(WorkspaceId workspace, String commit, UUID articleId) {
        if (articleId == null) {
            return false;
        }
        Entry entry = entries.get(new Key(workspace, articleId));
        return entry != null && entry.commit().equals(commit);
    }

    @Override
    public Inspection inspect(WorkspaceId workspace) {
        return snapshots.withCurrent(workspace, checks::inspect);
    }

    @Override
    public <T> T withCurrent(WorkspaceId workspace, UUID articleId, Function<Published, T> action) {
        Entry entry = entries.get(new Key(workspace, articleId));
        if (entry == null) {
            throw new GameException("GAME_UNAVAILABLE", "This article has no current validated game");
        }
        return snapshots.withCurrent(
                workspace,
                snapshot -> action.apply(current(entry, snapshot)
                        .orElseThrow(() -> new GameException(
                                "GAME_UNAVAILABLE", "The game's current publication is unavailable"))));
    }

    /** Each cycle advances one bounded article page; later articles and spaces remain reachable. */
    void refresh() {
        pruneWithdrawn();
        if (scanning == null) {
            List<WorkspacePublications.Publication> page = publications.publishedAfter(Optional.ofNullable(cursor), 1);
            if (page.isEmpty()) {
                cursor = null;
                return;
            }
            scanning = page.getFirst().workspaceId();
            offset = 0;
            scannedCommit = "";
        }
        var next = new LinkedHashMap<>(entries);
        try {
            // Blob reads take repository authority; never invert it beneath a snapshot guard.
            Batch batch = candidates(snapshots.current(scanning));
            next.entrySet()
                    .removeIf(item -> item.getKey().workspace().equals(scanning)
                            && !batch.articleIds().contains(item.getKey().articleId()));
            update(next, batch);
            offset = batch.nextOffset();
            if (offset < 0) {
                cursor = scanning;
                scanning = null;
            }
            entries = Map.copyOf(next);
        } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
            next.keySet().removeIf(key -> key.workspace().equals(scanning));
            entries = Map.copyOf(next);
            cursor = scanning;
            scanning = null;
        }
    }

    private void pruneWithdrawn() {
        var workspaces = new HashSet<>(checks.workspaces());
        entries.keySet().forEach(key -> workspaces.add(key.workspace()));
        workspaces.removeIf(workspace -> {
            try {
                publications.requireEnabled(workspace);
                return false;
            } catch (PublicationUnavailableException unavailable) {
                return true;
            }
        });
        checks.retainWorkspaces(workspaces);
        var retained = new LinkedHashMap<>(entries);
        retained.keySet().removeIf(key -> !workspaces.contains(key.workspace()));
        entries = Map.copyOf(retained);
    }

    private void update(Map<Key, Entry> next, Batch batch) {
        for (Candidate candidate : batch.candidates()) {
            var key = new Key(scanning, candidate.article().articleId());
            if (candidate.entry() == null) {
                next.remove(key);
                continue;
            }
            Entry entry = candidate.entry();
            int retained = next.entrySet().stream()
                    .filter(item -> !item.getKey().equals(key))
                    .mapToInt(item -> json.writeValueAsBytes(item.getValue().published()).length)
                    .sum();
            if (next.size() >= MAX_GAMES && !next.containsKey(key)) {
                checks.record(
                        batch.snapshot(),
                        candidate.article(),
                        entry.published().version(),
                        "GAME_CAPACITY",
                        "The game catalogue is full; validation will retry after capacity is available");
                continue;
            }
            if (retained + json.writeValueAsBytes(entry.published()).length > MAX_BYTES) {
                next.remove(key);
                checks.record(
                        batch.snapshot(),
                        candidate.article(),
                        entry.published().version(),
                        "GAME_CAPACITY",
                        "The game catalogue byte limit is full; validation will retry after capacity is available");
                continue;
            }
            if (validate(entry, batch.snapshot(), candidate.article())) {
                next.put(key, entry);
            } else {
                next.remove(key);
            }
        }
    }

    private Batch candidates(PublicContentSnapshot snapshot) {
        String commit = snapshot.commit().orElse("");
        if (!commit.equals(scannedCommit)) {
            offset = 0;
            scannedCommit = commit;
            priority = entries.keySet().stream()
                    .filter(key -> key.workspace().equals(scanning))
                    .map(Key::articleId)
                    .collect(Collectors.toSet());
        }
        List<PublicArticle> articles = snapshot.articles().stream()
                .sorted(Comparator.comparing(
                        article -> article.articleId() == null || !priority.contains(article.articleId())))
                .toList();
        int end = Math.min(articles.size(), offset + 32);
        var result = new ArrayList<Candidate>();
        for (PublicArticle article : articles.subList(Math.min(offset, end), end)) {
            result.add(new Candidate(article, candidate(snapshot, article).orElse(null)));
        }
        List<UUID> ids = snapshot.articles().stream()
                .map(PublicArticle::articleId)
                .filter(id -> id != null)
                .toList();
        return new Batch(snapshot, result, ids, end < articles.size() ? end : -1);
    }

    private Optional<Entry> candidate(PublicContentSnapshot snapshot, PublicArticle article) {
        try {
            Optional<GamePackages.Package> found = packages.find(snapshot, article);
            if (found.isEmpty()) {
                checks.forget(snapshot.workspaceId(), article.repositoryPath());
            }
            return found.map(value -> new Entry(
                    snapshot.commit().orElseThrow(),
                    new Published(
                            snapshot.workspaceId(),
                            article.articleId(),
                            article.title(),
                            value.version(),
                            value.help(),
                            value.bundle())));
        } catch (JacksonException | GameException | IllegalArgumentException invalid) {
            checks.record(
                    snapshot,
                    article,
                    null,
                    "INVALID_PACKAGE",
                    invalid instanceof GameException
                            ? invalid.getMessage()
                            : "Invalid manifest or module declaration; check protocol, article ID, paths and size limits");
            log.warn(
                    "Game package rejected in workspace {} article {} ({})",
                    snapshot.workspaceId(),
                    article.articleId(),
                    invalid.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean validate(Entry entry, PublicContentSnapshot snapshot, PublicArticle article) {
        Published value = entry.published();
        if (checks.rejected(value.workspaceId(), article.repositoryPath(), value.version())) {
            checks.record(
                    snapshot,
                    article,
                    value.version(),
                    "INVALID_GAME",
                    "Initialization or observation failed; change the rule package before another validation");
            return false;
        }
        Entry previous = entries.get(new Key(value.workspaceId(), value.articleId()));
        if (previous != null && previous.published().version().equals(value.version())) {
            checks.record(snapshot, article, value.version(), "READY", "Current package meets the game protocol");
            return true;
        }
        try {
            runner.run(
                    new GameRunner.Identity(
                            value.workspaceId().value(), value.workspaceId().value(), value.workspaceId()),
                    value.bundle(),
                    new GameRunner.Request("init", null, null, 1L));
            boolean current = snapshots.withCurrent(
                    value.workspaceId(), latest -> current(entry, latest).isPresent());
            checks.record(
                    snapshot,
                    article,
                    value.version(),
                    current ? "READY" : "GAME_UNAVAILABLE",
                    current ? "Current package meets the game protocol" : "Publication changed during validation");
            return current;
        } catch (GameException | ContentRepositoryException | PublicationUnavailableException rejected) {
            String code = rejected instanceof GameException game && game.code().equals("INVALID_GAME")
                    ? "INVALID_GAME"
                    : "GAME_UNAVAILABLE";
            checks.record(
                    snapshot,
                    article,
                    value.version(),
                    code,
                    code.equals("INVALID_GAME")
                            ? "Initialization or observation failed; check synchronous exports, state, output and execution limits"
                            : "Validation is temporarily unavailable; the background validator will retry");
            log.warn(
                    "Game initialization rejected in workspace {} article {} ({})",
                    value.workspaceId(),
                    value.articleId(),
                    rejected.getClass().getSimpleName());
            return false;
        }
    }

    private static Optional<Published> current(Entry entry, PublicContentSnapshot snapshot) {
        if (!snapshot.commit().orElse("").equals(entry.commit())) {
            return Optional.empty();
        }
        return snapshot.articles().stream()
                .filter(article -> entry.published().articleId().equals(article.articleId()))
                .findFirst()
                .map(article -> entry.published());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private record Key(WorkspaceId workspace, UUID articleId) {}

    private record Entry(String commit, Published published) {}

    private record Candidate(PublicArticle article, Entry entry) {}

    private record Batch(
            PublicContentSnapshot snapshot, List<Candidate> candidates, List<UUID> articleIds, int nextOffset) {}
}
