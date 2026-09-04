package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Private repository queries share current workspace authorization across browser and MCP entry points. */
public final class AuthorizedRepositoryReader {
    private final AuthService auth;
    private final RepositoryContentReader reader;

    public AuthorizedRepositoryReader(AuthService auth, RepositoryContentReader reader) {
        this.auth = auth;
        this.reader = reader;
    }

    public RepositoryTree readTree(AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        return reader.readTree(workspace, commit);
    }

    public RepositoryFile getFile(AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit, String path) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        return reader.getFile(workspace, commit, path);
    }

    public SearchPage search(
            AuthPrincipal actor,
            WorkspaceId workspace,
            Optional<String> commit,
            String query,
            String tag,
            Instant from,
            Instant to,
            int offset,
            int limit) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        if (query == null
                || tag == null
                || query.length() > 200
                || tag.length() > 64
                || offset < 0
                || offset > 10_000
                || limit < 1
                || limit > 100
                || (from != null && to != null && from.isAfter(to))) {
            throw new IllegalArgumentException("search exceeds its bounds or has an invalid date range");
        }
        RepositoryTree tree = reader.readTree(workspace, commit);
        List<RepositoryDocument> matches = tree.documents().stream()
                .filter(document -> query.isEmpty()
                        || document.title().contains(query)
                        || document.body().contains(query))
                .filter(document -> tag.isEmpty() || document.tags().contains(tag))
                .filter(document -> from == null || !document.createdAt().isBefore(from))
                .filter(document -> to == null || !document.createdAt().isAfter(to))
                .toList();
        return new SearchPage(
                tree.commit().orElse(null),
                matches.stream()
                        .skip(offset)
                        .limit(limit)
                        .map(document -> new SearchHit(
                                document.file().path(),
                                document.title(),
                                document.tags(),
                                document.createdAt(),
                                document.updatedAt(),
                                snippet(document.body(), query)))
                        .toList(),
                matches.size(),
                offset,
                limit);
    }

    private static String snippet(String body, String query) {
        int match = query.isEmpty() ? 0 : Math.max(0, body.indexOf(query));
        int start = Math.max(0, match - 60);
        int end = Math.min(body.length(), start + 240);
        if (start > 0 && Character.isLowSurrogate(body.charAt(start))) start--;
        if (end < body.length() && end > 0 && Character.isHighSurrogate(body.charAt(end - 1))) end--;
        return body.substring(start, end);
    }

    public record SearchHit(
            String path, String title, List<String> tags, Instant createdAt, Instant updatedAt, String snippet) {}

    public record SearchPage(String commit, List<SearchHit> items, int total, int offset, int limit) {}
}
