package io.github.core607.poketto.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derived once with a public snapshot; references never establish publication authority. */
public final class PublicCollections {
    private final Map<String, Landing> landings;
    private final Map<String, List<Membership>> memberships;

    public PublicCollections(List<PublicArticle> articles) {
        var routes = new HashMap<String, String>();
        var references = new HashMap<String, Reference>();
        for (PublicArticle article : articles) {
            routes.put(article.repositoryPath(), article.route());
            references.put(article.route(), new Reference(article.route(), article.title()));
        }
        var selected = new LinkedHashMap<String, Landing>();
        Set<String> publicRoutes = Set.copyOf(routes.values());
        var containing = new HashMap<String, List<Membership>>();
        for (PublicArticle article : articles) {
            if (!article.folderPage()) {
                continue;
            }
            Landing landing = landing(article, routes, publicRoutes, references);
            selected.put(article.route(), landing);
            for (int index = 0; index < landing.entries().size(); index++) {
                Reference entry = landing.entries().get(index);
                containing
                        .computeIfAbsent(entry.route(), ignored -> new ArrayList<>())
                        .add(new Membership(
                                references.get(article.route()),
                                index + 1,
                                landing.entries().size(),
                                index == 0 ? null : landing.entries().get(index - 1),
                                index + 1 == landing.entries().size()
                                        ? null
                                        : landing.entries().get(index + 1)));
            }
        }
        landings = Map.copyOf(selected);
        containing.replaceAll((route, entries) -> List.copyOf(entries));
        memberships = Map.copyOf(containing);
    }

    public Navigation forArticle(String route) {
        Landing landing = landings.get(route);
        return new Navigation(
                landing == null ? List.of() : landing.entries(),
                landing == null || landing.available(),
                memberships.getOrDefault(route, List.of()));
    }

    private static Landing landing(
            PublicArticle article,
            Map<String, String> routes,
            Set<String> publicRoutes,
            Map<String, Reference> references) {
        var entries = new LinkedHashMap<String, Reference>();
        try {
            for (String authored : MarkdownDestinations.parse(article.body()).links()) {
                MarkdownDestinations.route(article.repositoryPath(), authored, routes, publicRoutes)
                        .filter(route -> !route.equals(article.route()))
                        .ifPresent(route -> entries.putIfAbsent(route, references.get(route)));
            }
            return new Landing(List.copyOf(entries.values()), true);
        } catch (IllegalArgumentException invalid) {
            return new Landing(List.of(), false);
        }
    }

    private record Landing(List<Reference> entries, boolean available) {}

    public record Reference(String route, String title) {}

    public record Membership(Reference collection, int position, int total, Reference previous, Reference next) {}

    public record Navigation(List<Reference> entries, boolean available, List<Membership> memberships) {}
}
