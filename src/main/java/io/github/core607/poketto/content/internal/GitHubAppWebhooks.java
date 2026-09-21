package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.GitHubWebhookException;
import io.github.core607.poketto.content.GitHubWebhooks;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates the original bytes before decoding or recording any delivery. */
final class GitHubAppWebhooks implements GitHubWebhooks {
    private final long appId;
    private final SecretKeySpec key;
    private final GitHubWebhookStore store;

    GitHubAppWebhooks(long appId, String secret, GitHubWebhookStore store) {
        if (appId <= 0) {
            throw new IllegalArgumentException("GitHub App ID must be positive");
        }
        if (!validSecret(secret)) {
            throw new IllegalArgumentException("GitHub webhook secret must contain 32-256 characters");
        }
        this.appId = appId;
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.store = store;
    }

    private static boolean validSecret(String secret) {
        return secret != null && secret.length() >= 32 && secret.length() <= 256;
    }

    @Override
    public void receive(String delivery, String event, String signature, byte[] body) {
        if (body.length > MAX_BODY_BYTES) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.TOO_LARGE);
        }
        authenticate(signature, body);
        if (!validDelivery(delivery)) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.MALFORMED);
        }
        if (event == null || !event.matches("[a-z_]{1,64}")) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.MALFORMED);
        }
        GitHubWebhookPayload payload = GitHubWebhookPayload.read(body);
        payload.validate(event, appId);
        store.accept(UUID.fromString(delivery), event, payload);
    }

    private void authenticate(String signature, byte[] body) {
        if (signature == null || !signature.matches("sha256=[0-9a-f]{64}")) {
            throw new GitHubWebhookException(GitHubWebhookException.Code.INVALID_SIGNATURE);
        }
        byte[] supplied = HexFormat.of().parseHex(signature.substring(7));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            if (!MessageDigest.isEqual(supplied, mac.doFinal(body))) {
                throw new GitHubWebhookException(GitHubWebhookException.Code.INVALID_SIGNATURE);
            }
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("GitHub webhook authentication is unavailable", unavailable);
        }
    }

    private static boolean validDelivery(String delivery) {
        return delivery != null
                && delivery.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
