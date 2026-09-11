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
        boolean privateAccess = privateAccess(actor, workspace);
        return recheck(
                actor,
                workspace,
                privateAccess,
                privateAccess ? reader.readTree(workspace, commit) : reader.readPublicTree(workspace, commit));
    }

    public RepositoryDirectoryPage listDirectory(
            AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit, String path, int offset, int limit) {
        boolean privateAccess = privateAccess(actor, workspace);
        return recheck(
                actor,
                workspace,
                privateAccess,
                privateAccess
                        ? reader.listDirectory(workspace, commit, path, offset, limit)
                        : reader.listPublicDirectory(workspace, commit, path, offset, limit));
    }

    public RepositoryFile getFile(AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit, String path) {
        boolean privateAccess = privateAccess(actor, workspace);
        return recheck(
                actor,
                workspace,
                privateAccess,
                privateAccess
                        ? reader.getFile(workspace, commit, path)
                        : reader.getPublicFile(workspace, commit, path));
    }

    private boolean privateAccess(AuthPrincipal actor, WorkspaceId workspace) {
        return auth.authorize(actor, workspace).capabilities().contains(Capability.READ_PRIVATE);
    }

    private <T> T recheck(AuthPrincipal actor, WorkspaceId workspace, boolean privateAccess, T result) {
        // Repository reads may fetch; recheck after materialization without holding a database lock over the network.
        return auth.withAuthorization(
                actor,
                workspace,
                privateAccess ? java.util.Set.of(Capability.READ_PRIVATE) : java.util.Set.of(),
                () -> result);
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
        boolean privateAccess = privateAccess(actor, workspace);
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
        RepositoryTree tree =
                privateAccess ? reader.readTree(workspace, commit) : reader.readPublicTree(workspace, commit);
        List<RepositoryDocument> matches = tree.documents().stream()
                .filter(document -> query.isEmpty()
                        || document.title().contains(query)
                        || document.body().contains(query))
                .filter(document -> tag.isEmpty() || document.tags().contains(tag))
                .filter(document -> from == null || !document.createdAt().isBefore(from))
                .filter(document -> to == null || !document.createdAt().isAfter(to))
                .toList();
        return recheck(
                actor,
                workspace,
                privateAccess,
                new SearchPage(
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
                        limit));
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
