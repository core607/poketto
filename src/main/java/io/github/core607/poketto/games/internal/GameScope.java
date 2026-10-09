package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.games.GameLibrary;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Account guard precedes publication and snapshot guards; no game execution occurs in a transaction. */
final class GameScope {
    private final CommunityAccounts people;
    private final MachineAccounts machines;
    private final PublicationGuard publications;
    private final GameLibrary library;
    private final PlatformTransactionManager transactions;

    GameScope(
            CommunityAccounts people,
            MachineAccounts machines,
            PublicationGuard publications,
            GameLibrary library,
            PlatformTransactionManager transactions) {
        this.people = people;
        this.machines = machines;
        this.publications = publications;
        this.library = library;
        this.transactions = transactions;
    }

    <T> T account(AuthPrincipal actor, WorkspaceId connection, Function<UUID, T> operation) {
        TransactionStatus status = begin();
        try {
            UUID account = lock(actor, connection);
            T result = operation.apply(account);
            transactions.commit(status);
            return result;
        } finally {
            if (!status.isCompleted()) {
                transactions.rollback(status);
            }
        }
    }

    <T> T published(
            AuthPrincipal actor,
            WorkspaceId connection,
            Function<UUID, Target> target,
            BiFunction<UUID, GameLibrary.Published, T> operation) {
        TransactionStatus status = begin();
        try {
            UUID account = lock(actor, connection);
            Target selected = target.apply(account);
            publications.lock(selected.workspace());
            return library.withCurrent(selected.workspace(), selected.articleId(), game -> {
                T result = operation.apply(account, game);
                transactions.commit(status);
                return result;
            });
        } finally {
            if (!status.isCompleted()) {
                transactions.rollback(status);
            }
        }
    }

    private UUID lock(AuthPrincipal actor, WorkspaceId connection) {
        if (connection == null) {
            return people.lock(actor).accountId();
        }
        MachineAccounts.Identity identity = machines.lock(actor, connection);
        if (!identity.permissions().contains(MachinePermission.GAME_SAVE)) {
            throw new GameException("OWNER_CONSENT_REQUIRED", "The account holder must authorize access to game saves");
        }
        return identity.accountId();
    }

    private TransactionStatus begin() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Game operations own their account/publication transaction");
        }
        return transactions.getTransaction(new DefaultTransactionDefinition());
    }

    record Target(WorkspaceId workspace, UUID articleId) {}
}
