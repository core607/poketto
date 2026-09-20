package io.github.core607.poketto.auth;

import static io.github.core607.poketto.auth.AuthException.Code.ALREADY_INITIALIZED;
import static io.github.core607.poketto.auth.AuthException.Code.DENIED;
import static io.github.core607.poketto.auth.AuthException.Code.INVALID_CREDENTIALS;
import static io.github.core607.poketto.auth.AuthException.Code.INVALID_INPUT;
import static io.github.core607.poketto.auth.AuthException.Code.LAST_OWNER;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AccountPasswords accountPasswords;
    private final WorkspaceInvitations invitations;
    private final ApiKeys keys;

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
        this.accountPasswords = new AccountPasswords(this, jdbc, passwords);
        this.invitations = new WorkspaceInvitations(this, jdbc, transactions, clock);
        this.keys = new ApiKeys(this, jdbc, transactions);
    }

    /** Operator-only process entrance; no HTTP controller may expose this operation. */
    public AuthPrincipal initializeOwner(String login, String password) {
        String normalized = loginName(login);
        String encoded = encodePassword(password);
        return transactions.execute(status -> {
            Boolean initialized = jdbc.queryForObject(
                    "select initialized_at is not null from auth_initialization where singleton = true for update",
                    Boolean.class);
            if (Boolean.TRUE.equals(initialized)) {
                throw failure(ALREADY_INITIALIZED);
            }
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

    public AuthPrincipal authenticatePassword(String login, String password) {
        return accountPasswords.authenticate(login, password);
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
                        AuthPrincipal.Kind.API_KEY, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), 0),
                digestCredential(token),
                digestCredential(token),
                timestamp(),
                oauthResource,
                timestamp(),
                oauthResource);
        if (matches.isEmpty()) {
            AuditRecords.refused("key.authentication", INVALID_CREDENTIALS.name());
            throw failure(INVALID_CREDENTIALS);
        }
        AuditRecords.authenticated("key.authentication", matches.getFirst());
        return matches.getFirst();
    }

    public WorkspaceAccess authorize(AuthPrincipal principal, WorkspaceId workspace, Capability... required) {
        if (principal == null || workspace == null) {
            throw failure(DENIED);
        }
        if (principal.kind() == AuthPrincipal.Kind.ACCOUNT) {
            validateAccount(principal);
        }
        List<Membership> memberships = jdbc.query(
                "select role, permissions from auth_memberships where workspace_id = ? and account_id = ? and suspended_at is null",
                (rs, row) -> new Membership(MembershipRole.valueOf(rs.getString(1)), readCapabilities(rs, 2)),
                workspace.value(),
                principal.accountId());
        if (memberships.isEmpty()) {
            throw failure(DENIED);
        }
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
            if (stored.isEmpty()) {
                throw failure(DENIED);
            }
            capabilities = new HashSet<>(stored.getFirst());
            capabilities.retainAll(holderCapabilities);
        }
        if (!capabilities.containsAll(Arrays.asList(required))) {
            throw failure(DENIED);
        }
        return new WorkspaceAccess(workspace, principal, role, capabilities);
    }

    static Set<Capability> memberCapabilities(MembershipRole role, Set<Capability> permissions) {
        if (role == MembershipRole.OWNER) {
            return EnumSet.allOf(Capability.class);
        }
        // Execution is an independent machine grant. Membership permits public-scope use;
        // private reads, writes and publication still require their explicit content permissions.
        Set<Capability> result = EnumSet.of(Capability.EXECUTE_REPOSITORY);
        result.addAll(permissions);
        return result;
    }

    static Set<Capability> contentPermissions(Set<Capability> requested) {
        if (requested == null
                || requested.stream().anyMatch(Objects::isNull)
                || !CONTENT_PERMISSIONS.containsAll(requested)
                || (requested.contains(Capability.WRITE_PRIVATE) && !requested.contains(Capability.READ_PRIVATE))) {
            throw failure(INVALID_INPUT);
        }
        return Set.copyOf(requested);
    }

    static Set<Capability> readCapabilities(ResultSet row, int column) throws SQLException {
        return Arrays.stream((String[]) row.getArray(column).getArray())
                .map(Capability::valueOf)
                .collect(Collectors.toSet());
    }

    record Membership(MembershipRole role, Set<Capability> permissions) {}
    /** Machine workspace selection comes exclusively from the durable credential binding. */
    public WorkspaceId workspaceForKey(AuthPrincipal principal) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.API_KEY) {
            throw failure(DENIED);
        }
        var rows = jdbc.query(
                "select workspace_id from auth_api_keys where key_id=? and account_id=? and revoked_at is null",
                (rs, row) -> new WorkspaceId(rs.getObject(1, UUID.class)),
                principal.subjectId(),
                principal.accountId());
        if (rows.size() != 1) {
            throw failure(DENIED);
        }
        WorkspaceId workspace = rows.getFirst();
        authorize(principal, workspace);
        return workspace;
    }

    public Page<WorkspaceMembership> workspaces(AuthPrincipal principal, int offset, int limit) {
        if (principal == null || principal.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw failure(DENIED);
        }
        validateAccount(principal);
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
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workspace ownership requires a creation transaction");
        }
        if (actor == null || actor.kind() != AuthPrincipal.Kind.ACCOUNT) {
            throw failure(DENIED);
        }
        lockWorkspace(workspace);
        validateAccount(actor);
        if (!jdbc.queryForObject(
                        "select exists(select 1 from auth_accounts where account_id=?)",
                        Boolean.class,
                        actor.accountId())
                || jdbc.queryForObject(
                        "select exists(select 1 from auth_memberships where workspace_id=?)",
                        Boolean.class,
                        workspace.value())) {
            throw failure(DENIED);
        }
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
        return invitations.create(actor, workspace, requested);
    }

    public void revokeInvitation(AuthPrincipal actor, WorkspaceId workspace, UUID invitationId) {
        invitations.revoke(actor, workspace, invitationId);
    }

    public Page<WorkspaceInvitationInfo> listInvitations(
            AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        return invitations.list(actor, workspace, offset, limit);
    }

    /** Repeating a consumed token succeeds only for its original account with an active membership. */
    public WorkspaceId acceptInvitation(AuthPrincipal account, String token) {
        return invitations.accept(account, token);
    }

    /** Owners may issue keys; machine owners additionally need MANAGE_KEYS and cannot grant beyond their own capabilities. */
    public IssuedToken createApiKey(
            AuthPrincipal actor, WorkspaceId workspace, UUID holder, Set<Capability> requested) {
        return keys.create(actor, workspace, holder, requested);
    }

    /** OAuth delegates a human member's own permissions without granting key administration. */
    IssuedToken createOAuthKey(AuthPrincipal actor, WorkspaceId workspace, Set<Capability> requested) {
        return keys.createForOAuth(actor, workspace, requested);
    }

    public Page<ApiKeyInfo> listApiKeys(AuthPrincipal actor, WorkspaceId workspace, int offset, int limit) {
        return keys.list(actor, workspace, offset, limit);
    }

    public void revokeApiKey(AuthPrincipal actor, WorkspaceId workspace, UUID keyId) {
        keys.revoke(actor, workspace, keyId);
    }

    /** Caller holds the workspace row lock after validating an OAuth grant or its replay proof. */
    void revokeOAuthKey(WorkspaceId workspace, UUID keyId) {
        keys.revokeOAuth(workspace, keyId);
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
        if (role == null || account == null) {
            throw failure(INVALID_INPUT);
        }
        var held = new AtomicReference<Set<Capability>>(Set.of());
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
            if (members.isEmpty()) {
                throw failure(DENIED);
            }
            MemberInfo before = members.getFirst();
            held.set(before.active() ? memberCapabilities(before.role(), before.permissions()) : Set.of());
            if (before.active() && before.role() == MembershipRole.OWNER && (!active || role != MembershipRole.OWNER)) {
                Integer owners = jdbc.queryForObject(
                        "select count(*) from auth_memberships where workspace_id = ? and role = 'OWNER' and suspended_at is null",
                        Integer.class,
                        workspace.value());
                if (owners == null || owners <= 1) {
                    throw failure(LAST_OWNER);
                }
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
            keys.revokeMembershipKeys(workspace, account, before, role, active, permissions);
        });
        Set<Capability> effective = active ? memberCapabilities(role, permissions) : Set.of();
        // Narrowing also revokes the member's over-scoped keys, so recording it as a grant would
        // hide the withdrawal behind the action a reader filters on to find grants. Suspension is
        // a withdrawal even when the member already held nothing: the call still refreshes the
        // suspension and revokes keys again, so it is not a grant of the empty set.
        if (active && effective.containsAll(held.get())) {
            AuditRecords.granted("member.access.granted", actor, workspace, account, effective);
        } else {
            AuditRecords.granted("member.access.revoked", actor, workspace, account, effective);
        }
    }

    void requireHumanOwner(AuthPrincipal actor, WorkspaceId workspace) {
        if (actor == null
                || actor.kind() != AuthPrincipal.Kind.ACCOUNT
                || authorize(actor, workspace).role() != MembershipRole.OWNER) {
            throw failure(DENIED);
        }
    }

    void lockWorkspace(WorkspaceId workspace) {
        if (workspace == null
                || jdbc.query(
                                "select workspace_id from workspaces where workspace_id = ? for update",
                                (rs, row) -> rs.getObject(1),
                                workspace.value())
                        .isEmpty()) {
            throw failure(DENIED);
        }
    }

    UUID createAccount(String login, String encoded, boolean administrator) {
        UUID account = UUID.randomUUID();
        try {
            jdbc.update(
                    "insert into auth_accounts (account_id, login_name, password_hash, site_group, display_name) values (?, ?, ?, ?, ?)",
                    account,
                    login,
                    encoded,
                    administrator ? SiteGroup.ADMINISTRATOR.name() : SiteGroup.VIEWER.name(),
                    login);
        } catch (DataIntegrityViolationException exception) {
            throw failure(INVALID_INPUT);
        }
        return account;
    }

    void publishRevocation(AuthRevocation event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(event);
            }
        });
    }

    String encodePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 256) {
            throw failure(INVALID_INPUT);
        }
        return passwords.encode(password);
    }

    String loginName(String login) {
        if (login == null || login.length() > 64) {
            throw failure(INVALID_INPUT);
        }
        String normalized = login.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw failure(INVALID_INPUT);
        }
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

    static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    Timestamp timestamp() {
        return Timestamp.from(clock.instant());
    }

    static AuthException failure(AuthException.Code code) {
        return new AuthException(code);
    }

    static AuthPrincipal accountPrincipal(UUID account) {
        return new AuthPrincipal(AuthPrincipal.Kind.ACCOUNT, account, account, 0);
    }

    public void validateAccount(AuthPrincipal actor) {
        accountPasswords.validate(actor);
    }

    static void validatePage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw failure(INVALID_INPUT);
        }
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
