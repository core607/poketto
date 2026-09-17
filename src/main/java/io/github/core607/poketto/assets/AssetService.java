package io.github.core607.poketto.assets;

import io.github.core607.poketto.assets.MediaPreparations.Git;
import io.github.core607.poketto.assets.MediaPreparations.Indexed;
import io.github.core607.poketto.assets.MediaPreparations.Managed;
import io.github.core607.poketto.assets.MediaPreparations.PreparedMedia;
import io.github.core607.poketto.assets.MediaPreparations.Target;
import io.github.core607.poketto.assets.internal.AlbumThumbnailRenderer;
import io.github.core607.poketto.assets.internal.PublicThumbnailCache;
import io.github.core607.poketto.assets.internal.RepositoryImageCache;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.MarkdownResolutionLimitException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryMarkdownInspector;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Shared browser/MCP authorization, exact originals, and snapshot-bound rendering representations. */
public final class AssetService {
    // Neither of these reserves anything. Both cap the bytes one request may account for in
    // total, one for a page and one for an inventory listing. What actually reserves from the
    // shared admission pool is ImageMemoryAdmission.BROWSER_BYTES, and both paths take that same
    // per-image share. Raising a bound here therefore lets a request consider more images; it
    // does not give any of them more memory.
    private static final long INVENTORY_IMAGE_BYTES = 256L * 1024 * 1024;
    private static final int COVER_CANDIDATES = 8;
    private static final long COVER_IMAGE_BYTES = 32L * 1024 * 1024;
    private final AuthService auth;
    private final RepositoryContentReader content;
    private final RepositoryBlobReader blobs;
    private final RepositoryMarkdownInspector markdown;
    private final PublicContentSnapshots snapshots;
    private final Supplier<ManagedBlobStore> managed;
    private final RepositoryImageCache cache;
    private final PublicThumbnailCache thumbnails;
    private final Clock clock;
    private final ImageMemoryAdmission memory;
    private final ImageGrants grants;
    private final MediaPreparations media;

    public AssetService(
            AuthService auth,
            RepositoryContentReader content,
            RepositoryBlobReader blobs,
            RepositoryMarkdownInspector markdown,
            PublicContentSnapshots snapshots,
            Supplier<ManagedBlobStore> managed,
            Path cacheDirectory,
            long cacheBytes,
            int maxGrants,
            Clock clock,
            ImageMemoryAdmission memory) {
        this.auth = auth;
        this.content = content;
        this.blobs = blobs;
        this.markdown = markdown;
        this.snapshots = snapshots;
        this.managed = managed;
        this.cache = new RepositoryImageCache(cacheDirectory, cacheBytes);
        this.thumbnails = new PublicThumbnailCache(cacheDirectory.resolveSibling("public-album-thumbnails"));
        this.clock = clock;
        this.grants = new ImageGrants(blobs, clock, maxGrants);
        this.media = new MediaPreparations(blobs, managed, memory, this.cache);
        this.memory = Objects.requireNonNull(memory);
    }

    public ManagedAsset upload(AuthPrincipal actor, WorkspaceId workspace, String operationKey, InputStream original) {
        return auth.withAuthorization(
                actor,
                workspace,
                Set.of(Capability.WRITE_PRIVATE),
                () -> managed.get().upload(workspace, operationKey, original));
    }

    public ManagedAssetPage list(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        return auth.withAuthorization(
                actor,
                workspace,
                Set.of(Capability.READ_PRIVATE),
                () -> managed.get().list(workspace, offset, limit));
    }

