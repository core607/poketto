package io.github.core607.poketto.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Locks publication before entering a snapshot callback that commits relational state. */
public final class PublicationGuard {
    private final JdbcTemplate jdbc;
    private final WorkspacePublications publications;

    public PublicationGuard(JdbcTemplate jdbc, WorkspacePublications publications) {
        this.jdbc = jdbc;
        this.publications = publications;
    }

    /** Caller must already hold the account policy guard; this lock also excludes membership changes. */
    public void lock(WorkspaceId workspace) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("publication guard requires a transaction");
        }
        if (jdbc.query(
                        "select workspace_id from workspaces where workspace_id=? for share",
                        (row, number) -> row.getObject(1),
                        workspace.value())
                .isEmpty()) {
            throw new PublicationUnavailableException();
        }
        publications.requireEnabled(workspace);
    }
}
