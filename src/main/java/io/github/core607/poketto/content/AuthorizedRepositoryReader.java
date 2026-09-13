package io.github.core607.poketto.content;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                actor, workspace, privateAccess ? Set.of(Capability.READ_PRIVATE) : Set.of(), () -> result);
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
        var search = new DocumentSearch(query, tag, from, to, offset, limit);
        RepositoryTree tree =
                privateAccess ? reader.readTree(workspace, commit) : reader.readPublicTree(workspace, commit);
        List<RepositoryDocument> matches = tree.documents().stream()
                .filter(document ->
                        search.matches(document.title(), document.body(), document.tags(), document.createdAt()))
                .toList();
        return recheck(
                actor,
                workspace,
                privateAccess,
                new SearchPage(
                        tree.commit().orElse(null),
                        search.page(matches).stream()
                                .map(document -> new SearchHit(
                                        document.file().path(),
                                        document.title(),
                                        document.tags(),
                                        document.createdAt(),
                                        document.updatedAt(),
                                        search.snippet(document.body())))
                                .toList(),
                        matches.size(),
                        offset,
                        limit));
    }

    public record SearchHit(
            String path, String title, List<String> tags, Instant createdAt, Instant updatedAt, String snippet) {}

    public record SearchPage(String commit, List<SearchHit> items, int total, int offset, int limit) {}
}
