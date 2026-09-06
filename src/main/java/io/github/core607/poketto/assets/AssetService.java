package io.github.core607.poketto.assets;

import io.github.core607.poketto.assets.internal.MarkdownDestinations;
import io.github.core607.poketto.assets.internal.RepositoryImageCache;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryMarkdownInspector;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared browser/MCP authorization, exact image reads, and snapshot-bound rendering references. */
public final class AssetService {
    private static final Logger log = LoggerFactory.getLogger(AssetService.class);
    private static final Duration GRANT_LIFETIME = Duration.ofMinutes(5);
    private static final Duration MINIMUM_REUSABLE_LIFETIME = Duration.ofMinutes(1);
    private static final Duration CAPACITY_WARNING_INTERVAL = Duration.ofMinutes(1);
    private static final long PAGE_IMAGE_BYTES = 128L * 1024 * 1024;
    private static final long INVENTORY_IMAGE_BYTES = 256L * 1024 * 1024;
    private final AuthService auth;
    private final RepositoryContentReader content;
    private final RepositoryBlobReader blobs;
    private final RepositoryMarkdownInspector markdown;
    private final PublicContentSnapshots snapshots;
    private final Supplier<ManagedBlobStore> managed;
    private final RepositoryImageCache cache;
    private final Clock clock;
    private final int maxGrants;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new HashMap<>();
    private final Map<GrantKey, String> reusable = new HashMap<>();
    private Instant capacityWarningAt;
    private long capacityOmissions;

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
            Clock clock) {
        if (maxGrants < 128 || maxGrants > 100_000)
            throw new IllegalArgumentException("image grant capacity must be 128 to 100000");
        this.auth = auth;
        this.content = content;
        this.blobs = blobs;
        this.markdown = markdown;
        this.snapshots = snapshots;
        this.managed = managed;
        this.cache = new RepositoryImageCache(cacheDirectory, cacheBytes);
        this.clock = clock;
        this.maxGrants = maxGrants;
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
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> {
            if (source instanceof AssetSource.Managed managedSource)
                return bytes(workspace, new Managed(managedSource.reference()));
            AssetSource.Repository repositorySource = (AssetSource.Repository) source;
            String commit =
                    blobs.selectCommit(workspace, repositorySource.commit()).orElseThrow(AssetService::notFound);
            RepositoryBlob blob =
                    blobs.find(workspace, commit, repositorySource.path()).orElseThrow(AssetService::notFound);
            return bytes(workspace, new Git(blob));
        });
    }

    public RepositoryImagePage repositoryImages(
            AuthPrincipal actor,
            WorkspaceId workspace,
            Optional<String> requested,
            String prefix,
            int offset,
            int limit) {
        if (offset < 0 || offset > 1000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("repository image page exceeds its bounds");
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> {
            Optional<String> commit = blobs.selectCommit(workspace, requested);
            if (commit.isEmpty()) return new RepositoryImagePage(null, List.of(), 0, offset, limit, List.of());
            List<RepositoryImagePage.Item> images = new ArrayList<>();
            List<RepositoryDiagnostic> diagnostics = new ArrayList<>();
            long totalBytes = 0;
            for (RepositoryBlob blob : blobs.images(workspace, commit.orElseThrow(), prefix)) {
                totalBytes += blob.size();
                if (totalBytes > INVENTORY_IMAGE_BYTES)
                    throw new ContentRepositoryException("repository image inventory byte bound exceeded");
                try {
                    AssetBytes image = bytes(workspace, new Git(blob));
                    images.add(new RepositoryImagePage.Item(blob.path(), image.mediaType(), blob.size()));
                } catch (AssetStorageException | ContentRepositoryException invalid) {
                    diagnostics.add(new RepositoryDiagnostic(
                            blob.path(), "INVALID_IMAGE", "image signature, dimensions or bytes are unavailable"));
                }
            }
            return new RepositoryImagePage(
                    commit.orElseThrow(),
                    images.stream().skip(offset).limit(limit).toList(),
                    images.size(),
                    offset,
                    limit,
                    diagnostics);
        });
    }

    public ResolvedMedia preview(
            AuthPrincipal actor, WorkspaceId workspace, String path, String source, Optional<String> requested) {
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> {
            var draft = markdown.inspect(path, source);
            Optional<String> commit = blobs.selectCommit(workspace, requested);
            Map<String, String> routes = new HashMap<>();
            if (commit.isPresent()) {
                for (var document : content.readTree(workspace, commit).documents()) {
                    routes.put(
                            document.file().path(),
                            "/admin?path="
                                    + java.net.URLEncoder.encode(
                                            document.file().path(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return resolve(
                    workspace,
                    path,
                    draft.body(),
                    commit.orElse(null),
                    draft.folderPage(),
                    routes,
                    false,
                    actorKey(actor),
                    clock.instant().plus(GRANT_LIFETIME));
        });
    }

    /**
     * Article lookup, current-public validation and every grant mint share the installation lock.
     * Grant capacity exhaustion omits affected image mappings without failing the article.
     */
    public Optional<ResolvedPublicDocument> publicDocument(WorkspaceId workspace, String route) {
        return snapshots.withCurrent(workspace, snapshot -> {
            var article = snapshot.articles().stream()
                    .filter(item -> item.route().equals(route))
                    .findFirst();
            if (article.isEmpty()) return Optional.empty();
            Map<String, String> routes = new HashMap<>();
            for (var item : snapshot.articles()) routes.put(item.repositoryPath(), item.route());
            var value = article.orElseThrow();
            ResolvedMedia media = resolve(
                    workspace,
                    value.repositoryPath(),
                    value.body(),
                    snapshot.commit().orElse(null),
                    value.folderPage(),
                    routes,
                    true,
                    "",
                    snapshot.expiresAt());
            if (!clock.instant().isBefore(snapshot.expiresAt()))
                throw new ContentRepositoryException("public snapshot expired during image resolution");
            return Optional.of(new ResolvedPublicDocument(snapshot, value, media));
        });
    }

    public AssetBytes readPublicImage(WorkspaceId workspace, String token) {
        Grant grant = grant(workspace, token, "");
        AssetBytes image = bytes(workspace, grant.key().target());
        grant(workspace, token, "");
        return image;
    }

    /** An opaque private URL never substitutes for the current identity or current workspace authority. */
    public AssetBytes readPrivateImage(AuthPrincipal actor, WorkspaceId workspace, String token) {
        return auth.withAuthorization(actor, workspace, Set.of(Capability.READ_PRIVATE), () -> {
            Grant grant = grant(workspace, token, actorKey(actor));
            AssetBytes image = bytes(workspace, grant.key().target());
            grant(workspace, token, actorKey(actor));
            return image;
        });
    }

    private ResolvedMedia resolve(
            WorkspaceId workspace,
            String path,
            String body,
            String commit,
            boolean folder,
            Map<String, String> routes,
            boolean publicOnly,
            String actor,
            Instant expires) {
        var destinations = MarkdownDestinations.parse(body);
        Map<String, String> links = new LinkedHashMap<>();
        for (String authored : destinations.links()) {
            if (authored.startsWith("#")
                    && authored.length() <= 256
                    && authored.codePoints().noneMatch(Character::isISOControl)) {
                links.put(authored, authored);
                continue;
            }
            MarkdownDestinations.path(path, authored).ifPresent(target -> {
                String selected = routes.get(target);
                if (selected == null) selected = routes.get(target.isEmpty() ? "index.md" : target + "/index.md");
                if (selected == null) selected = routes.get(target + ".md");
                if (selected == null && publicOnly && routes.containsValue("/" + target)) selected = "/" + target;
                if (selected != null) links.put(authored, selected + fragment(authored));
            });
        }
        Map<String, String> images = new LinkedHashMap<>();
        Map<Target, String> resolved = new HashMap<>();
        Set<String> inlinePaths = new HashSet<>();
        long[] bytes = {0};
        for (String authored : destinations.images()) {
            Optional<Target> selected = target(workspace, commit, path, authored);
            if (selected.isEmpty()) continue;
            Target target = selected.orElseThrow();
            if (target instanceof Git git) {
                inlinePaths.add(git.blob().path());
                if (publicOnly && !git.blob().publicPath()) continue;
            }
            String url = resolveImage(workspace, path, commit, actor, expires, target, resolved, bytes);
            if (url != null) images.put(authored, url);
        }
        List<ResolvedMedia.GalleryImage> gallery = new ArrayList<>();
        if (folder && commit != null) {
            for (RepositoryBlob blob : blobs.siblings(workspace, commit, path, 128, publicOnly)) {
                if (inlinePaths.contains(blob.path()) || (publicOnly && !blob.publicPath())) continue;
                String url = resolveImage(workspace, path, commit, actor, expires, new Git(blob), resolved, bytes);
                if (url != null)
                    gallery.add(new ResolvedMedia.GalleryImage(
                            url, blob.path().substring(blob.path().lastIndexOf('/') + 1)));
            }
        }
        return new ResolvedMedia(body, commit, links, images, gallery);
    }

    private String resolveImage(
            WorkspaceId workspace,
            String page,
            String commit,
            String actor,
            Instant expires,
            Target target,
            Map<Target, String> resolved,
            long[] total) {
        if (resolved.containsKey(target)) return resolved.get(target);
        try {
            AssetBytes image = bytes(workspace, target);
            total[0] += image.size();
            if (total[0] > PAGE_IMAGE_BYTES)
                throw new ContentRepositoryException("page image resolution byte bound exceeded");
            Optional<String> token = mint(new GrantKey(workspace, commit, page, target, actor), expires);
            if (token.isEmpty()) {
                resolved.put(target, null);
                return null;
            }
            String url = (actor.isEmpty() ? "/api/public/assets/" : "/api/admin/assets/images/") + token.orElseThrow();
            resolved.put(target, url);
            return url;
        } catch (AssetStorageException unavailable) {
            resolved.put(target, null);
            return null;
        }
    }

    private Optional<Target> target(WorkspaceId workspace, String commit, String path, String authored) {
        if (authored.startsWith("managed:")) {
            String[] fields = authored.split(":", -1);
            if (fields.length != 3) return Optional.empty();
            try {
                UUID id = UUID.fromString(fields[1]);
                if (!id.toString().equals(fields[1])) return Optional.empty();
                return Optional.of(new Managed(new ManagedAssetReference(id, fields[2])));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        if (commit == null) return Optional.empty();
        return MarkdownDestinations.path(path, authored)
                .filter(value -> !value.isEmpty())
                .flatMap(value -> blobs.find(workspace, commit, value))
                .map(Git::new);
    }

    private AssetBytes bytes(WorkspaceId workspace, Target target) {
        if (target instanceof Managed value) {
            ManagedImage image = managed.get().read(workspace, value.reference());
            return new AssetBytes(
                    new AssetSource.Managed(value.reference()),
                    value.reference().revision(),
                    image.asset().mediaType(),
                    image.bytes());
        }
        RepositoryBlob blob = ((Git) target).blob();
        if (!blob.workspaceId().equals(workspace)) throw notFound();
        var image = cache.get(blob, () -> blobs.read(blob));
        return new AssetBytes(
                new AssetSource.Repository(Optional.of(blob.commit()), blob.path()),
                blob.objectId(),
                image.mediaType(),
                image.bytes());
    }

    private synchronized Optional<String> mint(GrantKey key, Instant snapshotExpires) {
        Instant now = clock.instant();
        purge(now);
        Instant expires =
                now.plus(GRANT_LIFETIME).isBefore(snapshotExpires) ? now.plus(GRANT_LIFETIME) : snapshotExpires;
        if (!now.isBefore(expires)) throw notFound();
        String token = reusable.get(key);
        if (token != null) {
            Instant previousExpires = grants.get(token).expires();
            // A snapshot near expiry cannot give a replacement token a longer useful lifetime.
            if (!previousExpires.isAfter(expires)
                    && (previousExpires.equals(expires)
                            || !previousExpires.isBefore(now.plus(MINIMUM_REUSABLE_LIFETIME))))
                return Optional.of(token);
        }
        if (grants.size() >= maxGrants) {
            warnCapacity(now);
            return Optional.empty();
        }
        byte[] entropy = new byte[32];
        do {
            random.nextBytes(entropy);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        } while (grants.containsKey(token));
        grants.put(token, new Grant(key, now, expires));
        reusable.put(key, token);
        return Optional.of(token);
    }

    /** Called under the grant lock; warnings contain no workspace, path, identity or token. */
    private void warnCapacity(Instant now) {
        if (capacityOmissions < Long.MAX_VALUE) capacityOmissions++;
        if (capacityWarningAt == null
                || now.isBefore(capacityWarningAt)
                || !now.isBefore(capacityWarningAt.plus(CAPACITY_WARNING_INTERVAL))) {
            log.warn(
                    "Image grant capacity exhausted; omitted {} image authorization(s) since the previous warning (capacity {})",
                    capacityOmissions,
                    maxGrants);
            capacityOmissions = 0;
            capacityWarningAt = now;
        }
    }

    private synchronized Grant grant(WorkspaceId workspace, String token, String actor) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw notFound();
        Instant now = clock.instant();
        purge(now);
        Grant grant = grants.get(token);
        if (grant == null
                || !grant.key().workspace().equals(workspace)
                || !grant.key().actor().equals(actor)) throw notFound();
        return grant;
    }

    private void purge(Instant now) {
        var iterator = grants.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Grant grant = entry.getValue();
            if (now.isBefore(grant.issued()) || !now.isBefore(grant.expires())) {
                reusable.remove(grant.key(), entry.getKey());
                iterator.remove();
            }
        }
    }

    private static String actorKey(AuthPrincipal actor) {
        return actor.kind() + ":" + actor.subjectId() + ":" + actor.accountId();
    }

    private static String fragment(String href) {
        int start = href.indexOf('#');
        if (start < 0) return "";
        String value = href.substring(start);
        return value.length() <= 256 && value.codePoints().noneMatch(Character::isISOControl) ? value : "";
    }

    private static AssetStorageException notFound() {
        return new AssetStorageException(AssetStorageException.Reason.NOT_FOUND);
    }

    private sealed interface Target permits Managed, Git {}

    private record Managed(ManagedAssetReference reference) implements Target {}

    private record Git(RepositoryBlob blob) implements Target {}

    private record GrantKey(WorkspaceId workspace, String commit, String page, Target target, String actor) {}

    private record Grant(GrantKey key, Instant issued, Instant expires) {}
}
