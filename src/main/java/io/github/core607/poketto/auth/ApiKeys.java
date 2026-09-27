package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.DENIED;

import io.github.core607.poketto.auth.AuthService.ApiKeyInfo;
import io.github.core607.poketto.auth.AuthService.MemberInfo;
import io.github.core607.poketto.auth.AuthService.Membership;
import io.github.core607.poketto.auth.AuthService.Page;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API keys: issued under the workspace lock to a member whose own capabilities cover the grant,
 * listed and revoked by owners who hold MANAGE_KEYS, delegated by OAuth without key
 * administration, and withdrawn with the membership authority that justified them.
 */
final class ApiKeys {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    ApiKeys(AuthService auth, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    void revokeMembershipKeys(
            WorkspaceId workspace,
            UUID account,
            MemberInfo before,
            MembershipRole role,
            boolean active,
            Set<Capability> permissions) {
        // Demotion also removes issued authority, including execution and key-management grants.
        if (!active || (before.role() == MembershipRole.OWNER && role != MembershipRole.OWNER)) {
            List<UUID> keys = jdbc.query(
                    """
                        update auth_api_keys set revoked_at = ? where workspace_id = ? and revoked_at is null
                        and (account_id = ? or created_by = ?) returning key_id
                        """,
                    (rs, row) -> rs.getObject(1, UUID.class),
                    auth.timestamp(),
                    workspace.value(),
                    account,
                    account);
            auth.publishRevocation(new AuthRevocation(workspace, Set.of(account), Set.copyOf(keys)));
        } else {
            Set<Capability> removed =
                    new HashSet<>(AuthService.memberCapabilities(before.role(), before.permissions()));
            removed.removeAll(AuthService.memberCapabilities(role, permissions));
            if (!removed.isEmpty()) {
                List<UUID> keys = jdbc.query(
                        connection -> {
                            var statement = connection.prepareStatement(
                                    "update auth_api_keys set revoked_at=? where workspace_id=? and account_id=? and revoked_at is null and capabilities && ? returning key_id");
                            statement.setTimestamp(1, auth.timestamp());
                            statement.setObject(2, workspace.value());
                            statement.setObject(3, account);
                            statement.setArray(
                                    4,
                                    connection.createArrayOf(
                                            "text",
                                            removed.stream().map(Enum::name).toArray(String[]::new)));
                            return statement;
                        },
                        (rs, row) -> rs.getObject(1, UUID.class));
                if (!keys.isEmpty()) {
                    auth.publishRevocation(new AuthRevocation(workspace, Set.of(), Set.copyOf(keys)));
                }
            }
        }
    }

    /** Owners may issue keys; machine owners additionally need MANAGE_KEYS and cannot grant beyond their own capabilities. */
    IssuedToken create(AuthPrincipal actor, WorkspaceId workspace, UUID holder, Set<Capability> requested) {
        return issueApiKey(actor, workspace, holder, requested, false);
    }

    /** OAuth delegates a human member's own permissions without granting key administration. */
    IssuedToken createForOAuth(AuthPrincipal actor, WorkspaceId workspace, Set<Capability> requested) {
        if (actor == null
                || actor.kind() != AuthPrincipal.Kind.ACCOUNT
                || requested == null
                || requested.contains(Capability.MANAGE_KEYS)) {
            throw AuthService.failure(DENIED);
        }
        return issueApiKey(actor, workspace, actor.accountId(), requested, true);
    }

    private IssuedToken issueApiKey(
            AuthPrincipal actor, WorkspaceId workspace, UUID holder, Set<Capability> requested, boolean oauth) {
        Set<Capability> capabilities = requested == null ? AuthService.DEFAULT_AI_CAPABILITIES : Set.copyOf(requested);
        IssuedToken issued = transactions.execute(status -> {
            auth.lockWorkspace(workspace);
            WorkspaceAccess access = oauth ? auth.authorize(actor, workspace) : requireKeyManager(actor, workspace);
            if (actor.kind() == AuthPrincipal.Kind.API_KEY
                    && !AuthService.withImplied(access.capabilities()).containsAll(capabilities)) {
                throw AuthService.failure(DENIED);
            }
            List<Membership> holders = jdbc.query(
                    "select role,permissions from auth_memberships where workspace_id = ? and account_id = ? and suspended_at is null",
                    (rs, row) -> new Membership(
                            MembershipRole.valueOf(rs.getString(1)), AuthService.readCapabilities(rs, 2)),
                    workspace.value(),
                    holder);
            if (holders.isEmpty()
                    || !AuthService.withImplied(AuthService.memberCapabilities(
                                    holders.getFirst().role(),
                                    holders.getFirst().permissions()))
                            .containsAll(capabilities)) {
                throw AuthService.failure(DENIED);
            }
            String token = CredentialTokens.random("pk_");
            UUID id = UUID.randomUUID();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "insert into auth_api_keys (key_id, workspace_id, account_id, created_by, token_digest, capabilities) values (?, ?, ?, ?, ?, ?)");
                statement.setObject(1, id);
                statement.setObject(2, workspace.value());
                statement.setObject(3, holder);
                statement.setObject(4, actor.accountId());
                statement.setString(5, CredentialTokens.digest(token));
                statement.setArray(
                        6,
                        connection.createArrayOf(
                                "text", capabilities.stream().map(Enum::name).toArray(String[]::new)));
                return statement;
            });
            return new IssuedToken(id, token);
        });
        AuditRecords.granted("key.issued", actor, workspace, issued.id(), capabilities);
        return issued;
    }

    Page<ApiKeyInfo> list(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        requireKeyManager(actor, workspace);
        AuthService.validatePage(offset, limit);
        long total = jdbc.queryForObject(
                "select count(*) from auth_api_keys k where workspace_id = ? and not exists (select 1 from oauth_connections c where c.key_id=k.key_id)",
                Long.class,
                workspace.value());
        List<ApiKeyInfo> items = jdbc.query(
                "select key_id, account_id, capabilities, revoked_at from auth_api_keys k where workspace_id = ? and not exists (select 1 from oauth_connections c where c.key_id=k.key_id) order by created_at desc, key_id limit ? offset ?",
                (rs, row) -> new ApiKeyInfo(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        Arrays.stream((String[]) rs.getArray(3).getArray())
                                .map(Capability::valueOf)
                                .collect(Collectors.toSet()),
                        rs.getTimestamp(4) != null),
                workspace.value(),
                limit,
                offset);
        return new Page<>(items, total, offset, limit);
    }

    void revoke(AuthPrincipal actor, WorkspaceId workspace, UUID keyId) {
        var revoked = new AtomicBoolean();
        transactions.executeWithoutResult(status -> {
            auth.lockWorkspace(workspace);
            requireKeyManager(actor, workspace);
            List<UUID> keys = jdbc.query(
                    "update auth_api_keys set revoked_at = ? where workspace_id = ? and key_id = ? and revoked_at is null returning key_id",
                    (rs, row) -> rs.getObject(1, UUID.class),
                    auth.timestamp(),
                    workspace.value(),
                    keyId);
            if (!keys.isEmpty()) {
                revoked.set(true);
                auth.publishRevocation(new AuthRevocation(workspace, Set.of(), Set.copyOf(keys)));
            }
        });
        if (revoked.get()) {
            AuditRecords.changed("key.revoked", actor, workspace, keyId);
        }
    }

    /** Caller holds the workspace row lock after validating an OAuth grant or its replay proof. */
    void revokeOAuth(WorkspaceId workspace, UUID keyId) {
        jdbc.update(
                "update auth_api_keys set revoked_at=? where workspace_id=? and key_id=? and revoked_at is null",
                auth.timestamp(),
                workspace.value(),
                keyId);
        auth.publishRevocation(new AuthRevocation(workspace, Set.of(), Set.of(keyId)));
    }

    private WorkspaceAccess requireKeyManager(AuthPrincipal actor, WorkspaceId workspace) {
        WorkspaceAccess access = auth.authorize(actor, workspace, Capability.MANAGE_KEYS);
        if (access.role() != MembershipRole.OWNER) {
            throw AuthService.failure(DENIED);
        }
        return access;
    }
}
