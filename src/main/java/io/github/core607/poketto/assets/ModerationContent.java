package io.github.core607.poketto.assets;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Current repository-public content for site moderation, without private workspace grants. */
public final class ModerationContent {
    private final SitePolicyService policies;
    private final PublicContentSnapshots snapshots;
    private final AssetService assets;
    private final MediaFileService files;

    public ModerationContent(
            SitePolicyService policies, PublicContentSnapshots snapshots, AssetService assets, MediaFileService files) {
        this.policies = policies;
        this.snapshots = snapshots;
        this.assets = assets;
        this.files = files;
    }

    public AuthService.Page<Article> articles(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        policies.requireAdministrator(actor);
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Review pages require a bounded offset and limit");
        }
        return policies.withAdministrator(
                actor,
                () -> snapshots.withCurrent(workspace, snapshot -> {
                    var items = snapshot.articles().stream()
                            .skip(offset)
                            .limit(limit)
                            .map(article -> new Article(article.route(), article.repositoryPath(), article.title()))
                            .toList();
                    return new AuthService.Page<>(items, snapshot.articles().size(), offset, limit);
                }));
    }

    public Document document(AuthPrincipal actor, WorkspaceId workspace, String route) {
        policies.requireAdministrator(actor);
        ResolvedPublicDocument selected =
                assets.publicDocument(workspace, route).orElseThrow(ImageGrants::notFound);
        return policies.withAdministrator(
                actor,
                () -> snapshots.withCurrent(workspace, current -> {
                    if (!current.commit().equals(selected.snapshot().commit())
                            || !current.articles().contains(selected.article())) {
                        throw ImageGrants.notFound();
                    }
                    return new Document(
                            selected.article().title(),
                            selected.article().repositoryPath(),
                            reviewMedia(workspace, selected.media()));
                }));
    }

    public AssetBytes image(AuthPrincipal actor, WorkspaceId workspace, String token) {
        policies.requireAdministrator(actor);
        AssetBytes bytes = assets.readPublicImage(workspace, token);
        return policies.withAdministrator(actor, () -> bytes);
    }

    public MediaFileService.Download download(
            AuthPrincipal actor, WorkspaceId workspace, String commit, String route, String path) {
        return files.referencedDownload(
                snapshots, workspace, commit, route, path, () -> policies.requireAdministrator(actor), false);
    }

    private static ResolvedMedia reviewMedia(WorkspaceId workspace, ResolvedMedia original) {
        String prefix = "/api/auth/site/workspaces/" + workspace + "/review";
        Function<String, String> image = value -> {
            if (!value.startsWith("/api/public/assets/")) {
                throw new IllegalStateException("Review image must originate from the isolated public-image registry");
            }
            return prefix + "/images/" + value.substring("/api/public/assets/".length());
        };
        Function<String, String> download = value -> {
            if (!value.startsWith("/api/public/media?")) {
                throw new IllegalStateException("Review download must originate from a referenced public file");
            }
            return prefix + "/download?" + value.substring("/api/public/media?".length());
        };
        return new ResolvedMedia(
                original.body(),
                original.commit(),
                original.links(),
                rewrite(original.downloads(), download),
                original.playback(),
                rewrite(original.images(), image),
                original.gallery().stream()
                        .map(entry -> new ResolvedMedia.GalleryImage(
                                image.apply(entry.src()), image.apply(entry.original()), entry.alt()))
                        .toList(),
                original.galleryStatus());
    }

    private static Map<String, String> rewrite(Map<String, String> values, Function<String, String> transform) {
        return values.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> transform.apply(entry.getValue())));
    }

    public record Article(String route, String path, String title) {}

    public record Document(String title, String path, ResolvedMedia media) {
        public Document {
            Objects.requireNonNull(media);
        }
    }
}
