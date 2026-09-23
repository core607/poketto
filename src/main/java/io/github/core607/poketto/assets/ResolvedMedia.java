package io.github.core607.poketto.assets;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Only mapped destinations may become local links or images; absent image mappings never fall back to authored URLs. */
public record ResolvedMedia(
        String body,
        String commit,
        Map<String, String> links,
        Map<String, String> downloads,
        Map<String, String> playback,
        Map<String, String> images,
        List<GalleryImage> gallery,
        GalleryStatus galleryStatus) {
    public ResolvedMedia {
        links = Map.copyOf(links);
        downloads = Map.copyOf(downloads);
        playback = Map.copyOf(playback);
        images = Map.copyOf(images);
        gallery = List.copyOf(gallery);
        Objects.requireNonNull(galleryStatus);
    }

    public enum GalleryStatus {
        COMPLETE,
        PARTIAL,
        UNAVAILABLE
    }

    public record GalleryImage(String src, String original, String alt) {}
}
