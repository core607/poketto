package io.github.core607.poketto.community.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.community.Readership;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.internal.CommunityPublicationFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
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
class ReadershipIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private static final String BROWSER = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) Safari/605.1.15";
    private final Snapshots snapshots = new Snapshots();
    private JdbcTemplate jdbc;
    private WorkspaceId workspace;
    private final MovingClock clock = new MovingClock();

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("truncate article_views,workspaces,auth_accounts cascade");
        jdbc.update("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,public_slug,public_delivery,is_default) values (?,'Readers','readers',true,true)",
                workspace.value());
        // A website is served only while an eligible owner holds the space.
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2-v5.8", Map.of("pbkdf2-v5.8", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        new AuthService(jdbc, new DataSourceTransactionManager(source), passwords, event -> {}, Clock.systemUTC())
                .initializeOwner("readers-owner", "Readers-fixture-password-2026!");
        snapshots.snapshot = snapshot("/雨后/100%");
    }

    private Readership readership(int capacity) {
        return readership(capacity, 300);
    }

    private Readership readership(int capacity, int perAddress) {
        var targets = new CommunityTargets(CommunityPublicationFixture.publications(jdbc), snapshots);
        return new JdbcReadership(jdbc, targets, new ReaderDigests(capacity, perAddress, () -> new byte[] {7}), clock);
    }

    @Test
    void aReaderCountsOnceADayAndOnlyForPublicRoutes() {
        Readership readers = readership(100);

        readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        readers.record("readers", "/雨后/100%", "198.51.100.9", BROWSER);
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(2));

        clock.now = clock.now.plusSeconds(86_400);
        readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(3));
        assertThat(jdbc.queryForObject("select count(*) from article_views", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void crawlersUnknownRoutesAndClosedSpacesCountNothing() {
        Readership readers = readership(100);

        readers.record("readers", "/雨后/100%", "203.0.113.5", "Googlebot/2.1 (+http://www.google.com/bot.html)");
        readers.record("readers", "/雨后/100%", "203.0.113.5", "");
        readers.record("readers", "/雨后/100%", "203.0.113.5", null);
        readers.record("readers", "/missing", "203.0.113.5", BROWSER);
        readers.record("readers", "no-slash", "203.0.113.5", BROWSER);
        readers.record("readers", "/" + "x".repeat(JdbcReadership.MAX_ROUTE), "203.0.113.5", BROWSER);
        readers.record("elsewhere", "/雨后/100%", "203.0.113.5", BROWSER);
        assertThat(jdbc.queryForObject("select count(*) from article_views", Long.class))
                .isZero();
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(0));
        assertThat(readers.total("readers", "/missing")).isEmpty();
        assertThat(readers.total("elsewhere", "/雨后/100%")).isEmpty();

        snapshots.expired = true;
        readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        assertThat(readers.total("readers", "/雨后/100%")).isEmpty();
    }

    @Test
    void withdrawnRoutesKeepTheirCountForTheirReturn() {
        Readership readers = readership(1);
        readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        // The day's capacity is used up, so a second reader is not counted.
        readers.record("readers", "/雨后/100%", "198.51.100.9", BROWSER);

        snapshots.snapshot = snapshot("/elsewhere");
        assertThat(readers.total("readers", "/雨后/100%")).isEmpty();
        snapshots.snapshot = snapshot("/雨后/100%");
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(1));
    }

    @Test
    void oneAddressCannotInflateCountsByRotatingUserAgents() {
        Readership readers = readership(100, 2);
        for (int agent = 0; agent < 5; agent++) {
            readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER + " build/" + agent);
        }
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(2));
    }

    @Test
    void aFailedWriteStillAnswersQuietlyAndLetsTheReaderCountLater() {
        Readership readers = readership(100);
        jdbc.execute("alter table article_views rename to article_views_offline");
        try {
            readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        } finally {
            jdbc.execute("alter table article_views_offline rename to article_views");
        }
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(0));
        readers.record("readers", "/雨后/100%", "203.0.113.5", BROWSER);
        assertThat(readers.total("readers", "/雨后/100%")).isEqualTo(OptionalLong.of(1));
    }

    private PublicContentSnapshot snapshot(String route) {
        // Website snapshots are checked against the real clock; the moving clock only picks the day.
        Instant now = Instant.now();
        return new PublicContentSnapshot(
                workspace,
                Optional.of("a".repeat(40)),
                now,
                now.plusSeconds(3600),
                List.of(new PublicArticle(
                        "public/rain.md", route, "Rain", "# Rain", List.of(), now, now, false, "", null, false)));
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Snapshots implements PublicContentSnapshots {
        private PublicContentSnapshot snapshot;
        private boolean expired;

        @Override
        public void ensureReady(WorkspaceId workspace) {}

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            return current(workspace);
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspace) {
            if (expired) {
                throw new ContentRepositoryException("fixture expired");
            }
            return snapshot;
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
            return operation.apply(current(workspace));
        }
    }
}
