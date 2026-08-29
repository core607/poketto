package io.github.core607.poketto.content;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validated frontmatter fields in their canonical value form.
 */
public record DocumentMetadata(
        DocumentId id,
        String title,
        DocumentVisibility visibility,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> publishedAt) {

    public DocumentMetadata {
        Objects.requireNonNull(id, "document id must not be null");
        Objects.requireNonNull(title, "document title must not be null");
        Objects.requireNonNull(visibility, "document visibility must not be null");
        Objects.requireNonNull(tags, "document tags must not be null");
        Objects.requireNonNull(createdAt, "document created time must not be null");
        Objects.requireNonNull(updatedAt, "document updated time must not be null");
        Objects.requireNonNull(publishedAt, "document published time must not be null");

        title = title.strip();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("document title must not be empty");
        }
        if (title.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("document title must not contain control characters");
        }
        tags = normalizeTags(tags);
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("document updated time must not precede creation");
        }
        publishedAt.ifPresent(published -> {
            if (published.isBefore(createdAt)) {
                throw new IllegalArgumentException(
                        "document published time must not precede creation");
            }
            if (published.isAfter(updatedAt)) {
                throw new IllegalArgumentException(
                        "document published time must not follow the last update");
            }
        });
    }

    private static List<String> normalizeTags(List<String> candidates) {
        List<String> normalized = new ArrayList<>(candidates.size());
        Set<String> collisionKeys = new HashSet<>();
        for (String candidate : candidates) {
            Objects.requireNonNull(candidate, "document tag must not be null");
            String display = candidate.strip();
            if (display.isEmpty()) {
                throw new IllegalArgumentException("document tag must not be empty");
            }
            String collisionKey = normalizedCollisionKey(display);
            if (!collisionKeys.add(collisionKey)) {
                throw new IllegalArgumentException(
                        "document tags must be unique after Unicode normalization and case folding: "
                                + display);
            }
            normalized.add(display);
        }
        return List.copyOf(normalized);
    }

    private static String caseFold(String value) {
        return value.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static String normalizedCollisionKey(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        return Normalizer.normalize(caseFold(normalized), Normalizer.Form.NFC);
    }
}
