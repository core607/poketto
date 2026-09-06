package io.github.core607.poketto.assets;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;

public record ResolvedPublicDocument(PublicContentSnapshot snapshot, PublicArticle article, ResolvedMedia media) {}
