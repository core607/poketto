package io.github.core607.poketto.assets;

import io.github.core607.poketto.assets.MediaPreparations.Git;
import io.github.core607.poketto.assets.MediaPreparations.Indexed;
import io.github.core607.poketto.assets.MediaPreparations.Managed;
import io.github.core607.poketto.assets.MediaPreparations.PreparedMedia;
import io.github.core607.poketto.assets.MediaPreparations.Target;
import io.github.core607.poketto.assets.internal.PublicThumbnailCache;
import io.github.core607.poketto.assets.internal.RepositoryImageCache;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.MarkdownDestinations;
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
import java.util.function.Supplier;

/** Shared browser/MCP authorization, exact originals, and snapshot-bound rendering representations. */
public final class AssetService {
    // Neither of these reserves anything. Both cap the bytes one request may account for in
    // total, one for a page and one for an inventory listing. What actually reserves from the
    // shared admission pool is ImageMemoryAdmission.BROWSER_BYTES, and both paths take that same
    // per-image share. Raising a bound here therefore lets a request consider more images; it
    // does not give any of them more memory.
    private static final long INVENTORY_IMAGE_BYTES = 256L * 1024 * 1024;
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
    private final PublicReads reads;

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
        this.reads = new PublicReads(this, media, blobs, grants, snapshots, thumbnails, markdown, clock);
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
                var entry = catalog.index().files().get(path);
                if (path.startsWith(prefix) && entry.mediaType().startsWith("image/")) {
                    candidates.add(new Candidate(path, entry.size(), new Indexed(commit, path, entry, true)));
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

    /** Anonymous reading of published documents, album covers and image tokens. */
    public Optional<ResolvedPublicDocument> publicDocument(WorkspaceId workspace, String route) {
        return reads.document(workspace, route);
    }

    public Map<String, PublicAlbumCover> publicAlbumCovers(
            PublicContentSnapshot selected, List<PublicArticle> requested) {
        return reads.albumCovers(selected, requested);
    }

    /** The opaque token fixes the workspace; a browser's selected workspace never affects this read. */
    public AssetBytes readPublicImage(String token) {
        return reads.image(token);
    }

    public AssetBytes readPublicImage(WorkspaceId workspace, String token) {
        return reads.image(workspace, token);
    }

    ResolvedMedia finishMedia(
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

    String imageUrl(
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

    static AssetStorageException notFound() {
        return ImageGrants.notFound();
    }
}
