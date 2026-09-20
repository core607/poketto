package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class SiteGroupMigrationIntegrationIT {
    private static final String PASSWORD = "Migration-fixture-password-2026!";
    private static final String KEY = "pk_migration-fixture-existing-key";

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    @Test
    void existingAccountsKeepIdentityAndMembershipsWithGroupsBackfilledFromCurrentAuthority() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).target("7").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug,public_delivery) values (?,'Existing','existing',true)",
                workspace);
        seedAccounts(jdbc, workspace);
        var accounts =
                jdbc.queryForList("select account_id,login_name,password_hash from auth_accounts order by login_name");
        var memberships = jdbc.queryForList(
                "select workspace_id,account_id,role,permissions::text from auth_memberships order by account_id");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForList(
                        "select account_id,login_name,password_hash from auth_accounts order by login_name"))
                .isEqualTo(accounts);
        assertThat(
                        jdbc.queryForList(
                                "select workspace_id,account_id,role,permissions::text from auth_memberships order by account_id"))
                .isEqualTo(memberships);
        assertThat(jdbc.queryForList("select site_group from auth_accounts order by login_name", String.class))
                .containsExactly("ADMINISTRATOR", "CREATOR", "CREATOR", "VIEWER", "VIEWER", "CREATOR");
        assertThat(jdbc.queryForObject(
                        "select public_delivery from workspaces where workspace_id=?", Boolean.class, workspace))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select eligible from website_owner_eligibility where workspace_id=?",
                        Boolean.class,
                        workspace))
                .isTrue();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) values (?,'new-account','new-hash')",
                UUID.randomUUID());
        assertThat(jdbc.queryForObject(
                        "select site_group from auth_accounts where login_name='new-account'", String.class))
                .isEqualTo("VIEWER");
        var auth = new AuthService(
                jdbc, new DataSourceTransactionManager(source), passwords(), event -> {}, Clock.systemUTC());
        AuthPrincipal owner = auth.authenticatePassword("owner", PASSWORD);
        auth.authorize(owner, new WorkspaceId(workspace), Capability.WRITE_PRIVATE, Capability.PUBLISH);
        AuthPrincipal machine = auth.authenticateApiKey(KEY);
        assertThat(machine.accountId()).isEqualTo(owner.accountId());
        auth.authorize(machine, new WorkspaceId(workspace), Capability.WRITE_PRIVATE);
    }

    private static void seedAccounts(JdbcTemplate jdbc, UUID workspace) {
        String hash = passwords().encode(PASSWORD);
        for (String name : List.of("administrator", "owner", "writer", "publisher", "reader", "visitor")) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "insert into auth_accounts(account_id,login_name,password_hash,instance_admin) values (?,?,?,?)",
                    id,
                    name,
                    hash,
                    name.equals("administrator"));
            if (!name.equals("visitor")) {
                String permissions =
                        switch (name) {
                            case "writer" -> "{READ_PRIVATE,WRITE_PRIVATE}";
                            case "publisher" -> "{PUBLISH}";
                            default -> "{}";
                        };
                jdbc.update(
                        "insert into auth_memberships(workspace_id,account_id,role,permissions) values (?,?,?,?::text[])",
                        workspace,
                        id,
                        name.equals("owner") ? "OWNER" : "MEMBER",
                        permissions);
            }
            if (name.equals("owner")) {
                jdbc.update(
                        "insert into auth_api_keys(key_id,workspace_id,account_id,created_by,token_digest,capabilities) values (?,?,?,?,?,'{READ_PRIVATE,WRITE_PRIVATE}')",
                        UUID.randomUUID(),
                        workspace,
                        id,
                        id,
                        AuthService.digestCredential(KEY));
            }
        }
    }

    private static DelegatingPasswordEncoder passwords() {
        return new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
