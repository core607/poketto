package io.github.core607.poketto.content;

import java.util.List;

/** A bounded filename-ordered prefix; partial never reports excluded paths or their count. */
public record SiblingImages(List<RepositoryBlob> items, boolean partial) {
    public SiblingImages {
        items = List.copyOf(items);
    }
}
