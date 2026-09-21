package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.GitHubConnectionException;
import io.github.core607.poketto.content.GitHubRepositoryProvisioning.AccessEpochs;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Revocation generations also cover proofs prepared before a workspace binding exists. */
final class GitHubAccessEpochs {
    private final JdbcTemplate jdbc;
    private final String clientId;

    GitHubAccessEpochs(JdbcTemplate jdbc, String clientId) {
        this.jdbc = jdbc;
        this.clientId = clientId;
    }

    AccessEpochs snapshot(long installation, long repository) {
        ensure(installation, repository);
        return read(installation, repository, false);
    }

    void requireCurrent(long installation, long repository, AccessEpochs expected) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GitHub access validation requires a binding transaction");
        }
        ensure(installation, repository);
        if (!read(installation, repository, true).equals(expected)) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
        }
    }

    void revoke(long installation, long repository) {
        jdbc.update("""
                insert into content_github_access_epochs(client_id,installation_id,repository_id,epoch) values (?,?,?,1)
                on conflict(client_id,installation_id,repository_id) do update
                set epoch=content_github_access_epochs.epoch+1
                """, clientId, installation, repository);
    }

    void revokeRepositories(List<Long> repositories) {
        jdbc.update("""
                insert into content_github_access_epochs(client_id,installation_id,repository_id,epoch)
                select ?,0,repository_id,1 from unnest(?::bigint[]) as removed(repository_id) order by repository_id
                on conflict(client_id,installation_id,repository_id) do update
                set epoch=content_github_access_epochs.epoch+1
                """, clientId, new SqlArrayValue("bigint", repositories.toArray()));
    }

    private void ensure(long installation, long repository) {
        jdbc.update("""
                insert into content_github_access_epochs(client_id,installation_id,repository_id)
                values (?,0,?),(?,?,0) on conflict do nothing
                """, clientId, repository, clientId, installation);
    }

    private AccessEpochs read(long installation, long repository, boolean lock) {
        List<Long> versions = jdbc.query(
                "select epoch from content_github_access_epochs where client_id=? and ((installation_id=? and repository_id=0) or (installation_id=0 and repository_id=?)) order by installation_id"
                        + (lock ? " for update" : ""),
                (row, index) -> row.getLong(1),
                clientId,
                installation,
                repository);
        if (versions.size() != 2) {
            throw new GitHubConnectionException(GitHubConnectionException.Code.AUTHORIZATION_CHANGED);
        }
        return new AccessEpochs(versions.getLast(), versions.getFirst());
    }
}
