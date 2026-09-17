package io.github.core607.poketto.assets;

import io.github.core607.poketto.assets.internal.RepositoryImageCache;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.MarkdownDestinations;
import io.github.core607.poketto.content.MarkdownResolutionLimitException;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositoryMediaSnapshot;
import io.github.core607.poketto.workspace.WorkspaceHttpRoutes;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Resolves the media a document names to targets, prepares each image once within one page's byte
 * allowance and the shared memory admission, reads exact bytes for a target, and builds the link,
 * download and gallery views of a document. It owns no grant: the caller decides what a prepared
 * target may be served as.
 */
final class MediaPreparations {
    // Neither of these reserves anything. Both cap the bytes one request may account for in
    // total, one for a page and one for an inventory listing. What actually reserves from the
    // shared admission pool is ImageMemoryAdmission.BROWSER_BYTES, and both paths take that same
    // per-image share. Raising a bound here therefore lets a request consider more images; it
    // does not give any of them more memory.
    static final long PAGE_IMAGE_BYTES = 128L * 1024 * 1024;

    private final RepositoryBlobReader blobs;
    private final Supplier<ManagedBlobStore> managed;
    private final ImageMemoryAdmission memory;
    private final RepositoryImageCache cache;

    MediaPreparations(
            RepositoryBlobReader blobs,
            Supplier<ManagedBlobStore> managed,
            ImageMemoryAdmission memory,
            RepositoryImageCache cache) {
        this.blobs = blobs;
        this.managed = managed;
        this.memory = memory;
        this.cache = cache;
    }

    private static AssetStorageException notFound() {
        return ImageGrants.notFound();
    }

    PreparedMedia prepare(
            WorkspaceId workspace,
            String path,
            String body,
            String commit,
            boolean folder,
            Map<String, String> routes,
            boolean publicOnly,
            boolean anonymous,
            Predicate<String> managedAllowed) {
        return new MediaPreparation(workspace, path, commit, routes, publicOnly, anonymous, managedAllowed)
                .prepare(body, folder);
    }

    /**
     * One document's media: authored links resolved to routes or downloads, inline images and the
     * folder gallery prepared as grant targets. Every image shares one byte allowance and one
     * resolution cache, and the media catalog is read once, only when something can need it.
     */
    private final class MediaPreparation {
        private final WorkspaceId workspace;
        private final String path;
        private final String commit;
        private final Map<String, String> routes;
        private final boolean publicOnly;
        private final boolean anonymous;
        private final Predicate<String> managedAllowed;
        private final Map<Target, Boolean> resolved = new HashMap<>();
        private final long[] bytes = {0};
        private RepositoryMediaSnapshot catalog;

        MediaPreparation(
                WorkspaceId workspace,
                String path,
                String commit,
                Map<String, String> routes,
                boolean publicOnly,
                boolean anonymous,
                Predicate<String> managedAllowed) {
            this.workspace = workspace;
            this.path = path;
            this.commit = commit;
            this.routes = routes;
            this.publicOnly = publicOnly;
            this.anonymous = anonymous;
            this.managedAllowed = managedAllowed;
        }

        PreparedMedia prepare(String body, boolean folder) {
            MarkdownDestinations.Destinations destinations;
            try {
                destinations = MarkdownDestinations.parse(body);
            } catch (MarkdownResolutionLimitException limit) {
                return new PreparedMedia(
                        body, commit, Map.of(), Map.of(), Map.of(), List.of(), ResolvedMedia.GalleryStatus.UNAVAILABLE);
            }
            if (commit != null
                    && (folder
                            || destinations.links().stream()
                                    .anyMatch(authored -> MarkdownDestinations.path(path, authored)
                                            .isPresent())
                            || destinations.images().stream().anyMatch(authored -> !authored.startsWith("managed:")))) {
                catalog = availableMedia(workspace, commit);
            }
            Map<String, String> links = new LinkedHashMap<>();
            Map<String, String> downloads = new LinkedHashMap<>();
            resolveLinks(destinations, links, downloads);
            Set<String> inlinePaths = new HashSet<>();
            for (String authored : destinations.images()) {
                MarkdownDestinations.path(path, authored).ifPresent(inlinePaths::add);
            }
            Map<String, Target> images = inlineImages(destinations);
            Gallery gallery = folder && commit != null
                    ? gallery(inlinePaths)
                    : new Gallery(List.of(), ResolvedMedia.GalleryStatus.COMPLETE);
            return new PreparedMedia(body, commit, links, downloads, images, gallery.items(), gallery.status());
        }

