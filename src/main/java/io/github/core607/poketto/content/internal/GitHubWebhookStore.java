package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.GitHubWebhookException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Delivery receipt, revocation tombstones and affected credentials commit or roll back together. */
final class GitHubWebhookStore {
    private final JdbcTemplate jdbc;
    private final String clientId;
    private final GitHubAccessEpochs epochs;
    private final TransactionTemplate transactions;

    GitHubWebhookStore(JdbcTemplate jdbc, PlatformTransactionManager manager, String clientId) {
        this.jdbc = jdbc;
        this.clientId = clientId;
        this.epochs = new GitHubAccessEpochs(jdbc, clientId);
        this.transactions = new TransactionTemplate(manager);
        transactions.setTimeout(5);
    }

    void accept(UUID delivery, String event, GitHubWebhookPayload payload) {
        transactions.executeWithoutResult(status -> {
            jdbc.execute("set local lock_timeout = '2s'");
            int inserted = jdbc.update("""
                    insert into content_github_webhook_deliveries(client_id,delivery_id) values (?,?)
                    on conflict do nothing
                    """, clientId, delivery);
            if (inserted != 1) {
                throw new GitHubWebhookException(GitHubWebhookException.Code.REPLAYED);
            }
            revoke(event, payload);
        });
    }

    private void revoke(String event, GitHubWebhookPayload payload) {
        if (event.equals("github_app_authorization") && "revoked".equals(payload.action())) {
            revokeOwner(payload.sender().id());
        } else if (payload.installationRevoked(event)) {
            revokeInstallation(payload.installation().id());
        } else if (payload.repositoriesChanged(event)) {
            revokeSelection(payload);
        } else if (payload.repositoryRevoked(event)) {
            revokeRepository(payload.repository().id());
        }
    }

    private void revokeOwner(long owner) {
        jdbc.update("""
                insert into content_github_authorization_epochs(client_id,github_user_id,epoch) values (?,?,1)
                on conflict(client_id,github_user_id) do update set epoch=content_github_authorization_epochs.epoch+1
                """, clientId, owner);
        jdbc.update("""
                update content_github_grants set version=version+1,state='REVOKED',sealed_tokens=null,
                    access_expires_at=null,refresh_expires_at=null,refresh_lease=null,refresh_deadline=null,
                    updated_at=current_timestamp where client_id=? and github_user_id=?
                """, clientId, owner);
        jdbc.update("""
                update content_repository_bindings b set github_revoked=true,github_binding_version=github_binding_version+1,
                    updated_at=current_timestamp from content_github_grants g
                where b.credential_kind='GITHUB_APP' and b.github_account_id=g.account_id
                    and g.client_id=? and g.github_user_id=?
                """, clientId, owner);
    }

    private void revokeInstallation(long installation) {
        epochs.revoke(installation, 0);
        jdbc.update("""
                update content_repository_bindings b set github_revoked=true,github_binding_version=github_binding_version+1,
                    updated_at=current_timestamp from content_github_grants g
                where b.credential_kind='GITHUB_APP' and b.github_account_id=g.account_id
                    and g.client_id=? and b.github_installation_id=?
                """, clientId, installation);
    }

    private void revokeSelection(GitHubWebhookPayload payload) {
        if (payload.repositoriesRemoved().isEmpty()) {
            if ("removed".equals(payload.action())) {
                // GitHub omits individual removals when switching from all repositories to selected repositories.
                revokeInstallation(payload.installation().id());
            }
            return;
        }
        List<Long> repositories = payload.repositoriesRemoved().stream()
                .map(GitHubWebhookPayload.Identity::id)
                .distinct()
                .sorted()
                .toList();
        epochs.revokeRepositories(repositories);
        jdbc.update(
                """
                    update content_repository_bindings b set github_revoked=true,github_binding_version=github_binding_version+1,
                        updated_at=current_timestamp from content_github_grants g
                    where b.credential_kind='GITHUB_APP' and b.github_account_id=g.account_id and g.client_id=?
                        and b.github_installation_id=? and b.provider_identity=any(?::text[])
                    """,
                clientId,
                payload.installation().id(),
                new SqlArrayValue(
                        "text", repositories.stream().map(id -> "github:" + id).toArray()));
    }

    private void revokeRepository(long repository) {
        epochs.revoke(0, repository);
        jdbc.update("""
                update content_repository_bindings b set github_revoked=true,github_binding_version=github_binding_version+1,
                    updated_at=current_timestamp from content_github_grants g
                where b.credential_kind='GITHUB_APP' and b.github_account_id=g.account_id
                    and g.client_id=? and b.provider_identity=?
                """, clientId, "github:" + repository);
    }
}
