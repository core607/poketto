package io.github.core607.poketto.workspace.internal;

import io.github.core607.poketto.workspace.PublicAuthorNames;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class JdbcWorkspacePublications implements WorkspacePublications {
    private static final String COLUMNS = "workspace_id,public_slug,display_name,public_delivery,public_author_name";
    private final JdbcTemplate jdbc;

    JdbcWorkspacePublications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Publication> findPublished(String slug) {
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{1,62}[a-z0-9]")) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        "select " + COLUMNS + " from workspaces where public_slug=? and public_delivery",
                        JdbcWorkspacePublications::read,
                        slug)
                .stream()
                .findFirst();
    }

    @Override
    public List<Publication> publishedAfter(Optional<WorkspaceId> after, int limit) {
        Objects.requireNonNull(after, "publication cursor must be present");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Publication page limit must be between 1 and 100");
        }
        if (after.isEmpty()) {
            return jdbc.query(
                    "select " + COLUMNS + " from workspaces where public_delivery order by workspace_id limit ?",
                    JdbcWorkspacePublications::read,
                    limit);
        }
        return jdbc.query(
                "select " + COLUMNS
                        + " from workspaces where public_delivery and workspace_id>? order by workspace_id limit ?",
                JdbcWorkspacePublications::read,
                after.orElseThrow().value(),
                limit);
    }

    @Override
    public Publication settings(WorkspaceId workspace) {
        return jdbc
                .query(
                        "select " + COLUMNS + " from workspaces where workspace_id=?",
                        JdbcWorkspacePublications::read,
                        workspace.value())
                .stream()
                .findFirst()
                .orElseThrow(PublicationUnavailableException::new);
    }

    @Override
    public Publication setEnabled(WorkspaceId workspace, boolean enabled) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Publication changes require an owner-authorization transaction");
        }
        if (jdbc.update("update workspaces set public_delivery=? where workspace_id=?", enabled, workspace.value())
                != 1) {
            throw new PublicationUnavailableException();
        }
        return settings(workspace);
    }

    @Override
    public void requireEnabled(WorkspaceId workspace) {
        Boolean enabled = jdbc.queryForObject(
                "select exists(select 1 from workspaces where workspace_id=? and public_delivery)",
                Boolean.class,
                workspace.value());
        if (!Boolean.TRUE.equals(enabled)) {
            throw new PublicationUnavailableException();
        }
    }

    @Override
    public Publication setAuthorName(WorkspaceId workspace, String name) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Public author changes require an owner-authorization transaction");
        }
        String normalized = PublicAuthorNames.normalize(name);
        if (jdbc.update(
                        "update workspaces set public_author_name=? where workspace_id=?",
                        normalized,
                        workspace.value())
                != 1) {
            throw new PublicationUnavailableException();
        }
        return settings(workspace);
    }

    private static Publication read(ResultSet row, int number) throws SQLException {
        return new Publication(
                new WorkspaceId(row.getObject("workspace_id", UUID.class)),
                row.getString("public_slug"),
                row.getString("display_name"),
                row.getBoolean("public_delivery"),
                row.getString("public_author_name"));
    }
}
