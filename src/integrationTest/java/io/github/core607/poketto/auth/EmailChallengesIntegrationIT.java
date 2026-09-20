package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class EmailChallengesIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private MutableClock clock;
    private MailFixture mail;
    private EmailChallenges challenges;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table if not exists verification_effects(id uuid primary key)");
        jdbc.execute("truncate auth_email_challenges,auth_email_limits,verification_effects");
        manager = new DataSourceTransactionManager(source);
        clock = new MutableClock();
        mail = new MailFixture();
        challenges = service(100);
    }

    @Test
    void storesOnlyKeyedProofAndConsumesItWithTheVerifiedDatabaseAction() {
        EmailChallenges.Receipt receipt =
                challenges.send(" Reader@Example.test ", EmailPurpose.SIGNUP, null, "192.0.2.1", true);
        Message message = mail.messages.getFirst();
        assertThat(message.email()).isEqualTo("reader@example.test");
        assertThat(message.code()).matches("[0-9]{6}");
        assertThat(message.id()).isEqualTo(receipt.challengeId());
        assertThat(receipt.expiresAt()).isEqualTo(clock.instant().plusSeconds(600));
        String digest = jdbc.queryForObject("select code_digest from auth_email_challenges", String.class);
        assertThat(digest).hasSize(64).isNotEqualTo(message.code()).doesNotContain(message.email());
        UUID action = UUID.randomUUID();
        assertThat(challenges.consume(message.proof(), EmailPurpose.SIGNUP, null, () -> effect(action)))
                .isEqualTo(action);
        assertThat(jdbc.queryForObject("select count(*) from verification_effects", Integer.class))
                .isOne();
        assertInvalid(
                () -> challenges.consume(message.proof(), EmailPurpose.SIGNUP, null, () -> effect(UUID.randomUUID())));
        assertThat(message.proof().toString()).doesNotContain(message.email(), message.code());
    }

    @Test
    void failedAttemptsSurviveRestartAndCorrectCodeCannotBypassTheFiveAttemptLimit() {
        send("reader@example.test");
        Message message = mail.messages.getFirst();
        String wrong = message.code().equals("000000") ? "111111" : "000000";
        var proof = new EmailChallenges.Proof(message.id(), message.email(), wrong);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertInvalid(() -> service(100).consume(proof, EmailPurpose.SIGNUP, null, () -> "unused"));
        }
        assertInvalid(() -> service(100).consume(message.proof(), EmailPurpose.SIGNUP, null, () -> "unused"));
        assertThat(jdbc.queryForObject("select failed_attempts from auth_email_challenges", Integer.class))
                .isEqualTo(5);
    }

    @Test
    void resendingReplacesOldCodeAndExpiresAtTheExactBoundary() {
        send("reader@example.test");
        Message old = mail.messages.getFirst();
        clock.advance(59);
        assertRateLimited(() -> send("reader@example.test"));
        clock.advance(1);
        send("reader@example.test");
        Message replacement = mail.messages.getLast();
        assertThat(replacement.id()).isNotEqualTo(old.id());
        assertInvalid(() -> challenges.consume(old.proof(), EmailPurpose.SIGNUP, null, () -> "unused"));
        clock.advance(600);
        assertInvalid(() -> challenges.consume(replacement.proof(), EmailPurpose.SIGNUP, null, () -> "unused"));
    }

    @Test
    void deliveryFailureLeavesNoUsableProofAndStillConsumesTheSendAllowance() {
        mail.fail = true;
        assertCode(() -> send("reader@example.test"), EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
        Message failed = mail.messages.getFirst();
        assertInvalid(() -> challenges.consume(failed.proof(), EmailPurpose.SIGNUP, null, () -> "unused"));
        assertThat(jdbc.queryForObject("select delivered_at is null from auth_email_challenges", Boolean.class))
                .isTrue();
        assertRateLimited(() -> send("reader@example.test"));
        clock.advance(60);
        mail.fail = false;
        send("reader@example.test");
        assertThat(challenges.consume(mail.messages.getLast().proof(), EmailPurpose.SIGNUP, null, () -> "verified"))
                .isEqualTo("verified");
    }

    @Test
    void failedBusinessActionRollsBackProofConsumptionAndItsOwnWrites() {
        send("reader@example.test");
        Message message = mail.messages.getFirst();
        assertThatThrownBy(() -> challenges.consume(message.proof(), EmailPurpose.SIGNUP, null, () -> {
                    effect(UUID.randomUUID());
                    throw new IllegalStateException("fixture business failure");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from verification_effects", Integer.class))
                .isZero();
        challenges.consume(message.proof(), EmailPurpose.SIGNUP, null, () -> effect(UUID.randomUUID()));
        assertThat(jdbc.queryForObject("select count(*) from verification_effects", Integer.class))
                .isOne();
    }

    @Test
    void rejectsForeignPurposesAccountsAndEnclosingTransactions() {
        UUID account = account();
        UUID stranger = account();
        challenges.send("binding@example.test", EmailPurpose.BIND, account, "192.0.2.1", true);
        Message message = mail.messages.getFirst();
        assertInvalid(() -> challenges.consume(message.proof(), EmailPurpose.BIND, stranger, () -> "unused"));
        assertInvalid(() -> challenges.consume(message.proof(), EmailPurpose.RECOVERY, null, () -> "unused"));
        assertThatThrownBy(() -> new TransactionTemplate(manager)
                        .execute(status ->
                                challenges.consume(message.proof(), EmailPurpose.BIND, account, () -> "unused")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(challenges.consume(message.proof(), EmailPurpose.BIND, account, () -> "verified"))
                .isEqualTo("verified");
    }

    @Test
    void concurrentConsumptionCommitsExactlyOneBusinessAction() throws Exception {
        send("reader@example.test");
        Message message = mail.messages.getFirst();
        var ready = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(1, 2).stream()
                    .map(index -> executor.submit(() -> {
                        if (!ready.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("fixture coordination timeout");
                        }
                        try {
                            service(100)
                                    .consume(
                                            message.proof(),
                                            EmailPurpose.SIGNUP,
                                            null,
                                            () -> effect(UUID.randomUUID()));
                            return "consumed";
                        } catch (EmailChallengeException failure) {
                            return failure.code().name();
                        }
                    }))
                    .toList();
            ready.countDown();
            assertThat(List.of(
                            futures.get(0).get(10, TimeUnit.SECONDS),
                            futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("consumed", "INVALID_CHALLENGE");
        }
        assertThat(jdbc.queryForObject("select count(*) from verification_effects", Integer.class))
                .isOne();
    }

    @Test
    void budgetsAreDurableAndRecoveryWithoutDeliveryDoesNotRevealAnAccount() {
        challenges = service(2);
        send("reader@example.test");
        EmailChallenges.Receipt hidden =
                challenges.send("unknown@example.test", EmailPurpose.RECOVERY, null, "192.0.2.2", false);
        assertThat(hidden.challengeId()).isNotNull();
        assertThat(mail.messages).hasSize(1);
        assertRateLimited(() -> service(2).send("third@example.test", EmailPurpose.SIGNUP, null, "192.0.2.3", true));
        assertThat(jdbc.queryForObject(
                        "select send_count from auth_email_limits where bucket='installation'", Integer.class))
                .isEqualTo(2);
        clock.advance(86400);
        send("third@example.test");
        assertThat(mail.messages).hasSize(2);
    }

    @Test
    void emailCooldownCrossesPurposesAndMidnightAndAddressBudgetBoundsDistinctRecipients() {
        clock.now = Instant.parse("2026-09-20T23:59:30Z");
        send("reader@example.test");
        clock.advance(40);
        assertRateLimited(
                () -> challenges.send("reader@example.test", EmailPurpose.RECOVERY, null, "192.0.2.1", false));
        clock.advance(20);
        challenges.send("reader@example.test", EmailPurpose.RECOVERY, null, "192.0.2.1", false);
        for (int index = 0; index < 19; index++) {
            send("other" + index + "@example.test");
        }
        assertRateLimited(() -> send("over-limit@example.test"));
        assertThat(mail.messages).hasSize(20);
    }

    private EmailChallenges service(int dailyLimit) {
        return new EmailChallenges(jdbc, manager, mail, "synthetic-mail-secret", dailyLimit, clock);
    }

    private void send(String email) {
        challenges.send(email, EmailPurpose.SIGNUP, null, "192.0.2.1", true);
    }

    private UUID effect(UUID id) {
        jdbc.update("insert into verification_effects values (?)", id);
        return id;
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into auth_accounts(account_id,login_name,password_hash) values (?,?,?)",
                id,
                "fixture-" + id,
                "fixture-unused-hash");
        return id;
    }

    private static void assertInvalid(Runnable action) {
        assertCode(action, EmailChallengeException.Code.INVALID_CHALLENGE);
    }

    private static void assertRateLimited(Runnable action) {
        assertCode(action, EmailChallengeException.Code.RATE_LIMITED);
    }

    private static void assertCode(Runnable action, EmailChallengeException.Code code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        EmailChallengeException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private record Message(String email, String code, UUID id) {
        EmailChallenges.Proof proof() {
            return new EmailChallenges.Proof(id, email, code);
        }
    }

    private static final class MailFixture implements VerificationMail {
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private boolean fail;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void send(String email, String code, EmailPurpose purpose, UUID messageId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            messages.add(new Message(email, code, messageId));
            if (fail) {
                throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-20T00:00:00Z");

        void advance(long seconds) {
            now = now.plus(Duration.ofSeconds(seconds));
        }

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
}
