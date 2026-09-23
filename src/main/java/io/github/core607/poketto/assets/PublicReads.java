package io.github.core607.poketto.assets;

import io.github.core607.poketto.assets.MediaPreparations.Git;
import io.github.core607.poketto.assets.MediaPreparations.Indexed;
import io.github.core607.poketto.assets.MediaPreparations.Managed;
import io.github.core607.poketto.assets.MediaPreparations.PreparedMedia;
import io.github.core607.poketto.assets.MediaPreparations.Target;
import io.github.core607.poketto.assets.internal.AlbumThumbnailRenderer;
import io.github.core607.poketto.assets.internal.PublicThumbnailCache;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.MarkdownResolutionLimitException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What an anonymous reader may see: one article with its media, the covers of the cards a
 * discovery batch lists, and the bytes behind a public image token. Each read is bound to the
 * publication snapshot it was prepared from and is rechecked before anything is returned, so a
 * withdrawal between preparation and delivery serves nothing.
 */
final class PublicReads {
    // Neither of these reserves anything; they cap the bytes one cover request may account for.
    private static final int COVER_CANDIDATES = 8;
    private static final long COVER_IMAGE_BYTES = 32L * 1024 * 1024;

    private final AssetService assets;
    private final MediaPreparations media;
    private final RepositoryBlobReader blobs;
    private final ImageGrants grants;
    private final PublicContentSnapshots snapshots;
    private final PublicThumbnailCache thumbnails;
    private final Clock clock;

    PublicReads(
            AssetService assets,
            MediaPreparations media,
            RepositoryBlobReader blobs,
            ImageGrants grants,
            PublicContentSnapshots snapshots,
            PublicThumbnailCache thumbnails,
            Clock clock) {
        this.assets = assets;
        this.media = media;
        this.blobs = blobs;
        this.grants = grants;
        this.snapshots = snapshots;
        this.thumbnails = thumbnails;
        this.clock = clock;
    }

