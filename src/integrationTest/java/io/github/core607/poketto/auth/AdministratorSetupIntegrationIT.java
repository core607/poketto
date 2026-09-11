package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
class AdministratorSetupIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private DataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate workspaces,auth_accounts cascade");
        jdbc.execute("update auth_initialization set initialized_at=null");
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default) values (?,'Test space',true)",
                UUID.randomUUID());
    }

    @Test
    void operatorCreatesOneAdministratorAndLoginUsesTheExistingPasswordContract() {
        String password = UUID.randomUUID().toString();
        Input input = new Input("AdMiN", password, password);
        assertThat(AdministratorSetup.initialize(source, input)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_accounts where instance_admin", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("select count(*) from auth_memberships where role='OWNER'", Integer.class))
                .isOne();
        var encoder = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        AuthService auth = new AuthService(
                jdbc, new DataSourceTransactionManager(source), encoder, event -> {}, Clock.systemUTC());
        assertThat(auth.authenticatePassword("admin", password)).isNotNull();
        assertThat(input.messages.toString()).doesNotContain(password);
        assertThat(new String(input.password)).isEqualTo("\0".repeat(password.length()));
        assertThat(new String(input.confirmation)).isEqualTo("\0".repeat(password.length()));
        String stored = jdbc.queryForObject("select password_hash from auth_accounts", String.class);
        assertThat(stored).startsWith("{pbkdf2-v5.8}").doesNotContain(password);
    }

    @Test
    void repetitionDoesNotEvenPromptForCredentialsOrReplaceTheOwner() {
        assertThat(AdministratorSetup.initialize(source, new Input("owner", "a-long-password", "a-long-password")))
                .isZero();
        Input repeated = new Input("different", "another-password", "another-password");
        assertThat(AdministratorSetup.initialize(source, repeated)).isOne();
        assertThat(repeated.prompted).isFalse();
        assertThat(jdbc.queryForObject("select login_name from auth_accounts", String.class))
                .isEqualTo("owner");
    }

    @Test
    void mismatchInvalidInputAndCancellationKeepInitializationAvailable() {
        for (Input input : List.of(
                new Input("owner", "first-password", "second-password"),
                new Input("!", "valid-password", "valid-password"),
                new Input("owner", "short", "short"),
                new Input(null, "valid-password", "valid-password"),
                new Input("owner", null, null))) {
            assertThat(AdministratorSetup.initialize(source, input)).isOne();
            assertThat(jdbc.queryForObject("select count(*) from auth_accounts", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select initialized_at is null from auth_initialization", Boolean.class))
                    .isTrue();
            if (input.password != null && input.prompted && input.username != null)
                assertThat(input.password).containsOnly('\0');
        }
    }

    private static final class Input implements AdministratorSetup.Prompt {
        private final String username;
        private final char[] password;
        private final char[] confirmation;
        private final List<String> messages = new ArrayList<>();
        private boolean prompted;

        private Input(String username, String password, String confirmation) {
            this.username = username;
            this.password = password == null ? null : password.toCharArray();
            this.confirmation = confirmation == null ? null : confirmation.toCharArray();
        }

        public String username() {
            prompted = true;
            return username;
        }

        public char[] password(boolean confirm) {
            return confirm ? confirmation : password;
        }

        public void message(String text) {
            messages.add(text);
        }
    }
}
