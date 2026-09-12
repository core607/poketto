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

    public RepositoryDirectoryPage listDirectory(
            AuthPrincipal actor, WorkspaceId workspace, Optional<String> commit, String path, int offset, int limit) {
        auth.authorize(actor, workspace, Capability.READ_PRIVATE);
        return reader.listDirectory(workspace, commit, path, offset, limit);
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
        var search = new DocumentSearch(query, tag, from, to, offset, limit);
        RepositoryTree tree = reader.readTree(workspace, commit);
        List<RepositoryDocument> matches = tree.documents().stream()
                .filter(document ->
                        search.matches(document.title(), document.body(), document.tags(), document.createdAt()))
                .toList();
        return new SearchPage(
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
                limit);
    }

    public record SearchHit(
            String path, String title, List<String> tags, Instant createdAt, Instant updatedAt, String snippet) {}

    public record SearchPage(String commit, List<SearchHit> items, int total, int offset, int limit) {}
}
