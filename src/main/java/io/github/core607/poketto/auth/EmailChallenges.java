package io.github.core607.poketto.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Single-use email proofs. The successful action and proof consumption commit together. */
public final class EmailChallenges {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final VerificationMail mail;
    private final Clock clock;
    private final EmailChallengeDigests digests;
    private final EmailSendLimits limits;
    private final SecureRandom random = new SecureRandom();

    public EmailChallenges(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            VerificationMail mail,
            String credential,
            int dailyLimit,
            Clock clock) {
        if (mail.available() && (credential == null || credential.isBlank())) {
            throw new IllegalArgumentException("Enabled verification mail requires a secret digest credential");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.mail = mail;
        this.clock = clock;
        this.digests = new EmailChallengeDigests(credential);
        this.limits = new EmailSendLimits(jdbc, digests, dailyLimit);
    }

    public boolean available() {
        return mail.available();
    }

    /** Recovery can reserve an indistinguishable challenge without delivering to an unknown address. */
    public Receipt send(String input, EmailPurpose purpose, UUID account, String address, boolean deliver) {
        requireNoTransaction();
        requireAvailable();
        requireBinding(purpose, account);
        String email = EmailAddress.normalize(input);
        UUID id = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        Instant now = clock.instant();
        Instant expires = now.plus(Duration.ofMinutes(10));
        transactions.executeWithoutResult(status -> {
            limits.reserve(email, address, now);
            jdbc.update("delete from auth_email_challenges where email=? and purpose=?", email, purpose.name());
            jdbc.update(
                    "insert into auth_email_challenges(challenge_id,email,purpose,account_id,code_digest,expires_at) values (?,?,?,?,?,?)",
                    id,
                    email,
                    purpose.name(),
                    account,
                    digests.of(binding(id, email, purpose, account, code)),
                    Timestamp.from(expires));
        });
        if (deliver) {
            mail.send(email, code, purpose, id);
            int updated = jdbc.update(
                    "update auth_email_challenges set delivered_at=? where challenge_id=? and expires_at>?",
                    Timestamp.from(clock.instant()),
                    id,
                    Timestamp.from(clock.instant()));
            if (updated != 1) {
                throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
            }
        }
        return new Receipt(id, expires, 60);
    }

    /** The callback must contain only bounded database work; do not send mail or call a provider from it. */
    public <T> T consume(Proof proof, EmailPurpose purpose, UUID account, Supplier<T> verifiedAction) {
        requireNoTransaction();
        requireAvailable();
        requireBinding(purpose, account);
        Outcome<T> result = transactions.execute(status -> verifyAndConsume(proof, purpose, account, verifiedAction));
        if (result == null || !result.valid()) {
            throw new EmailChallengeException(EmailChallengeException.Code.INVALID_CHALLENGE);
        }
        return result.value();
    }

    private <T> Outcome<T> verifyAndConsume(Proof proof, EmailPurpose purpose, UUID account, Supplier<T> action) {
        var rows = jdbc.query(
                "select code_digest,expires_at,delivered_at is not null,consumed_at is not null,failed_attempts "
                        + "from auth_email_challenges where challenge_id=? and email=? and purpose=? and account_id is not distinct from ? for update",
                (row, number) -> new Challenge(
                        row.getString(1),
                        row.getTimestamp(2).toInstant(),
                        row.getBoolean(3),
                        row.getBoolean(4),
                        row.getInt(5)),
                proof.challengeId(),
                proof.email(),
                purpose.name(),
                account);
        if (rows.isEmpty()) {
            return new Outcome<>(false, null);
        }
        Challenge challenge = rows.getFirst();
        if (!challenge.usable(clock.instant())) {
            return new Outcome<>(false, null);
        }
        if (!digests.matches(
                binding(proof.challengeId(), proof.email(), purpose, account, proof.code()), challenge.digest())) {
            jdbc.update(
                    "update auth_email_challenges set failed_attempts=failed_attempts+1 where challenge_id=?",
                    proof.challengeId());
            // Return instead of throwing in the transaction, so failed attempts cannot roll back.
            return new Outcome<>(false, null);
        }
        T value = action.get();
        jdbc.update(
                "update auth_email_challenges set consumed_at=? where challenge_id=?",
                Timestamp.from(clock.instant()),
                proof.challengeId());
        return new Outcome<>(true, value);
    }

    private void requireAvailable() {
        if (!available()) {
            throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Email challenge operations own their transaction boundary");
        }
    }

    private static void requireBinding(EmailPurpose purpose, UUID account) {
        if (purpose == null || (purpose == EmailPurpose.BIND) != (account != null)) {
            throw new EmailChallengeException(EmailChallengeException.Code.INVALID_INPUT);
        }
    }

    private static String binding(UUID id, String email, EmailPurpose purpose, UUID account, String code) {
        return id + "\0" + email + "\0" + purpose + "\0" + account + "\0" + code;
    }

    public record Receipt(UUID challengeId, Instant expiresAt, int retryAfterSeconds) {}

    public record Proof(UUID challengeId, String email, String code) {
        public Proof {
            Objects.requireNonNull(challengeId, "Email challenge ID is required");
            email = EmailAddress.normalize(email);
            if (code == null || !code.matches("[0-9]{6}")) {
                throw new EmailChallengeException(EmailChallengeException.Code.INVALID_INPUT);
            }
        }

        @Override
        public String toString() {
            return "EmailProof[REDACTED]";
        }
    }

    private record Challenge(String digest, Instant expires, boolean delivered, boolean consumed, int failures) {
        boolean usable(Instant now) {
            return delivered && !consumed && failures < 5 && now.isBefore(expires);
        }
    }

    private record Outcome<T>(boolean valid, T value) {}
}