    Optional<ResolvedPublicDocument> document(WorkspaceId workspace, String route) {
        for (int attempt = 0; attempt < 2; attempt++) {
            PublicContentSnapshot snapshot = snapshots.withCurrent(workspace, value -> value);
            var article = article(snapshot, route);
            if (article.isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> routes = new HashMap<>();
            for (var item : snapshot.articles()) {
                routes.put(item.repositoryPath(), item.route());
            }
            var value = article.orElseThrow();
            PreparedMedia prepared = media.prepare(
                    workspace,
                    value.repositoryPath(),
                    value.body(),
                    snapshot.commit().orElse(null),
                    value.folderPage(),
                    routes,
                    true,
                    true,
                    reference -> true);
            PageAttempt completed = snapshots.withCurrent(workspace, current -> {
                var currentArticle = article(current, route);
                if (currentArticle.isEmpty()) {
                    return new PageAttempt(false, Optional.empty());
                }
                if (!current.commit().equals(snapshot.commit())) {
                    return new PageAttempt(true, Optional.empty());
                }
                if (!currentArticle.orElseThrow().equals(value)) {
                    throw new ContentRepositoryException("public article differs within the selected commit");
                }
                ResolvedMedia media =
                        assets.finishMedia(workspace, value.repositoryPath(), "", current.expiresAt(), prepared, true);
                Instant now = clock.instant();
                if (now.isBefore(current.verifiedAt()) || !now.isBefore(current.expiresAt())) {
                    throw new ContentRepositoryException("public snapshot expired during image resolution");
                }
                return new PageAttempt(false, Optional.of(new ResolvedPublicDocument(current, value, media)));
            });
            if (!completed.retry()) {
                return completed.page();
            }
        }
        throw new ContentRepositoryException("public snapshot changed during both image preparation attempts");
    }

    /**
     * Prepares covers for at most six visible cards, sharing one media inventory for the selected
     * workspace and commit. A folder landing uses a sibling image and reports whether it is an
     * album; one whose folder reads in full with no further images, like any other article, uses
     * its first inline image that is public and readable. Publication
     * is checked before and after source preparation outside the snapshot lock. Missing routes are
     * no longer current; a null URL only means no cover.
     */
    Map<String, PublicAlbumCover> covers(PublicContentSnapshot selected, List<PublicArticle> requested) {
        if (requested.size() > 6) {
            throw new IllegalArgumentException("cover preparation accepts at most six cards");
        }
        if (requested.isEmpty() || selected.commit().isEmpty()) {
            return Map.of();
        }
        WorkspaceId workspace = selected.workspaceId();
        List<PublicArticle> current = snapshots.withCurrent(
                workspace,
                snapshot -> requested.stream()
                        .filter(expected -> sameArticle(snapshot, selected, expected))
                        .distinct()
                        .toList());
        if (current.isEmpty()) {
            return Map.of();
        }
        String commit = selected.commit().orElseThrow();
        RepositoryMediaSnapshot catalog = media.availableMedia(workspace, commit);
        var prepared = new LinkedHashMap<PublicArticle, PreparedCover>();
        for (PublicArticle article : current) {
            prepared.put(
                    article,
                    article.folderPage()
                            ? prepareAlbumCover(workspace, commit, article, catalog)
                            : prepareArticleCover(workspace, commit, article, catalog));
        }
        return snapshots.withCurrent(workspace, snapshot -> {
            var covers = new LinkedHashMap<String, PublicAlbumCover>();
            for (var entry : prepared.entrySet()) {
                PublicArticle expected = entry.getKey();
                if (sameArticle(snapshot, selected, expected)) {
                    PreparedCover cover = entry.getValue();
                    String url = cover.target() == null
                            ? null
                            : assets.imageUrl(
                                    workspace,
                                    expected.repositoryPath(),
                                    commit,
                                    "",
                                    snapshot.expiresAt(),
                                    cover.target(),
                                    new HashMap<>(),
                                    true,
                                    ImageGrants.Representation.ALBUM_THUMBNAIL_V1);
                    covers.put(expected.route(), new PublicAlbumCover(cover.album(), url));
                }
            }
            Instant now = clock.instant();
            if (now.isBefore(snapshot.verifiedAt()) || !now.isBefore(snapshot.expiresAt())) {
                throw new ContentRepositoryException("public snapshot expired during cover preparation");
            }
            return Map.copyOf(covers);
        });
    }

    private static boolean sameArticle(
            PublicContentSnapshot current, PublicContentSnapshot selected, PublicArticle expected) {
        return current.commit().equals(selected.commit())
                && article(current, expected.route()).filter(expected::equals).isPresent();
    }

    private PreparedCover prepareAlbumCover(
            WorkspaceId workspace, String commit, PublicArticle article, RepositoryMediaSnapshot catalog) {
        var candidates = new TreeMap<String, Target>();
        Inventory inventory = Inventory.UNKNOWN;
        try {
            Set<String> inline = new HashSet<>();
            for (String authored : MarkdownDestinations.parse(article.body()).images()) {
                MarkdownDestinations.path(article.repositoryPath(), authored).ifPresent(inline::add);
            }
            inventory = coverCandidates(workspace, commit, article.repositoryPath(), inline, catalog, candidates);
        } catch (AssetStorageException | ContentRepositoryException | MarkdownResolutionLimitException unavailable) {
            // Card text remains readable when its optional image inventory is unavailable.
        }
        if (inventory == Inventory.NONE) {
            // A fully read folder without further images is a directory; its own figure can still be its cover.
            return prepareArticleCover(workspace, commit, article, catalog);
        }
        // An unknown inventory with nothing found claims neither an album nor a figure.
        boolean album = inventory == Inventory.FURTHER || !candidates.isEmpty();
        return new PreparedCover(album, firstImage(workspace, candidates.values()));
    }

    /** Inline images in document order; private and unreadable references are skipped, not substituted. */
    private PreparedCover prepareArticleCover(
            WorkspaceId workspace, String commit, PublicArticle article, RepositoryMediaSnapshot catalog) {
        var candidates = new LinkedHashMap<String, Target>();
        try {
            for (String authored : MarkdownDestinations.parse(article.body()).images()) {
                if (candidates.size() >= COVER_CANDIDATES) {
                    break;
                }
                var path = MarkdownDestinations.path(article.repositoryPath(), authored);
                if (path.isEmpty() || candidates.containsKey(path.orElseThrow())) {
                    continue;
                }
                String name = path.orElseThrow();
                var indexed = catalog == null ? null : catalog.index().files().get(name);
                if (indexed != null) {
                    if (indexed.mediaType().startsWith("image/")
                            && catalog.publicPaths().contains(name)) {
                        candidates.put(name, new Indexed(commit, name, indexed, true));
                    }
                } else {
                    blobs.find(workspace, commit, name)
                            .filter(RepositoryBlob::publicPath)
                            .ifPresent(blob -> candidates.put(name, new Git(blob)));
                }
            }
        } catch (AssetStorageException | ContentRepositoryException | MarkdownResolutionLimitException unavailable) {
            // Card text remains readable when its optional image inventory is unavailable.
        }
        return new PreparedCover(false, firstImage(workspace, candidates.values()));
    }

    private Target firstImage(WorkspaceId workspace, Iterable<Target> candidates) {
        Map<Target, Boolean> resolved = new HashMap<>();
        long[] total = {0};
        for (Target target : candidates) {
            if (MediaPreparations.imageAllowance(target) > COVER_IMAGE_BYTES - total[0]) {
                continue;
            }
            try {
                if (media.prepareImage(workspace, target, resolved, total)) {
                    return target;
                }
            } catch (AssetStorageException | ContentRepositoryException unavailable) {
                // A later candidate may still have independently available original bytes.
            }
        }
        return null;
    }

    /** Selects cover candidates and reports what the folder holds beyond the page's own images. */
    private Inventory coverCandidates(
            WorkspaceId workspace,
            String commit,
            String path,
            Set<String> inline,
            RepositoryMediaSnapshot catalog,
            TreeMap<String, Target> selected) {
        boolean unknown = false;
        // A partial listing skipped an oversized image or stopped at the limit; either way one exists.
        boolean further = false;
        try {
            var siblings = blobs.siblings(workspace, commit, path, COVER_CANDIDATES, true, inline);
            further = siblings.partial();
            for (RepositoryBlob blob : siblings.items()) {
                if (blob.publicPath() && !inline.contains(blob.path())) {
                    selected.put(blob.path(), new Git(blob));
                }
            }
        } catch (ContentRepositoryException unavailable) {
            // A previously validated media index can still supply an independent managed cover.
            unknown = true;
        }
        if (catalog == null) {
            unknown = true;
        } else {
            addIndexedCandidates(commit, path, inline, catalog, selected);
        }
        if (further || !selected.isEmpty()) {
            return Inventory.FURTHER;
        }
        return unknown ? Inventory.UNKNOWN : Inventory.NONE;
    }

    private static void addIndexedCandidates(
            String commit,
            String path,
            Set<String> inline,
            RepositoryMediaSnapshot catalog,
            TreeMap<String, Target> selected) {
        String prefix = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (var entry : catalog.index().files().entrySet()) {
            String name = entry.getKey();
            boolean eligible = name.startsWith(prefix)
                    && !name.substring(prefix.length()).contains("/")
                    && !inline.contains(name)
                    && entry.getValue().mediaType().startsWith("image/")
                    && catalog.publicPaths().contains(name);
            if (eligible) {
                selected.putIfAbsent(name, new Indexed(commit, name, entry.getValue(), true));
                if (selected.size() > COVER_CANDIDATES) {
                    selected.pollLastEntry();
                }
            }
        }
    }

    /** NONE only when every inventory was read and held nothing beyond the page's own images. */
    private enum Inventory {
        NONE,
        FURTHER,
        UNKNOWN
    }

    private record PreparedCover(boolean album, Target target) {}

    /** The opaque token fixes the workspace; a browser's selected workspace never affects this read. */
    AssetBytes image(String token) {
        ImageGrants.Grant selected = grants.grant(token, "");
        return image(selected.key().workspace(), token);
    }

    AssetBytes image(WorkspaceId workspace, String token) {
        ImageGrants.Grant grant = grants.grant(workspace, token, "");
        requireCurrentPublication(grant);
        AssetBytes image = publicRepresentation(grant);
        grants.grant(workspace, token, "");
        requireCurrentPublication(grant);
        return image;
    }

    private AssetBytes publicRepresentation(ImageGrants.Grant grant) {
        WorkspaceId workspace = grant.key().workspace();
        Target target = grant.key().target();
        if (grant.key().representation() == ImageGrants.Representation.ORIGINAL) {
            return media.bytes(workspace, target);
        }
        String version = thumbnailSource(target);
        byte[] image = thumbnails.get(workspace, version, () -> {
            byte[] rendered =
                    AlbumThumbnailRenderer.render(media.bytes(workspace, target).bytes());
            requireCurrentPublication(grant);
            return rendered;
        });
        AssetSource source =
                switch (target) {
                    case Git git ->
                        new AssetSource.Repository(
                                Optional.of(git.blob().commit()), git.blob().path());
                    case Indexed indexed -> new AssetSource.Repository(Optional.of(indexed.commit()), indexed.path());
                    case Managed value -> new AssetSource.Managed(value.reference());
                };
        return new AssetBytes(
                source,
                version + ":" + AlbumThumbnailRenderer.REPRESENTATION,
                AlbumThumbnailRenderer.validateEncoded(image),
                image);
    }

    private static String thumbnailSource(Target target) {
        return switch (target) {
            case Git git -> "git:" + git.blob().objectId();
            case Indexed indexed ->
                "managed:" + indexed.media().assetId() + ":" + indexed.media().revision();
            case Managed value ->
                "managed:" + value.reference().assetId() + ":"
                        + value.reference().revision();
        };
    }

    private void requireCurrentPublication(ImageGrants.Grant grant) {
        if (grant.key().target() instanceof Indexed indexed && !indexed.publicPath()) {
            throw AssetService.notFound();
        }
        snapshots.withCurrent(grant.key().workspace(), snapshot -> {
            if (!snapshot.commit().equals(Optional.of(grant.key().commit()))
                    || snapshot.articles().stream()
                            .noneMatch(article ->
                                    article.repositoryPath().equals(grant.key().page()))) {
                throw AssetService.notFound();
            }
            return null;
        });
    }

    /** An opaque private URL never substitutes for the current identity or current workspace authority. */
    private static Optional<PublicArticle> article(PublicContentSnapshot snapshot, String route) {
        return snapshot.articles().stream()
                .filter(value -> value.route().equals(route))
                .findFirst();
    }

    private record PageAttempt(boolean retry, Optional<ResolvedPublicDocument> page) {}
}