    public AssetBytes readExact(AuthPrincipal actor, WorkspaceId workspace, AssetSource source) {
        AssetBytes image = preparePrivate(actor, workspace, () -> {
            if (source instanceof AssetSource.Managed managedSource) {
                return media.bytes(workspace, new Managed(managedSource.reference()));
            }
            AssetSource.Repository repositorySource = (AssetSource.Repository) source;
            String commit =
                    blobs.selectCommit(workspace, repositorySource.commit()).orElseThrow(AssetService::notFound);
            RepositoryMediaSnapshot catalog = media.availableMedia(workspace, commit);
            var indexed = catalog == null ? null : catalog.index().files().get(repositorySource.path());
            if (indexed != null) {
                return media.bytes(
                        workspace,
                        new Indexed(
                                commit,
                                repositorySource.path(),
                                indexed,
                                catalog.publicPaths().contains(repositorySource.path())));
            }
            RepositoryBlob blob =
                    blobs.find(workspace, commit, repositorySource.path()).orElseThrow(AssetService::notFound);
            return media.bytes(workspace, new Git(blob));
        });
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> image);
    }

    private record Candidate(String path, long size, Target target) {}

    private List<Candidate> imageCandidates(
            WorkspaceId workspace, String commit, String prefix, boolean privateAccess) {
        List<Candidate> candidates = new ArrayList<>();
        for (RepositoryBlob blob : blobs.images(workspace, commit, prefix)) {
            if (privateAccess || blob.publicPath()) {
                candidates.add(new Candidate(blob.path(), blob.size(), new Git(blob)));
            }
        }
        if (!privateAccess) {
            var catalog = media.availableMedia(workspace, commit);
            for (String path : catalog == null ? Set.<String>of() : catalog.publicPaths()) {
                var media = catalog.index().files().get(path);
                if (path.startsWith(prefix) && media.mediaType().startsWith("image/")) {
                    candidates.add(new Candidate(path, media.size(), new Indexed(commit, path, media, true)));
                }
            }
        }
        if (candidates.size() > 1000) {
            throw new ContentRepositoryException("repository image inventory entry bound exceeded");
        }
        candidates.sort(Comparator.comparing(Candidate::path));
        return candidates;
    }

    public RepositoryImagePage repositoryImages(
            AuthPrincipal actor,
            WorkspaceId workspace,
            Optional<String> requested,
            String prefix,
            int offset,
            int limit) {
        if (offset < 0 || offset > 1000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("repository image page exceeds its bounds");
        }
        boolean privateAccess = auth.authorize(actor, workspace).capabilities().contains(Capability.READ_PRIVATE);
        Supplier<RepositoryImagePage> prepare = () -> {
            Optional<String> commit = blobs.selectCommit(workspace, privateAccess ? requested : Optional.empty());
            if (!privateAccess && requested.isPresent() && !requested.equals(commit)) {
                throw notFound();
            }
            if (commit.isEmpty()) {
                return new RepositoryImagePage(null, List.of(), 0, offset, limit, List.of());
            }
            List<Candidate> candidates = imageCandidates(workspace, commit.orElseThrow(), prefix, privateAccess);
            List<RepositoryImagePage.Item> images = new ArrayList<>();
            List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
            long totalBytes = 0;
            for (Candidate candidate : candidates) {
                totalBytes += candidate.size();
                if (totalBytes > INVENTORY_IMAGE_BYTES) {
                    throw new ContentRepositoryException("repository image inventory byte bound exceeded");
                }
                var reservation = memory.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES);
                if (reservation.isEmpty()) {
                    diagnostics.add(new RepositoryDiagnostic(
                            candidate.path(),
                            "IMAGE_CAPACITY_UNAVAILABLE",
                            "image validation capacity is temporarily unavailable"));
                    continue;
                }
                var scope = reservation.orElseThrow();
                try (var producer = scope.producer()) {
                    AssetBytes image = media.bytes(workspace, candidate.target());
                    images.add(new RepositoryImagePage.Item(candidate.path(), image.mediaType(), candidate.size()));
                } catch (AssetStorageException | ContentRepositoryException invalid) {
                    diagnostics.add(new RepositoryDiagnostic(
                            candidate.path(), "INVALID_IMAGE", "image signature, dimensions or bytes are unavailable"));
                } finally {
                    scope.responseComplete();
                }
            }
            if (!privateAccess
                    && !blobs.selectCommit(workspace, Optional.empty()).equals(commit)) {
                throw notFound();
            }
            return new RepositoryImagePage(
                    commit.orElseThrow(),
                    images.stream().skip(offset).limit(limit).toList(),
                    images.size(),
                    offset,
                    limit,
                    diagnostics);
        };
        RepositoryImagePage page = privateAccess ? preparePrivate(actor, workspace, prepare) : prepare.get();
        return auth.withAuthorization(
                actor, workspace, privateAccess ? Set.of(Capability.READ_PRIVATE) : Set.of(), () -> page);
    }

    public ResolvedMedia preview(
            AuthPrincipal actor, WorkspaceId workspace, String path, String source, Optional<String> requested) {
        if (!auth.authorize(actor, workspace).capabilities().contains(Capability.READ_PRIVATE)) {
            return memberPublicPreview(actor, workspace, path, source, requested);
        }
        PreparedMedia prepared = preparePrivate(actor, workspace, () -> {
            var draft = markdown.inspect(path, source);
            Optional<String> commit = blobs.selectCommit(workspace, requested);
            Map<String, String> routes = new HashMap<>();
            if (commit.isPresent()) {
                for (var document : content.readTree(workspace, commit).documents()) {
                    routes.put(
                            document.file().path(),
                            "/admin?workspace=" + workspace + "&path="
                                    + URLEncoder.encode(document.file().path(), StandardCharsets.UTF_8));
                }
            }
            return media.prepare(
                    workspace,
                    path,
                    draft.body(),
                    commit.orElse(null),
                    draft.folderPage(),
                    routes,
                    false,
                    false,
                    value -> true);
        });
        return auth.withAuthorization(
                actor,
                workspace,
                Set.of(Capability.READ_PRIVATE),
                () -> finishMedia(
                        workspace,
                        path,
                        actorKey(actor),
                        clock.instant().plus(ImageGrants.GRANT_LIFETIME),
                        prepared,
                        false));
    }

    private ResolvedMedia memberPublicPreview(
            AuthPrincipal actor, WorkspaceId workspace, String path, String source, Optional<String> requested) {
        var file = content.getPublicFile(workspace, requested, path);
        var tree = content.readPublicTree(workspace, file.commit());
        Set<String> managedSources = new HashSet<>();
        Map<String, String> routes = new HashMap<>();
        for (var document : tree.documents()) {
            routes.put(
                    document.file().path(),
                    "/admin?workspace=" + workspace + "&path="
                            + URLEncoder.encode(document.file().path(), StandardCharsets.UTF_8));
            MarkdownDestinations.parse(document.body()).images().stream()
                    .filter(value -> value.startsWith("managed:"))
                    .forEach(managedSources::add);
        }
        var draft = markdown.inspect(path, source);
        var prepared = media.prepare(
                workspace,
                path,
                draft.body(),
                file.commit().orElse(null),
                draft.folderPage(),
                routes,
                true,
                false,
                managedSources::contains);
        content.getPublicFile(workspace, file.commit(), path);
        return auth.withAuthorization(
                actor,
                workspace,
                Set.of(),
                () -> finishMedia(
                        workspace,
                        path,
                        actorKey(actor),
                        clock.instant().plus(ImageGrants.GRANT_LIFETIME),
                        prepared,
                        true));
    }

    /**
     * Prepares image bytes outside the installation lock, then revalidates publication before
     * signing. A changed commit can trigger one retry; grant capacity omits only affected images.
     */
    public Optional<ResolvedPublicDocument> publicDocument(WorkspaceId workspace, String route) {
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
                        finishMedia(workspace, value.repositoryPath(), "", current.expiresAt(), prepared, true);
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
     * Prepares at most six visible folder covers, sharing one media inventory for the selected
     * workspace and commit. Publication is checked before and after source preparation outside
     * the snapshot lock. Missing routes are no longer current; a null URL only means no cover.
     */
    public Map<String, PublicAlbumCover> publicAlbumCovers(
            PublicContentSnapshot selected, List<PublicArticle> requested) {
        if (requested.size() > 6 || requested.stream().anyMatch(article -> !article.folderPage())) {
            throw new IllegalArgumentException("cover preparation requires at most six folder landings");
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
            prepared.put(article, prepareAlbumCover(workspace, commit, article, catalog));
        }
        return snapshots.withCurrent(workspace, snapshot -> {
            var covers = new LinkedHashMap<String, PublicAlbumCover>();
            for (var entry : prepared.entrySet()) {
                PublicArticle expected = entry.getKey();
                if (sameArticle(snapshot, selected, expected)) {
                    PreparedCover cover = entry.getValue();
                    String url = cover.target() == null
                            ? null
                            : imageUrl(
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
        try {
            Set<String> inline = new HashSet<>();
            for (String authored : MarkdownDestinations.parse(article.body()).images()) {
                MarkdownDestinations.path(article.repositoryPath(), authored).ifPresent(inline::add);
            }
            coverCandidates(workspace, commit, article.repositoryPath(), inline, catalog, candidates);
        } catch (AssetStorageException | ContentRepositoryException | MarkdownResolutionLimitException unavailable) {
            // Card text remains readable when its optional image inventory is unavailable.
        }
        Map<Target, Boolean> resolved = new HashMap<>();
        long[] total = {0};
        for (Target target : candidates.values()) {
            if (MediaPreparations.imageAllowance(target) > COVER_IMAGE_BYTES - total[0]) {
                continue;
            }
            try {
                if (media.prepareImage(workspace, target, resolved, total)) {
                    return new PreparedCover(true, target);
                }
            } catch (AssetStorageException | ContentRepositoryException unavailable) {
                // A later candidate may still have independently available original bytes.
            }
        }
        return new PreparedCover(!candidates.isEmpty(), null);
    }

    private void coverCandidates(
            WorkspaceId workspace,
            String commit,
            String path,
            Set<String> inline,
            RepositoryMediaSnapshot catalog,
            TreeMap<String, Target> selected) {
        try {
            for (RepositoryBlob blob : blobs.siblings(workspace, commit, path, COVER_CANDIDATES, true, inline)
                    .items()) {
                if (blob.publicPath() && !inline.contains(blob.path())) {
                    selected.put(blob.path(), new Git(blob));
                }
            }
        } catch (ContentRepositoryException unavailable) {
            // A previously validated media index can still supply an independent managed cover.
        }
        if (catalog == null) {
            return;
        }
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

    private record PreparedCover(boolean album, Target target) {}

    /** The opaque token fixes the workspace; a browser's selected workspace never affects this read. */
    public AssetBytes readPublicImage(String token) {
        ImageGrants.Grant selected = grants.grant(token, "");
        return readPublicImage(selected.key().workspace(), token);
    }

    public AssetBytes readPublicImage(WorkspaceId workspace, String token) {
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
            throw notFound();
        }
        snapshots.withCurrent(grant.key().workspace(), snapshot -> {
            if (!snapshot.commit().equals(Optional.of(grant.key().commit()))
                    || snapshot.articles().stream()
                            .noneMatch(article ->
                                    article.repositoryPath().equals(grant.key().page()))) {
                throw notFound();
            }
            return null;
        });
    }

    /** An opaque private URL never substitutes for the current identity or current workspace authority. */
    public AssetBytes readPrivateImage(AuthPrincipal actor, WorkspaceId workspace, String token) {
        auth.withAuthorization(actor, workspace, Set.of(), () -> null);
        ImageGrants.Grant selected = grants.grant(workspace, token, actorKey(actor));
        if (selected.key().publicScope()) {
            auth.withAuthorization(actor, workspace, Set.of(), () -> null);
            requireCurrentMemberPage(selected);
            AssetBytes image = media.bytes(workspace, selected.key().target());
            requireCurrentMemberPage(selected);
            return auth.withAuthorization(actor, workspace, Set.of(), () -> {
                grants.grant(workspace, token, actorKey(actor));
                return image;
            });
        }
        AssetBytes image = preparePrivate(actor, workspace, () -> {
            ImageGrants.Grant grant = grants.grant(workspace, token, actorKey(actor));
            return media.bytes(workspace, grant.key().target());
        });
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> {
            grants.grant(workspace, token, actorKey(actor));
            return image;
        });
    }

    private void requireCurrentMemberPage(ImageGrants.Grant grant) {
        content.getPublicFile(
                grant.key().workspace(),
                Optional.ofNullable(grant.key().commit()),
                grant.key().page());
    }

    private <T> T preparePrivate(AuthPrincipal actor, WorkspaceId workspace, Supplier<T> prepare) {
        auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> null);
        try {
            return prepare.get();
        } catch (RuntimeException failure) {
            // A failed preparation can contain private diagnostics, so denial takes precedence.
            auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> null);
            throw failure;
        }
    }

    private static Optional<PublicArticle> article(PublicContentSnapshot snapshot, String route) {
        return snapshot.articles().stream()
                .filter(value -> value.route().equals(route))
                .findFirst();
    }

    private ResolvedMedia finishMedia(
            WorkspaceId workspace,
            String page,
            String actor,
            Instant expires,
            PreparedMedia prepared,
            boolean publicScope) {
        Map<Target, String> resolved = new HashMap<>();
        Map<Target, String> previews = new HashMap<>();
        Map<String, String> images = new LinkedHashMap<>();
        for (var image : prepared.images().entrySet()) {
            String url = imageUrl(
                    workspace,
                    page,
                    prepared.commit(),
                    actor,
                    expires,
                    image.getValue(),
                    resolved,
                    publicScope,
                    ImageGrants.Representation.ORIGINAL);
            if (url != null) {
                images.put(image.getKey(), url);
            }
        }
        List<ResolvedMedia.GalleryImage> gallery = new ArrayList<>();
        var status = prepared.galleryStatus();
        for (var image : prepared.gallery()) {
            String original = imageUrl(
                    workspace,
                    page,
                    prepared.commit(),
                    actor,
                    expires,
                    image.target(),
                    resolved,
                    publicScope,
                    ImageGrants.Representation.ORIGINAL);
            String preview = actor.isEmpty() && original != null
                    ? imageUrl(
                            workspace,
                            page,
                            prepared.commit(),
                            actor,
                            expires,
                            image.target(),
                            previews,
                            publicScope,
                            ImageGrants.Representation.ALBUM_THUMBNAIL_V1)
                    : original;
            if (preview != null && original != null) {
                gallery.add(new ResolvedMedia.GalleryImage(preview, original, image.alt()));
            } else if (status == ResolvedMedia.GalleryStatus.COMPLETE) {
                status = ResolvedMedia.GalleryStatus.PARTIAL;
            }
        }
        return new ResolvedMedia(
                prepared.body(), prepared.commit(), prepared.links(), prepared.downloads(), images, gallery, status);
    }

    private String imageUrl(
            WorkspaceId workspace,
            String page,
            String commit,
            String actor,
            Instant expires,
            Target target,
            Map<Target, String> resolved,
            boolean publicScope,
            ImageGrants.Representation representation) {
        if (resolved.containsKey(target)) {
            return resolved.get(target);
        }
        String url = null;
        try {
            Optional<String> token = grants.mint(
                    new ImageGrants.Key(workspace, commit, page, target, actor, publicScope, representation), expires);
            if (token.isPresent()) {
                url = (actor.isEmpty()
                                ? "/api/public/assets/"
                                : WorkspaceHttpRoutes.admin(workspace) + "/assets/images/")
                        + token.orElseThrow();
            }
        } catch (AssetStorageException unavailable) {
            // Validation and publication are separate; unavailable source protection issues no URL.
        }
        resolved.put(target, url);
        return url;
    }

    private static String actorKey(AuthPrincipal actor) {
        return actor.kind() + ":" + actor.subjectId() + ":" + actor.accountId();
    }

    private static AssetStorageException notFound() {
        return ImageGrants.notFound();
    }

    private record PageAttempt(boolean retry, Optional<ResolvedPublicDocument> page) {}
}
