package io.github.core607.poketto.auth;

import java.io.Console;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** Interactive operator command using the deployment's database credentials, without starting HTTP. */
public final class AdministratorSetup {
    private AdministratorSetup() {}

    public static int run(String[] args) {
        if (!Arrays.equals(args, new String[] {"admin", "init"})) {
            System.err.println("Usage: admin init (interactive terminal required)");
            return 2;
        }
        Console console = System.console();
        if (console == null) {
            System.err.println(
                    "Administrator setup requires an interactive terminal. Passwords cannot be supplied as arguments or piped input.");
            return 2;
        }
        try {
            DataSource source = new DriverManagerDataSource(
                    required("SPRING_DATASOURCE_URL"),
                    required("SPRING_DATASOURCE_USERNAME"),
                    required("SPRING_DATASOURCE_PASSWORD"));
            return initialize(source, new Prompt() {
                public String username() {
                    return console.readLine("Administrator username: ");
                }

                public char[] password(boolean confirmation) {
                    return console.readPassword(confirmation ? "Confirm password: " : "Password: ");
                }

                public void message(String message) {
                    console.printf("%s%n", message);
                }
            });
        } catch (RuntimeException failure) {
            console.printf(
                    "Administrator setup could not connect to the deployment database. Verify its configuration and start the application first.%n");
            return 1;
        }
    }

    static int initialize(DataSource source, Prompt prompt) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select initialized_at is not null from auth_initialization where singleton=true", Boolean.class))) {
            prompt.message("An administrator already exists. This command cannot reset or replace accounts.");
            return 1;
        }
        char[] password = null;
        char[] confirmation = null;
        try {
            String username = prompt.username();
            if (username == null) {
                prompt.message("Setup cancelled. No account was created.");
                return 1;
            }
            password = prompt.password(false);
            if (password == null) {
                prompt.message("Setup cancelled. No account was created.");
                return 1;
            }
            confirmation = prompt.password(true);
            if (confirmation == null || !Arrays.equals(password, confirmation)) {
                prompt.message("Passwords do not match. No account was created.");
                return 1;
            }
            var encoder = new DelegatingPasswordEncoder(
                    "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
            AuthService auth = new AuthService(
                    jdbc, new DataSourceTransactionManager(source), encoder, event -> {}, Clock.systemUTC());
            auth.initializeOwner(username, new String(password));
            prompt.message("Administrator created. Sign in to Poketto with this account.");
            return 0;
        } catch (AuthException failure) {
            prompt.message(
                    failure.code() == AuthException.Code.ALREADY_INITIALIZED
                            ? "An administrator already exists. This command cannot reset or replace accounts."
                            : "The account was not created. Use a 3-64 character login name and a 12-256 character password.");
            return 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Deployment database configuration is incomplete");
        return value;
    }

    interface Prompt {
        String username();

        char[] password(boolean confirmation);

        void message(String message);
    }
}
