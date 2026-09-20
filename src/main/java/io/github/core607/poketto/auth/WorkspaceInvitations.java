package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.DENIED;
import static io.github.core607.poketto.auth.AuthException.Code.INVALID_INVITATION;

import io.github.core607.poketto.auth.AuthService.Page;
import io.github.core607.poketto.auth.AuthService.WorkspaceInvitationInfo;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Workspace invitations: issued by a human owner under the workspace lock, stored only as a
 * digest, single-use with an idempotent repeat by the same account, and listed or revoked only by
 * a human owner. Registration invitations are a separate credential owned by RegistrationService.
 */
final class WorkspaceInvitations {
    private final AuthService auth;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    WorkspaceInvitations(AuthService auth, JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    IssuedToken create(AuthPrincipal actor, WorkspaceId workspace, Set<Capability> requested) {
        Set<Capability> permissions = AuthService.contentPermissions(requested);
        IssuedToken issued = transactions.execute(status -> {
            auth.lockWorkspace(workspace);
            auth.requireHumanOwner(actor, workspace);
            UUID id = UUID.randomUUID();
            String token = auth.randomToken("invite_");
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "insert into auth_invitations (invitation_id,workspace_id,token_digest,created_by,expires_at,permissions) values (?,?,?,?,?,?)");
                statement.setObject(1, id);
                statement.setObject(2, workspace.value());
                statement.setString(3, AuthService.digest(token));
                statement.setObject(4, actor.accountId());
                statement.setTimestamp(5, Timestamp.from(clock.instant().plus(Duration.ofDays(7))));
                statement.setArray(
                        6,
                        connection.createArrayOf(
                                "text", permissions.stream().map(Enum::name).toArray(String[]::new)));
                return statement;
            });
            return new IssuedToken(id, token);
        });
        AuditRecords.granted("invitation.issued", actor, workspace, issued.id(), permissions);
        return issued;
    }

    void revoke(AuthPrincipal actor, WorkspaceId workspace, UUID invitationId) {
        var withdrawn = new AtomicBoolean();
        transactions.executeWithoutResult(status -> {
            auth.lockWorkspace(workspace);
            auth.requireHumanOwner(actor, workspace);
            // Only a row whose revocation time this call set is a withdrawal. A repeat, a
            // mistyped identifier and another workspace's invitation all leave the link live,
            // and a record claiming otherwise would retire a link that still works.
            List<UUID> withdrawnIds = jdbc.query(
                    "update auth_invitations set revoked_at = ? where workspace_id = ? and invitation_id = ? and revoked_at is null returning invitation_id",
                    (rs, row) -> rs.getObject(1, UUID.class),
                    auth.timestamp(),
                    workspace.value(),
                    invitationId);
            withdrawn.set(!withdrawnIds.isEmpty());
        });
        if (withdrawn.get()) {
            AuditRecords.changed("invitation.revoked", actor, workspace, invitationId);
        }
    }

    Page<WorkspaceInvitationInfo> list(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        auth.requireHumanOwner(actor, workspace);
        AuthService.validatePage(offset, limit);
        long total = jdbc.queryForObject(
                "select count(*) from auth_invitations where workspace_id = ?", Long.class, workspace.value());
        List<WorkspaceInvitationInfo> items = jdbc.query(
                """
                select invitation_id, expires_at, revoked_at, used_at, permissions from auth_invitations
                where workspace_id = ? order by created_at desc, invitation_id limit ? offset ?
                """,
                (rs, row) -> new WorkspaceInvitationInfo(
                        rs.getObject(1, UUID.class),
                        rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) != null,
                        rs.getTimestamp(4) != null,
                        AuthService.readCapabilities(rs, 5)),
                workspace.value(),
                limit,
                offset);
        return new Page<>(items, total, offset, limit);
    }

    /** Repeating a consumed token succeeds only for its original account with an active membership. */
    WorkspaceId accept(AuthPrincipal account, String token) {
        if (account == null || account.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw AuthService.failure(DENIED);
        }
        var admitted = new AtomicBoolean();
        WorkspaceId joined = transactions.execute(status -> {
            Invitation invitation = lockInvitation(token);
            auth.validateAccount(account);
            requireUsableInvitation(invitation, account.accountId());
            admitted.set(join(invitation, account.accountId()));
            return invitation.workspace();
        });
        // Replaying a consumed token succeeds and changes nothing, so recording it again would
        // overstate how many accounts joined and when.
        if (admitted.get()) {
            AuditRecords.changed("invitation.redeemed", account, joined, account.accountId());
        }
        return joined;
    }

    private Invitation lockInvitation(String token) {
        String hash = AuthService.digestCredential(token);
        List<WorkspaceId> workspaces = jdbc.query(
                "select workspace_id from auth_invitations where token_digest = ?",
                (rs, row) -> new WorkspaceId(rs.getObject(1, UUID.class)),
                hash);
        if (workspaces.isEmpty()) {
            throw AuthService.failure(INVALID_INVITATION);
        }
        WorkspaceId workspace = workspaces.getFirst();
        auth.lockWorkspace(workspace);
        List<Invitation> found = jdbc.query(
                "select invitation_id, expires_at, revoked_at, used_by, permissions from auth_invitations where token_digest = ? for update",
                (rs, row) -> new Invitation(
                        rs.getObject(1, UUID.class),
                        workspace,
                        rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) != null,
                        rs.getObject(4, UUID.class),
                        AuthService.readCapabilities(rs, 5)),
                hash);
        if (found.isEmpty()) {
            throw AuthService.failure(INVALID_INVITATION);
        }
        return found.getFirst();
    }

    private void requireUsableInvitation(Invitation invitation, UUID account) {
        if (invitation.revoked()
                || !clock.instant().isBefore(invitation.expires())
                || (invitation.usedBy() != null && !invitation.usedBy().equals(account))) {
            throw AuthService.failure(INVALID_INVITATION);
        }
    }

    /** Reports whether this call admitted the account, as opposed to replaying a consumed token. */
    private boolean join(Invitation invitation, UUID account) {
        List<Boolean> membership = jdbc.query(
                "select suspended_at is null from auth_memberships where workspace_id = ? and account_id = ?",
                (rs, row) -> rs.getBoolean(1),
                invitation.workspace().value(),
                account);
        if (!membership.isEmpty() && !membership.getFirst()) {
            throw AuthService.failure(INVALID_INVITATION);
        }
        int admitted = jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into auth_memberships (workspace_id,account_id,role,permissions) values (?,?,'MEMBER',?) on conflict(workspace_id,account_id) do nothing");
            statement.setObject(1, invitation.workspace().value());
            statement.setObject(2, account);
            statement.setArray(
                    3,
                    connection.createArrayOf(
                            "text",
                            invitation.permissions().stream().map(Enum::name).toArray(String[]::new)));
            return statement;
        });
        jdbc.update(
                "update auth_invitations set used_at = coalesce(used_at, ?), used_by = ? where invitation_id = ?",
                auth.timestamp(),
                account,
                invitation.id());
        return admitted > 0;
    }

    private record Invitation(
            UUID id,
            WorkspaceId workspace,
            Instant expires,
            boolean revoked,
            UUID usedBy,
            Set<Capability> permissions) {}
}
