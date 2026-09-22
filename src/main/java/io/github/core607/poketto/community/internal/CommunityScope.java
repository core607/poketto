package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Commits while the current snapshot is held, after taking all cross-module guards. */
final class CommunityScope {
    private final PlatformTransactionManager transactions;
    private final CommunityAccounts accounts;
    private final PublicationGuard publications;
    private final PublicContentSnapshots snapshots;

    CommunityScope(
            PlatformTransactionManager transactions,
            CommunityAccounts accounts,
            PublicationGuard publications,
            PublicContentSnapshots snapshots) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.publications = publications;
        this.snapshots = snapshots;
    }

    <T> T published(
            AuthPrincipal actor,
            WorkspaceId workspace,
            boolean participating,
            BiFunction<AccountIdentity, PublicContentSnapshot, T> operation) {
        TransactionStatus status = begin();
        try {
            AccountIdentity identity = accounts.lock(actor);
            if (participating && identity.group() == SiteGroup.VIEWER) {
                throw new CommunityException(CommunityException.Code.PARTICIPATION_REQUIRED);
            }
            publications.lock(workspace);
            return snapshots.withCurrent(workspace, snapshot -> {
                T result = operation.apply(identity, snapshot);
                transactions.commit(status);
                return result;
            });
        } finally {
            if (!status.isCompleted()) {
                transactions.rollback(status);
            }
        }
    }

    <T> T personal(AuthPrincipal actor, Function<AccountIdentity, T> operation) {
        TransactionStatus status = begin();
        try {
            T result = operation.apply(accounts.lock(actor));
            transactions.commit(status);
            return result;
        } finally {
            if (!status.isCompleted()) {
                transactions.rollback(status);
            }
        }
    }

    private TransactionStatus begin() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("community operations own their transaction boundary");
        }
        return transactions.getTransaction(new DefaultTransactionDefinition());
    }
}
