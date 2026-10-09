package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.content.PublicArticle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

/** Public tags supply the scenery; only the account's discovered names are remembered. */
final class PlazaStreet {
    private final Clock clock;

    PlazaStreet(Clock clock) {
        this.clock = clock;
    }

    Street look(
            List<PublicPlazaReads.Source> sources,
            Set<String> discovered,
            int offset,
            ToLongFunction<String> visitors) {
        List<Tag> tags = tags(sources);
        int end = Math.min(tags.size(), offset + 10);
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Instant midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Stall> stalls = offset >= tags.size()
                ? List.of()
                : tags.subList(offset, end).stream()
                        .map(tag -> new Stall(
                                handle(tag.name()),
                                !discovered.contains(tag.name()) && visitors.applyAsLong(tag.name()) < 3
                                        ? "#???"
                                        : tag.name(),
                                tag.articles(),
                                !tag.updatedAt().isBefore(midnight)))
                        .toList();
        String[] places = {"under the blue awning", "beside a folded map", "at the quiet corner"};
        String[] sayings = {
            "A question can be a small beginning.", "Someone left a light on.", "Look inside another pocket."
        };
        int selection = Math.floorMod(day.toEpochDay(), places.length);
        return new Street(
                stalls,
                tags.size(),
                end < tags.size() ? end : null,
                new Well(places[selection], sayings[selection], day.toString()));
    }

    private static List<Tag> tags(List<PublicPlazaReads.Source> sources) {
        var values = new TreeMap<String, Tag>();
        for (PublicPlazaReads.Source source : sources) {
            for (PublicArticle article : source.snapshot().articles()) {
                for (String tag : article.tags()) {
                    var next = new Tag(tag, 1, article.updatedAt());
                    values.merge(
                            tag,
                            next,
                            (left, right) -> new Tag(
                                    tag,
                                    left.articles() + 1,
                                    left.updatedAt().isAfter(right.updatedAt())
                                            ? left.updatedAt()
                                            : right.updatedAt()));
                }
            }
        }
        return values.values().stream()
                .sorted(Comparator.comparing(Tag::updatedAt).reversed().thenComparing(Tag::name))
                .toList();
    }

    static String tag(List<PublicPlazaReads.Source> sources, String selector) {
        return sources.stream()
                .flatMap(source -> source.snapshot().articles().stream())
                .flatMap(article -> article.tags().stream())
                .distinct()
                .filter(tag -> tag.equals(selector) || handle(tag).equals(selector))
                .findFirst()
                .orElse(selector);
    }

    private static String handle(String tag) {
        try {
            return "@"
                    + HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required for stall handles", impossible);
        }
    }

    private record Tag(String name, int articles, Instant updatedAt) {}

    record Stall(String id, String label, int articles, boolean lit) {}

    record Well(String position, String saying, String day) {}

    record Street(List<Stall> stalls, int total, Integer nextOffset, Well well) {}
}
