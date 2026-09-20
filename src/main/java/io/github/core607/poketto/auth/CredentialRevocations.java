package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account recovery revokes machine grants without changing memberships or site eligibility. */
final class CredentialRevocations {
    private final JdbcTemplate jdbc;
    private final AuthService auth;

    CredentialRevocations(JdbcTemplate jdbc, AuthService auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    void revoke(UUID account) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Credential revocation requires the account mutation transaction");
        }
        List<UUID> spaces = jdbc.query(
                "select workspace_id from workspaces where workspace_id in (select workspace_id from auth_memberships where account_id=? "
                        + "union select workspace_id from auth_api_keys where account_id=? or created_by=?) order by workspace_id for update",
                (row, number) -> row.getObject(1, UUID.class),
                account,
                account,
                account);
        List<Key> keys = jdbc.query(
                "update auth_api_keys set revoked_at=? where (account_id=? or created_by=?) and revoked_at is null returning key_id,workspace_id",
                (row, number) -> new Key(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
                auth.timestamp(),
                account,
                account);
        for (UUID space : spaces) {
            Set<UUID> ids = keys.stream()
                    .filter(key -> key.workspace().equals(space))
                    .map(Key::id)
                    .collect(Collectors.toSet());
            auth.publishRevocation(new AuthRevocation(new WorkspaceId(space), Set.of(account), ids));
        }
    }

    private record Key(UUID id, UUID workspace) {}
}
