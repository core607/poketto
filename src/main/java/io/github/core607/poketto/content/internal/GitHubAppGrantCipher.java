package io.github.core607.poketto.content.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Shares the repository encryption key but separates App, account, owner and grant-version contexts. */
final class GitHubAppGrantCipher {
    private final RepositoryCredentialCipher cipher;
    private final String clientId;

    GitHubAppGrantCipher(RepositoryCredentialCipher cipher, String clientId) {
        if (clientId == null || !clientId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("GitHub App client ID is invalid");
        }
        this.cipher = cipher;
        this.clientId = clientId;
    }

    byte[] seal(UUID account, long owner, long version, GitHubAppOAuth.Tokens tokens) {
        byte[] context = context(account, owner, version);
        byte[] plain = GitHubAppJson.write(tokens);
        try {
            return cipher.seal(context, plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    GitHubAppOAuth.Tokens open(UUID account, long owner, long version, byte[] envelope) {
        byte[] plain = cipher.open(context(account, owner, version), envelope);
        try {
            return GitHubAppJson.read(plain, GitHubAppOAuth.Tokens.class);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private byte[] context(UUID account, long owner, long version) {
        Objects.requireNonNull(account, "GitHub grant account is required");
        if (owner <= 0 || version <= 0) {
            throw new IllegalArgumentException("GitHub grant owner and version must be positive");
        }
        return ("poketto-github-grant-v1\n" + clientId + "\n" + account + "\n" + owner + "\n" + version)
                .getBytes(StandardCharsets.UTF_8);
    }
}