        // A fragment stays as authored; a repository path becomes its route, or a download when it
        // is indexed media without one.
        private void resolveLinks(
                MarkdownDestinations.Destinations destinations,
                Map<String, String> links,
                Map<String, String> downloads) {
            Set<String> publicRoutes = publicOnly ? Set.copyOf(routes.values()) : Set.of();
            for (String authored : destinations.links()) {
                if (authored.startsWith("#")
                        && authored.length() <= 256
                        && authored.codePoints().noneMatch(Character::isISOControl)) {
                    links.put(authored, authored);
                    continue;
                }
                MarkdownDestinations.path(path, authored).ifPresent(target -> {
                    String selected = MarkdownDestinations.route(path, authored, routes, publicRoutes)
                            .orElse(null);
                    if (selected == null
                            && catalog != null
                            && catalog.index().files().containsKey(target)
                            && (!publicOnly || catalog.publicPaths().contains(target))) {
                        downloads.put(
                                authored,
                                downloadUrl(workspace, anonymous, commit, routes.get(path), target)
                                        + fragment(authored));
                    }
                    if (selected != null) {
                        links.put(authored, selected + fragment(authored));
                    }
                });
            }
        }

        private Map<String, Target> inlineImages(MarkdownDestinations.Destinations destinations) {
            Map<String, Target> images = new LinkedHashMap<>();
            for (String authored : destinations.images()) {
                try {
                    if (authored.startsWith("managed:") && !managedAllowed.test(authored)) {
                        continue;
                    }
                    Optional<Target> selected = target(workspace, commit, path, authored, catalog);
                    if (selected.isEmpty()) {
                        continue;
                    }
                    Target target = selected.orElseThrow();
                    if (target instanceof Git git && publicOnly && !git.blob().publicPath()) {
                        continue;
                    }
                    if (target instanceof Indexed indexed && publicOnly && !indexed.publicPath()) {
                        continue;
                    }
                    if (prepareImage(workspace, target, resolved, bytes)) {
                        images.put(authored, target);
                    }
                } catch (AssetStorageException | ContentRepositoryException unavailable) {
                    // An unavailable image retains its Markdown placeholder, without an authored URL fallback.
                }
            }
            return images;
        }

        // Git siblings first, then indexed media beside the document; an image that fails to
        // prepare leaves the gallery partial, a sibling listing that fails leaves it unavailable.
        private Gallery gallery(Set<String> inlinePaths) {
            List<PreparedGallery> items = new ArrayList<>();
            var status = ResolvedMedia.GalleryStatus.COMPLETE;
            int candidates = 0;
            try {
                var siblings = blobs.siblings(workspace, commit, path, 128, publicOnly, inlinePaths);
                candidates = siblings.items().size();
                if (siblings.partial()) {
                    status = ResolvedMedia.GalleryStatus.PARTIAL;
                }
                for (RepositoryBlob blob : siblings.items()) {
                    if (inlinePaths.contains(blob.path()) || (publicOnly && !blob.publicPath())) {
                        continue;
                    }
                    Target target = new Git(blob);
                    if (prepareImage(workspace, target, resolved, bytes)) {
                        items.add(new PreparedGallery(
                                target, blob.path().substring(blob.path().lastIndexOf('/') + 1)));
                    } else {
                        status = ResolvedMedia.GalleryStatus.PARTIAL;
                    }
                }
            } catch (AssetStorageException | ContentRepositoryException unavailable) {
                status = ResolvedMedia.GalleryStatus.UNAVAILABLE;
            }
            if (catalog == null && status != ResolvedMedia.GalleryStatus.UNAVAILABLE) {
                status = ResolvedMedia.GalleryStatus.PARTIAL;
            }
            if (catalog != null) {
                status = indexedSiblings(inlinePaths, items, status, candidates);
            }
            return new Gallery(items, status);
        }

        private ResolvedMedia.GalleryStatus indexedSiblings(
                Set<String> inlinePaths,
                List<PreparedGallery> items,
                ResolvedMedia.GalleryStatus status,
                int candidates) {
            String prefix = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
            for (var entry : catalog.index().files().entrySet()) {
                String name = entry.getKey();
                if (!name.startsWith(prefix)
                        || name.substring(prefix.length()).contains("/")
                        || inlinePaths.contains(name)
                        || !entry.getValue().mediaType().startsWith("image/")
                        || (publicOnly && !catalog.publicPaths().contains(name))) {
                    continue;
                }
                if (candidates++ >= 128) {
                    return ResolvedMedia.GalleryStatus.PARTIAL;
                }
                Target target = new Indexed(
                        commit, name, entry.getValue(), catalog.publicPaths().contains(name));
                try {
                    if (prepareImage(workspace, target, resolved, bytes)) {
                        items.add(new PreparedGallery(target, name.substring(prefix.length())));
                    } else {
                        status = ResolvedMedia.GalleryStatus.PARTIAL;
                    }
                } catch (AssetStorageException | ContentRepositoryException unavailable) {
                    status = ResolvedMedia.GalleryStatus.PARTIAL;
                }
            }
            return status;
        }
    }

    private record Gallery(List<PreparedGallery> items, ResolvedMedia.GalleryStatus status) {}

