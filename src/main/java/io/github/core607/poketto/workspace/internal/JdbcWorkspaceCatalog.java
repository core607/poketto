package io.github.core607.poketto.workspace.internal;

import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

final class JdbcWorkspaceCatalog implements WorkspaceCatalog {

    private static final String SELECT_COLUMNS = "workspace_id, display_name";
    private static final RowMapper<Workspace> WORKSPACE_ROW = JdbcWorkspaceCatalog::readWorkspace;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcWorkspaceCatalog(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Workspace defaultWorkspace() {
        List<Workspace> matches =
                jdbc.query("select " + SELECT_COLUMNS + " from workspaces where is_default", WORKSPACE_ROW);
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "workspace catalog must contain exactly one default workspace, found " + matches.size());
        }
        return matches.getFirst();
    }

    @Override
    public Optional<Workspace> findById(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspace id must not be null");
        List<Workspace> matches = jdbc.query(
                "select " + SELECT_COLUMNS + " from workspaces where workspace_id = ?",
                WORKSPACE_ROW,
                workspaceId.value());
        return matches.stream().findFirst();
    }

    Workspace ensureDefaultWorkspace() {
        return transactions.execute(status -> {
            // The table lock serializes first-start initialization across application processes.
            jdbc.execute("lock table workspaces in share row exclusive mode");
            List<Workspace> existing =
                    jdbc.query("select " + SELECT_COLUMNS + " from workspaces where is_default", WORKSPACE_ROW);
            if (existing.size() > 1) {
                throw new IllegalStateException("workspace catalog contains more than one default workspace");
            }
            if (existing.size() == 1) {
                return existing.getFirst();
            }

            Workspace created = new Workspace(WorkspaceId.random(), "Default workspace");
            jdbc.update(
                    "insert into workspaces (workspace_id, display_name, is_default) " + "values (?, ?, true)",
                    created.id().value(),
                    created.displayName());
            return created;
        });
    }

    private static Workspace readWorkspace(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Workspace(
                new WorkspaceId(resultSet.getObject("workspace_id", UUID.class)), resultSet.getString("display_name"));
    }
}
