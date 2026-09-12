package io.github.core607.poketto.assets;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;

/**
 * Everything one public page needs, resolved from a single snapshot. Article and media come
 * from that same snapshot so a page cannot mix text from one commit with images from
 * another.
 */
public record ResolvedPublicDocument(PublicContentSnapshot snapshot, PublicArticle article, ResolvedMedia media) {}