    boolean prepareImage(WorkspaceId workspace, Target target, Map<Target, Boolean> resolved, long[] total) {
        if (resolved.containsKey(target)) {
            return resolved.get(target);
        }
        long allowance = imageAllowance(target);
        if (allowance > PAGE_IMAGE_BYTES - total[0]) {
            resolved.put(target, false);
            return false;
        }
        var reservation = memory.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES);
        if (reservation.isEmpty()) {
            resolved.put(target, false);
            return false;
        }
        var scope = reservation.orElseThrow();
        // Failed reads consume their allowance; managed reads settle to actual bytes only after success.
        total[0] += allowance;
        try (var producer = scope.producer()) {
            AssetBytes image = bytes(workspace, target);
            total[0] -= allowance - image.size();
            resolved.put(target, true);
            return true;
        } catch (AssetStorageException unavailable) {
            resolved.put(target, false);
            if (unavailable.reason() == AssetStorageException.Reason.UNAVAILABLE) {
                throw unavailable;
            }
            return false;
        } catch (ContentRepositoryException unavailable) {
            resolved.put(target, false);
            throw unavailable;
        } finally {
            scope.responseComplete();
        }
    }

    static long imageAllowance(Target target) {
        return target instanceof Git git ? git.blob().size() : ManagedBlobStore.MAX_UPLOAD_BYTES;
    }

    Optional<Target> target(
            WorkspaceId workspace, String commit, String path, String authored, RepositoryMediaSnapshot catalog) {
        if (authored.startsWith("managed:")) {
            String[] fields = authored.split(":", -1);
            if (fields.length != 3) {
                return Optional.empty();
            }
            try {
                UUID id = UUID.fromString(fields[1]);
                if (!id.toString().equals(fields[1])) {
                    return Optional.empty();
                }
                return Optional.of(new Managed(new ManagedAssetReference(id, fields[2])));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        if (commit == null) {
            return Optional.empty();
        }
        return MarkdownDestinations.path(path, authored)
                .filter(value -> !value.isEmpty())
                .flatMap(value -> {
                    var media = catalog == null ? null : catalog.index().files().get(value);
                    if (media != null) {
                        return Optional.of(new Indexed(
                                commit, value, media, catalog.publicPaths().contains(value)));
                    }
                    return blobs.find(workspace, commit, value).map(Git::new).map(target -> (Target) target);
                });
    }

    RepositoryMediaSnapshot availableMedia(WorkspaceId workspace, String commit) {
        try {
            return Objects.requireNonNull(blobs.media(workspace, commit));
        } catch (ContentRepositoryException unavailable) {
            // Git paths retain their independent immutable-object and publication checks.
            return null;
        }
    }

    AssetBytes bytes(WorkspaceId workspace, Target target) {
        if (target instanceof Indexed value) {
            var entry = value.media();
            ManagedImage image =
                    managed.get().read(workspace, new ManagedAssetReference(entry.assetId(), entry.revision()));
            if (image.asset().size() != entry.size()
                    || !image.asset().mediaType().equals(entry.mediaType())) {
                throw notFound();
            }
            return new AssetBytes(
                    new AssetSource.Repository(Optional.of(value.commit()), value.path()),
                    entry.revision(),
                    entry.mediaType(),
                    image.bytes());
        }
        if (target instanceof Managed value) {
            ManagedImage image = managed.get().read(workspace, value.reference());
            return new AssetBytes(
                    new AssetSource.Managed(value.reference()),
                    value.reference().revision(),
                    image.asset().mediaType(),
                    image.bytes());
        }
        RepositoryBlob blob = ((Git) target).blob();
        if (!blob.workspaceId().equals(workspace)) {
            throw notFound();
        }
        var image = cache.get(blob, () -> blobs.read(blob));
        return new AssetBytes(
                new AssetSource.Repository(Optional.of(blob.commit()), blob.path()),
                blob.objectId(),
                image.mediaType(),
                image.bytes());
    }

    static String fragment(String href) {
        int start = href.indexOf('#');
        if (start < 0) {
            return "";
        }
        String value = href.substring(start);
        return value.length() <= 256 && value.codePoints().noneMatch(Character::isISOControl) ? value : "";
    }

    sealed interface Target permits Managed, Git, Indexed {}

    record Indexed(String commit, String path, RepositoryMediaIndex.Media media, boolean publicPath)
            implements Target {}

    static String downloadUrl(WorkspaceId workspace, boolean publicOnly, String commit, String route, String path) {
        String prefix = publicOnly ? "/api/public/media?" : WorkspaceHttpRoutes.admin(workspace) + "/media?";
        return prefix + "commit=" + commit + "&path=" + query(path)
                + (publicOnly ? "&route=" + query(route) + "&workspace=" + workspace : "");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Managed(ManagedAssetReference reference) implements Target {}

    record Git(RepositoryBlob blob) implements Target {}

    /** Prepared pages retain descriptors and text only; all image working sets have been released. */
    record PreparedMedia(
            String body,
            String commit,
            Map<String, String> links,
            Map<String, String> downloads,
            Map<String, Target> images,
            List<PreparedGallery> gallery,
            ResolvedMedia.GalleryStatus galleryStatus) {}

    record PreparedGallery(Target target, String alt) {}
}
