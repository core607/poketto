package io.github.core607.poketto.web.internal;

import io.github.core607.poketto.content.PublicArticle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** Four slots per space: authored choice, recent, tag diversity, and random exploration. */
final class DiscoverySelection {
    static final int LIMIT = 4;

    private DiscoverySelection() {}

    static List<PublicArticle> select(List<PublicArticle> articles, String tag, Random random) {
        var selected = new ArrayList<PublicArticle>();
        Predicate<PublicArticle> remaining = article ->
                !selected.contains(article) && (tag.isEmpty() || article.tags().contains(tag));
        choose(articles, remaining.and(PublicArticle::featured), (left, right) -> 0, random)
                .ifPresent(selected::add);
        choose(articles, remaining, Comparator.comparing(PublicArticle::createdAt), random)
                .ifPresent(selected::add);
        Set<String> tags = new HashSet<>();
        selected.forEach(article -> tags.addAll(article.tags()));
        choose(
                        articles,
                        remaining,
                        Comparator.comparingLong(article -> article.tags().stream()
                                .filter(value -> !tags.contains(value))
                                .count()),
                        random)
                .ifPresent(selected::add);
        while (selected.size() < LIMIT) {
            var next = choose(articles, remaining, (left, right) -> 0, random);
            if (next.isEmpty()) {
                break;
            }
            selected.add(next.orElseThrow());
        }
        return List.copyOf(selected);
    }

    private static Optional<PublicArticle> choose(
            List<PublicArticle> articles,
            Predicate<PublicArticle> eligible,
            Comparator<PublicArticle> order,
            Random random) {
        PublicArticle best = null;
        int ties = 0;
        for (PublicArticle article : articles) {
            if (!eligible.test(article)) {
                continue;
            }
            int comparison = best == null ? 1 : order.compare(article, best);
            if (comparison > 0) {
                best = article;
                ties = 1;
            } else if (comparison == 0 && random.nextInt(++ties) == 0) {
                best = article;
            }
        }
        return Optional.ofNullable(best);
    }
}
