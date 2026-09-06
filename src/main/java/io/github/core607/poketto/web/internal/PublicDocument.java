package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.assets.ResolvedMedia;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public Markdown body only; private frontmatter and raw source never cross this mapper. */
record PublicDocument(
        String commit,
        Instant verifiedAt,
        Instant expiresAt,
        String route,
        String title,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        boolean folderPage,
        String body,
        Map<String, String> links,
        Map<String, String> images,
        List<ResolvedMedia.GalleryImage> gallery) {
    static PublicDocument of(PublicArticle article, PublicContentSnapshot snapshot, ResolvedMedia media) {
        return new PublicDocument(
                snapshot.commit().orElse(null),
                snapshot.verifiedAt(),
                snapshot.expiresAt(),
                article.route(),
                article.title(),
                article.tags(),
                article.createdAt(),
                article.updatedAt(),
                article.folderPage(),
                article.body(),
                media.links(),
                media.images(),
                media.gallery());
    }
}
