package io.github.core607.poketto.auth.internal;

import io.github.core607.poketto.auth.EmailAddress;
import io.github.core607.poketto.auth.EmailChallengeException;
import io.github.core607.poketto.auth.EmailPurpose;
import io.github.core607.poketto.auth.VerificationMail;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Fixed provider endpoint with bounded responses; no address, code or provider response is logged. */
final class ResendVerificationMail implements VerificationMail, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ResendVerificationMail.class);
    private final HttpClient http;
    private final URI endpoint;
    private final String credential;
    private final String from;
    private final JsonMapper json = JsonMapper.builder().build();

    ResendVerificationMail(String credential, String from) {
        this(
                credential,
                from,
                URI.create("https://api.resend.com/emails"),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    ResendVerificationMail(String credential, String from, URI endpoint, HttpClient http) {
        this.credential = credential == null ? "" : credential;
        if (this.credential.contains("\r") || this.credential.contains("\n")) {
            throw new IllegalArgumentException("Mail credential must be a single line");
        }
        this.from = from;
        this.endpoint = endpoint;
        this.http = http;
        if (available()) {
            validateSender(from);
        }
    }

    private static void validateSender(String from) {
        if (from == null || from.length() > 320 || from.contains("\r") || from.contains("\n")) {
            throw new IllegalArgumentException("A valid verification sender is required");
        }
        int angle = from.indexOf('<');
        if (angle >= 0 && !from.endsWith(">")) {
            throw new IllegalArgumentException("A valid verification sender is required");
        }
        String address = angle < 0 ? from : from.substring(angle + 1, from.length() - 1);
        EmailAddress.normalize(address);
    }

    @Override
    public boolean available() {
        return !credential.isBlank();
    }

    @Override
    public void send(String email, String code, EmailPurpose purpose, UUID messageId) {
        if (!available()) {
            throw unavailable();
        }
        String subject =
                switch (purpose) {
                    case SIGNUP -> "验证你的 Poketto 注册邮箱";
                    case BIND -> "验证你的 Poketto 绑定邮箱";
                    case RECOVERY -> "重置你的 Poketto 密码";
                };
        String text = "你的验证码是：" + code + "\n\n验证码在 10 分钟内有效，请勿分享给他人。\n如果不是你本人操作，请忽略这封邮件。";
        var body = new Message(from, List.of(email), subject, text);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Poketto")
                .header("Idempotency-Key", "email-verification/" + messageId)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        send(request);
    }

    private void send(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<Void> response = http.send(
                        request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.discarding(), 16 * 1024));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
                if (response.statusCode() < 500 || attempt == 1) {
                    log.warn("Verification mail provider rejected delivery with HTTP {}", response.statusCode());
                    throw unavailable();
                }
            } catch (IOException exception) {
                if (attempt == 1) {
                    log.warn("Verification mail transport failed", exception);
                    throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE, exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE, exception);
            }
        }
        throw unavailable();
    }

    private static EmailChallengeException unavailable() {
        return new EmailChallengeException(EmailChallengeException.Code.DELIVERY_UNAVAILABLE);
    }

    @Override
    public void close() {
        http.close();
    }

    private record Message(String from, List<String> to, String subject, String text) {
        @Override
        public String toString() {
            return "VerificationMail[REDACTED]";
        }
    }
}
