package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.*;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared identity and workspace authorization boundary. Browser session handling, login throttling,
 * CSRF, and HTTP error mapping belong to the security entrance. Tokens and passwords must never be logged.
 * Membership and key mutations serialize on the workspace row; no caller-supplied capability set is trusted.
 */
public final class AuthService {
    private volatile String oauthResource = "";

    void oauthResource(String resource) {
        this.oauthResource = resource;
    }

    public static final Set<Capability> DEFAULT_AI_CAPABILITIES =
            Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE);
    public static final Set<Capability> CONTENT_PERMISSIONS =
            Set.of(Capability.READ_PRIVATE, Capability.WRITE_PRIVATE, Capability.PUBLISH);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            PasswordEncoder passwords,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.passwords = passwords;
        this.events = events;
        this.clock = clock;
        this.dummyPasswordHash = passwords.encode(randomToken("dummy_"));
    }

    /** Operator-only process entrance; no HTTP controller may expose this operation. */
    public AuthPrincipal initializeOwner(String login, String password) {
        String normalized = loginName(login);
        String encoded = encodePassword(password);
        return transactions.execute(status -> {
            Boolean initialized = jdbc.queryForObject(
                    "select initialized_at is not null from auth_initialization where singleton = true for update",
                    Boolean.class);
            if (Boolean.TRUE.equals(initialized)) throw failure(ALREADY_INITIALIZED);
            UUID workspace = jdbc.queryForObject(
                    "select workspace_id from workspaces where is_default = true for update", UUID.class);
            UUID account = createAccount(normalized, encoded, true);
            jdbc.update(
                    "insert into auth_memberships (workspace_id, account_id, role) values (?, ?, 'OWNER')",
                    workspace,
                    account);
            jdbc.update("update auth_initialization set initialized_at = ? where singleton = true", timestamp());
            return accountPrincipal(account);
        });
    }

    /** Uniform credential rejection includes missing accounts; the HTTP caller must also throttle attempts. */
    public AuthPrincipal authenticatePassword(String login, String password) {
        if (password == null || password.length() > 256) throw failure(INVALID_CREDENTIALS);
        String normalized;
        try {
            normalized = loginName(login);
        } catch (AuthException exception) {
            normalized = "";
        }
        List<AccountCredential> accounts = jdbc.query(
                "select account_id, password_hash from auth_accounts where login_name = ?",
                (rs, row) -> new AccountCredential(rs.getObject(1, UUID.class), rs.getString(2)),
                normalized);
        String encoded =
                accounts.isEmpty() ? dummyPasswordHash : accounts.getFirst().hash();
        if (!passwords.matches(password, encoded) || accounts.isEmpty()) throw failure(INVALID_CREDENTIALS);
        AccountCredential account = accounts.getFirst();
        if (passwords.upgradeEncoding(encoded)) {
            jdbc.update(
                    "update auth_accounts set password_hash = ? where account_id = ? and password_hash = ?",
                    passwords.encode(password),
                    account.id(),
                    encoded);
        }
        return accountPrincipal(account.id());
    }

    public AuthPrincipal authenticateApiKey(String token) {
        List<AuthPrincipal> matches = jdbc.query(
                """
                select k.key_id, k.account_id from auth_api_keys k
                join auth_memberships m on m.workspace_id = k.workspace_id and m.account_id = k.account_id
                where (k.token_digest = ? or exists (
                    select 1 from oauth_access_tokens t join oauth_connections c using(key_id)
                    where t.key_id=k.key_id and t.digest=? and t.expires_at>? and c.resource=?
                )) and k.revoked_at is null and m.suspended_at is null
                and not exists (select 1 from oauth_connections c where c.key_id=k.key_id and (c.expires_at<=? or c.resource<>?))
                """,
                (rs, row) -> new AuthPrincipal(
                        AuthPrincipal.Kind.API_KEY, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                digestCredential(token),
                digestCredential(token),
                timestamp(),
                oauthResource,
                timestamp(),
                oauthResource);
        if (matches.isEmpty()) throw failure(INVALID_CREDENTIALS);
        return matches.getFirst();
    }

    public WorkspaceAccess authorize(AuthPrincipal principal, WorkspaceId workspace, Capability... required) {
        if (principal == null || workspace == null) throw failure(DENIED);
        List<Membership> memberships = jdbc.query(
                "select role, permissions from auth_memberships where workspace_id = ? and account_id = ? and suspended_at is null",
                (rs, row) -> new Membership(MembershipRole.valueOf(rs.getString(1)), readCapabilities(rs, 2)),
                workspace.value(),
                principal.accountId());
        if (memberships.isEmpty()) throw failure(DENIED);
        Membership membership = memberships.getFirst();
        MembershipRole role = membership.role();
        Set<Capability> holderCapabilities = memberCapabilities(role, membership.permissions());
        Set<Capability> capabilities;
        if (principal.kind() == AuthPrincipal.Kind.ACCOUNT) {
            capabilities = holderCapabilities;
        } else {
            List<Set<Capability>> stored = jdbc.query(
                    """
                    select capabilities from auth_api_keys where key_id = ? and account_id = ?
                    and workspace_id = ? and revoked_at is null
                    and not exists (select 1 from oauth_connections c where c.key_id=auth_api_keys.key_id and (c.expires_at<=? or c.resource<>?))
                    """,
                    (rs, row) -> Arrays.stream((String[]) rs.getArray(1).getArray())
                            .map(Capability::valueOf)
                            .collect(Collectors.toSet()),
                    principal.subjectId(),
                    principal.accountId(),
                    workspace.value(),
                    timestamp(),
                    oauthResource);
            if (stored.isEmpty()) throw failure(DENIED);
            capabilities = new java.util.HashSet<>(stored.getFirst());
            capabilities.retainAll(holderCapabilities);
        }
        if (!capabilities.containsAll(Arrays.asList(required))) throw failure(DENIED);
        return new WorkspaceAccess(workspace, principal, role, capabilities);
    }

    private static Set<Capability> memberCapabilities(MembershipRole role, Set<Capability> permissions) {
        if (role == MembershipRole.OWNER) return EnumSet.allOf(Capability.class);
        // Execution is an independent machine grant. Membership permits public-scope use;
        // private reads, writes and publication still require their explicit content permissions.
        Set<Capability> result = EnumSet.of(Capability.EXECUTE_REPOSITORY);
        result.addAll(permissions);
        return result;
    }

    private static Set<Capability> contentPermissions(Set<Capability> requested) {
        if (requested == null
                || requested.stream().anyMatch(java.util.Objects::isNull)
                || !CONTENT_PERMISSIONS.containsAll(requested)
                || (requested.contains(Capability.WRITE_PRIVATE) && !requested.contains(Capability.READ_PRIVATE)))
            throw failure(INVALID_INPUT);
        return Set.copyOf(requested);
    }

    private static Set<Capability> readCapabilities(java.sql.ResultSet row, int column) throws java.sql.SQLException {
        return Arrays.stream((String[]) row.getArray(column).getArray())
                .map(Capability::valueOf)
                .collect(Collectors.toSet());
    }

    private record Membership(MembershipRole role, Set<Capability> permissions) {}
    /** Machine workspace selection comes exclusively from the durable credential binding. */
    public WorkspaceId workspaceForKey(AuthPrincipal principal) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY) throw failure(DENIED);
        var rows = jdbc.query(
                "select workspace_id from auth_api_keys where key_id=? and account_id=? and revoked_at is null",
                (rs, row) -> new WorkspaceId(rs.getObject(1, UUID.class)),
                principal.subjectId(),
                principal.accountId());
        if (rows.size() != 1) throw failure(DENIED);
        WorkspaceId workspace = rows.getFirst();
        authorize(principal, workspace);
        return workspace;
    }

    public Page<WorkspaceMembership> workspaces(AuthPrincipal principal, int offset, int limit) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.ACCOUNT) throw failure(DENIED);
        validatePage(offset, limit);
        long total = jdbc.queryForObject(
                "select count(*) from auth_memberships where account_id=? and suspended_at is null",
                Long.class,
                principal.accountId());
        var rows = jdbc.query(
                "select w.workspace_id,w.display_name from workspaces w join auth_memberships m using(workspace_id) where m.account_id=? and m.suspended_at is null order by w.display_name,w.workspace_id limit ? offset ?",
                (rs, row) ->
                        new WorkspaceMembership(new WorkspaceId(rs.getObject(1, UUID.class)), rs.getString(2), null),
                principal.accountId(),
                limit,
                offset);
        return new Page<>(
                rows.stream()
                        .map(row -> new WorkspaceMembership(
                                row.workspaceId(), row.displayName(), authorize(principal, row.workspaceId())))
                        .toList(),
                total,
                offset,
                limit);
    }

    public record WorkspaceMembership(WorkspaceId workspaceId, String displayName, WorkspaceAccess access) {}

    /** The caller creates the empty catalog row in this same transaction; existing spaces cannot be claimed. */
    public void establishWorkspaceOwner(AuthPrincipal actor, WorkspaceId workspace) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Workspace ownership requires a creation transaction");
        if (actor == null || actor.kind() != AuthPrincipal.Kind.ACCOUNT) throw failure(DENIED);
        lockWorkspace(workspace);
        if (!jdbc.queryForObject(
                        "select exists(select 1 from auth_accounts where account_id=?)",
                        Boolean.class,
                        actor.accountId())
                || jdbc.queryForObject(
                        "select exists(select 1 from auth_memberships where workspace_id=?)",
                        Boolean.class,
                        workspace.value())) throw failure(DENIED);
        jdbc.update(
                "insert into auth_memberships(workspace_id,account_id,role) values (?,?,'OWNER')",
                workspace.value(),
                actor.accountId());
    }

    /**
     * Holds the workspace lock through a bounded operation, serializing authority checks with key
     * revocation and membership changes. External effects are not rolled back with this transaction;
     * callers must reconcile their own acknowledgement if transaction completion subsequently fails.
     */
    public <T> T withAuthorization(
            AuthPrincipal principal, WorkspaceId workspace, Set<Capability> required, Supplier<T> action) {
        return transactions.execute(status -> {
            lockWorkspace(workspace);
            authorize(principal, workspace, required.toArray(Capability[]::new));
            return action.get();
        });
    }

    public IssuedToken createInvitation(AuthPrincipal actor, WorkspaceId workspace, Set<Capability> requested) {
        Set<Capability> permissions = contentPermissions(requested);
        return transactions.execute(status -> {
            lockWorkspace(workspace);
            requireHumanOwner(actor, workspace);
            UUID id = UUID.randomUUID();
            String token = randomToken("invite_");
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "insert into auth_invitations (invitation_id,workspace_id,token_digest,created_by,expires_at,permissions) values (?,?,?,?,?,?)");
                statement.setObject(1, id);
                statement.setObject(2, workspace.value());
                statement.setString(3, digest(token));
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
    }

    public void revokeInvitation(AuthPrincipal actor, WorkspaceId workspace, UUID invitationId) {
        transactions.executeWithoutResult(status -> {
            lockWorkspace(workspace);
            requireHumanOwner(actor, workspace);
            jdbc.update(
                    "update auth_invitations set revoked_at = coalesce(revoked_at, ?) where workspace_id = ? and invitation_id = ?",
                    timestamp(),
                    workspace.value(),
                    invitationId);
        });
    }

    public Page<WorkspaceInvitationInfo> listInvitations(
            AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        requireHumanOwner(actor, workspace);
        validatePage(offset, limit);
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
                        readCapabilities(rs, 5)),
                workspace.value(),
                limit,
                offset);
        return new Page<>(items, total, offset, limit);
    }

    /** Repeating a consumed token succeeds only for its original account with an active membership. */
    public WorkspaceId acceptInvitation(AuthPrincipal account, String token) {
        if (account == null || account.kind() != AuthPrincipal.Kind.ACCOUNT) throw failure(DENIED);
        return transactions.execute(status -> {
            Invitation invitation = lockInvitation(token);
            requireUsableInvitation(invitation, account.accountId());
            join(invitation, account.accountId());
            return invitation.workspace();
        });
    }

    public Page<MemberInfo> listMembers(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        requireHumanOwner(actor, workspace);
        validatePage(offset, limit);
        long total = jdbc.queryForObject(
                "select count(*) from auth_memberships where workspace_id = ?", Long.class, workspace.value());
        List<MemberInfo> items = jdbc.query(
                """
                select m.account_id, a.login_name, m.role, m.suspended_at, m.permissions from auth_memberships m
                join auth_accounts a on a.account_id = m.account_id where workspace_id = ?
                order by a.login_name limit ? offset ?
                """,
                (rs, row) -> new MemberInfo(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        MembershipRole.valueOf(rs.getString(3)),
                        rs.getTimestamp(4) == null,
                        rs.getString(3).equals("OWNER") ? CONTENT_PERMISSIONS : readCapabilities(rs, 5)),
                workspace.value(),
                limit,
                offset);
        return new Page<>(items, total, offset, limit);
    }

    public void changeMembership(
            AuthPrincipal actor,
            WorkspaceId workspace,
            UUID account,
            MembershipRole role,
            boolean active,
            Set<Capability> requested) {
        Set<Capability> permissions = contentPermissions(requested);
        if (role == null || account == null) throw failure(INVALID_INPUT);
        transactions.executeWithoutResult(status -> {
            lockWorkspace(workspace);
            requireHumanOwner(actor, workspace);
            List<MemberInfo> members = jdbc.query(
                    "select account_id, role, suspended_at, permissions from auth_memberships where workspace_id = ? and account_id = ?",
                    (rs, row) -> new MemberInfo(
                            rs.getObject(1, UUID.class),
                            "",
                            MembershipRole.valueOf(rs.getString(2)),
                            rs.getTimestamp(3) == null,
                            readCapabilities(rs, 4)),
                    workspace.value(),
                    account);
            if (members.isEmpty()) throw failure(DENIED);
            MemberInfo before = members.getFirst();
            if (before.active() && before.role() == MembershipRole.OWNER && (!active || role != MembershipRole.OWNER)) {
                Integer owners = jdbc.queryForObject(
                        "select count(*) from auth_memberships where workspace_id = ? and role = 'OWNER' and suspended_at is null",
                        Integer.class,
                        workspace.value());
                if (owners == null || owners <= 1) throw failure(LAST_OWNER);
            }
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "update auth_memberships set role=?,suspended_at=?,permissions=? where workspace_id=? and account_id=?");
                statement.setString(1, role.name());
                statement.setTimestamp(2, active ? null : timestamp());
                statement.setArray(
                        3,
                        connection.createArrayOf(
                                "text", permissions.stream().map(Enum::name).toArray(String[]::new)));
                statement.setObject(4, workspace.value());
                statement.setObject(5, account);
                return statement;
            });
            // Demotion also removes issued authority, including execution and key-management grants.
            if (!active || (before.role() == MembershipRole.OWNER && role != MembershipRole.OWNER)) {
                List<UUID> keys = jdbc.query(
                        """
                        update auth_api_keys set revoked_at = ? where workspace_id = ? and revoked_at is null
                        and (account_id = ? or created_by = ?) returning key_id
                        """,
                        (rs, row) -> rs.getObject(1, UUID.class),
                        timestamp(),
                        workspace.value(),
                        account,
                        account);
                publishRevocation(new AuthRevocation(workspace, Set.of(account), Set.copyOf(keys)));
            } else {
                Set<Capability> removed =
                        new java.util.HashSet<>(memberCapabilities(before.role(), before.permissions()));
                removed.removeAll(memberCapabilities(role, permissions));
                if (!removed.isEmpty()) {
                    List<UUID> keys = jdbc.query(
                            connection -> {
                                var statement = connection.prepareStatement(
                                        "update auth_api_keys set revoked_at=? where workspace_id=? and account_id=? and revoked_at is null and capabilities && ? returning key_id");
                                statement.setTimestamp(1, timestamp());
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
                    if (!keys.isEmpty()) publishRevocation(new AuthRevocation(workspace, Set.of(), Set.copyOf(keys)));
                }
            }
        });
    }

    /** Owners may issue keys; machine owners additionally need MANAGE_KEYS and cannot grant beyond their own capabilities. */
    public IssuedToken createApiKey(
            AuthPrincipal actor, WorkspaceId workspace, UUID holder, Set<Capability> requested) {
        Set<Capability> capabilities = requested == null ? DEFAULT_AI_CAPABILITIES : Set.copyOf(requested);
        return transactions.execute(status -> {
            lockWorkspace(workspace);
            WorkspaceAccess access = requireKeyManager(actor, workspace);
            if (actor.kind() == AuthPrincipal.Kind.API_KEY
                    && !access.capabilities().containsAll(capabilities)) throw failure(DENIED);
            List<Membership> holders = jdbc.query(
                    "select role,permissions from auth_memberships where workspace_id = ? and account_id = ? and suspended_at is null",
                    (rs, row) -> new Membership(MembershipRole.valueOf(rs.getString(1)), readCapabilities(rs, 2)),
                    workspace.value(),
                    holder);
            if (holders.isEmpty()
                    || !memberCapabilities(
                                    holders.getFirst().role(),
                                    holders.getFirst().permissions())
                            .containsAll(capabilities)) throw failure(DENIED);
            String token = randomToken("pk_");
            UUID id = UUID.randomUUID();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "insert into auth_api_keys (key_id, workspace_id, account_id, created_by, token_digest, capabilities) values (?, ?, ?, ?, ?, ?)");
                statement.setObject(1, id);
                statement.setObject(2, workspace.value());
                statement.setObject(3, holder);
                statement.setObject(4, actor.accountId());
                statement.setString(5, digest(token));
                statement.setArray(
                        6,
                        connection.createArrayOf(
                                "text", capabilities.stream().map(Enum::name).toArray(String[]::new)));
                return statement;
            });
            return new IssuedToken(id, token);
        });
    }

    public Page<ApiKeyInfo> listApiKeys(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        requireKeyManager(actor, workspace);
        validatePage(offset, limit);
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

    public void revokeApiKey(AuthPrincipal actor, WorkspaceId workspace, UUID keyId) {
        transactions.executeWithoutResult(status -> {
            lockWorkspace(workspace);
            requireKeyManager(actor, workspace);
            List<UUID> keys = jdbc.query(
                    "update auth_api_keys set revoked_at = ? where workspace_id = ? and key_id = ? and revoked_at is null returning key_id",
                    (rs, row) -> rs.getObject(1, UUID.class),
                    timestamp(),
                    workspace.value(),
                    keyId);
            if (!keys.isEmpty()) publishRevocation(new AuthRevocation(workspace, Set.of(), Set.copyOf(keys)));
        });
    }

    /** Caller holds the workspace row lock after validating an OAuth grant or its replay proof. */
    void revokeOAuthKey(WorkspaceId workspace, UUID keyId) {
        jdbc.update(
                "update auth_api_keys set revoked_at=? where workspace_id=? and key_id=? and revoked_at is null",
                timestamp(),
                workspace.value(),
                keyId);
        publishRevocation(new AuthRevocation(workspace, Set.of(), Set.of(keyId)));
    }

    private Invitation lockInvitation(String token) {
        String hash = digestCredential(token);
        List<WorkspaceId> workspaces = jdbc.query(
                "select workspace_id from auth_invitations where token_digest = ?",
                (rs, row) -> new WorkspaceId(rs.getObject(1, UUID.class)),
                hash);
        if (workspaces.isEmpty()) throw failure(INVALID_INVITATION);
        WorkspaceId workspace = workspaces.getFirst();
        lockWorkspace(workspace);
        List<Invitation> found = jdbc.query(
                "select invitation_id, expires_at, revoked_at, used_by, permissions from auth_invitations where token_digest = ? for update",
                (rs, row) -> new Invitation(
                        rs.getObject(1, UUID.class),
                        workspace,
                        rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) != null,
                        rs.getObject(4, UUID.class),
                        readCapabilities(rs, 5)),
                hash);
        if (found.isEmpty()) throw failure(INVALID_INVITATION);
        return found.getFirst();
    }

    private void requireUsableInvitation(Invitation invitation, UUID account) {
        if (invitation.revoked()
                || !clock.instant().isBefore(invitation.expires())
                || (invitation.usedBy() != null && !invitation.usedBy().equals(account)))
            throw failure(INVALID_INVITATION);
    }

    private void join(Invitation invitation, UUID account) {
        List<Boolean> membership = jdbc.query(
                "select suspended_at is null from auth_memberships where workspace_id = ? and account_id = ?",
                (rs, row) -> rs.getBoolean(1),
                invitation.workspace().value(),
                account);
        if (!membership.isEmpty() && !membership.getFirst()) throw failure(INVALID_INVITATION);
        jdbc.update(connection -> {
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
                timestamp(),
                account,
                invitation.id());
    }

    private WorkspaceAccess requireKeyManager(AuthPrincipal actor, WorkspaceId workspace) {
        WorkspaceAccess access = authorize(actor, workspace, Capability.MANAGE_KEYS);
        if (access.role() != MembershipRole.OWNER) throw failure(DENIED);
        return access;
    }

    private void requireHumanOwner(AuthPrincipal actor, WorkspaceId workspace) {
        if (actor == null
                || actor.kind() != AuthPrincipal.Kind.ACCOUNT
                || authorize(actor, workspace).role() != MembershipRole.OWNER) throw failure(DENIED);
    }

    private void lockWorkspace(WorkspaceId workspace) {
        if (workspace == null
                || jdbc.query(
                                "select workspace_id from workspaces where workspace_id = ? for update",
                                (rs, row) -> rs.getObject(1),
                                workspace.value())
                        .isEmpty()) throw failure(DENIED);
    }

    UUID createAccount(String login, String encoded, boolean administrator) {
        UUID account = UUID.randomUUID();
        try {
            jdbc.update(
                    "insert into auth_accounts (account_id, login_name, password_hash, instance_admin) values (?, ?, ?, ?)",
                    account,
                    login,
                    encoded,
                    administrator);
        } catch (DataIntegrityViolationException exception) {
            throw failure(INVALID_INPUT);
        }
        return account;
    }

    private void publishRevocation(AuthRevocation event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(event);
            }
        });
    }

    String encodePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 256) throw failure(INVALID_INPUT);
        return passwords.encode(password);
    }

    String loginName(String login) {
        if (login == null || login.length() > 64) throw failure(INVALID_INPUT);
        String normalized = login.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,63}")) throw failure(INVALID_INPUT);
        return normalized;
    }

    String randomToken(String prefix) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String digestCredential(String token) {
        return digest(token == null || token.length() > 256 ? "" : token);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Timestamp timestamp() {
        return Timestamp.from(clock.instant());
    }

    private static AuthException failure(AuthException.Code code) {
        return new AuthException(code);
    }

    static AuthPrincipal accountPrincipal(UUID account) {
        return new AuthPrincipal(AuthPrincipal.Kind.ACCOUNT, account, account);
    }

    private record AccountCredential(UUID id, String hash) {}

    private record Invitation(
            UUID id,
            WorkspaceId workspace,
            Instant expires,
            boolean revoked,
            UUID usedBy,
            Set<Capability> permissions) {}

    private static void validatePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw failure(INVALID_INPUT);
    }

    public record Page<T>(List<T> items, long total, int offset, int limit) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record InvitationInfo(UUID id, Instant expiresAt, boolean revoked, boolean used) {}

    public record WorkspaceInvitationInfo(
            UUID id, Instant expiresAt, boolean revoked, boolean used, Set<Capability> permissions) {
        public WorkspaceInvitationInfo {
            permissions = Set.copyOf(permissions);
        }
    }

    public record MemberInfo(
            UUID accountId, String loginName, MembershipRole role, boolean active, Set<Capability> permissions) {
        public MemberInfo {
            permissions = Set.copyOf(permissions);
        }
    }

    public record ApiKeyInfo(UUID id, UUID accountId, Set<Capability> capabilities, boolean revoked) {
        public ApiKeyInfo {
            capabilities = Set.copyOf(capabilities);
        }
    }
}
