package io.github.core607.poketto.assets;

import java.util.List;
import java.util.Map;

/** Only mapped destinations may become local links or images; absent image mappings never fall back to authored URLs. */
public record ResolvedMedia(
        String body, String commit, Map<String, String> links, Map<String, String> images, List<GalleryImage> gallery) {
    public ResolvedMedia {
        links = Map.copyOf(links);
        images = Map.copyOf(images);
        gallery = List.copyOf(gallery);
    }

    public record GalleryImage(String src, String alt) {}
}
