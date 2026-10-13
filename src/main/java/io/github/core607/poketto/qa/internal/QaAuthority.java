package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.auth.AccountIdentity;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Policy/account guards precede the shared QA ledger; no upstream or content I/O holds these locks. */
final class QaAuthority {
    private final CommunityAccounts people;
    private final MachineAccounts machines;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    QaAuthority(
            CommunityAccounts people, MachineAccounts machines, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.people = people;
        this.machines = machines;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    <T> T with(AuthPrincipal actor, WorkspaceId connection, Function<UUID, T> operation) {
        return transactions.execute(status -> {
            UUID account;
            if (connection == null) {
                AccountIdentity identity = people.lock(actor);
                if (!identity.group().mayCreateSpace()) {
                    throw new AuthException(AuthException.Code.DENIED);
                }
                account = identity.accountId();
            } else {
                MachineAccounts.Identity identity = machines.lock(actor, connection);
                if (!identity.permissions().contains(MachinePermission.WISH)) {
                    throw new QaException("OWNER_CONSENT_REQUIRED", "The account holder must authorize wishes");
                }
                account = identity.accountId();
            }
            lockLedger();
            return operation.apply(account);
        });
    }

    /** Billing and refunds finish even after the caller loses eligibility. Only internal run identities reach this path. */
    <T> T record(UUID account, Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.queryForObject(
                    "select singleton from auth_initialization where singleton=true for share", Boolean.class);
            jdbc.query(
                    "select account_id from auth_accounts where account_id=? for update",
                    (row, index) -> row.getObject(1),
                    account);
            lockLedger();
            return operation.get();
        });
    }

    private void lockLedger() {
        jdbc.queryForObject("select singleton from qa_control where singleton=true for update", Boolean.class);
    }
}
