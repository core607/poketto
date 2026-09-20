package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted credentials are bound to both workspace identity and the canonical remote. */
final class RepositoryCredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    RepositoryCredentialCipher(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            key = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException invalid) {
            throw invalidKey();
        }
        try {
            if (decoded.length != 32) {
                throw invalidKey();
            }
            key = new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    boolean available() {
        return key != null;
    }

    byte[] encrypt(WorkspaceId workspace, String canonicalUri, Credentials credentials) {
        requireKey();
        byte[] username = credentials.username().getBytes(StandardCharsets.UTF_8);
        byte[] password = credentials.password().getBytes(StandardCharsets.UTF_8);
        byte[] plain = ByteBuffer.allocate(4 + username.length + password.length)
                .putInt(username.length)
                .put(username)
                .put(password)
                .array();
        try {
            return seal(repositoryContext(workspace, canonicalUri), plain);
        } finally {
            Arrays.fill(username, (byte) 0);
            Arrays.fill(password, (byte) 0);
            Arrays.fill(plain, (byte) 0);
        }
    }

    Credentials decrypt(WorkspaceId workspace, String canonicalUri, byte[] envelope) {
        byte[] plain = null;
        try {
            plain = open(repositoryContext(workspace, canonicalUri), envelope);
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            int length = buffer.getInt();
            if (length < 1 || length > buffer.remaining()) {
                throw unavailable();
            }
            return new Credentials(
                    new String(plain, 4, length, StandardCharsets.UTF_8),
                    new String(plain, 4 + length, plain.length - 4 - length, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | BufferUnderflowException invalid) {
            throw unavailable();
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    byte[] seal(byte[] context, byte[] plain) {
        requireKey();
        if (plain.length > 19_000) {
            throw new IllegalArgumentException("Repository credential plaintext exceeds its limit");
        }
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, context, nonce).doFinal(plain);
            return ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                    .put((byte) 1)
                    .put(nonce)
                    .put(encrypted)
                    .array();
        } catch (GeneralSecurityException invalid) {
            throw new ContentRepositoryException("Workspace repository credentials are unavailable", invalid);
        }
    }

    byte[] open(byte[] context, byte[] envelope) {
        requireKey();
        if (envelope == null || envelope.length < 33 || envelope.length > 20_000 || envelope[0] != 1) {
            throw unavailable();
        }
        try {
            return cipher(Cipher.DECRYPT_MODE, context, Arrays.copyOfRange(envelope, 1, 13))
                    .doFinal(envelope, 13, envelope.length - 13);
        } catch (GeneralSecurityException invalid) {
            throw new ContentRepositoryException("Workspace repository credentials are unavailable", invalid);
        }
    }

    private static byte[] repositoryContext(WorkspaceId workspace, String canonicalUri) {
        return ("poketto-repository-v1\n" + workspace + "\n" + canonicalUri).getBytes(StandardCharsets.UTF_8);
    }

    private Cipher cipher(int mode, byte[] context, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context);
        return cipher;
    }

    private void requireKey() {
        if (key == null) {
            throw new ContentRepositoryException("Workspace repository credential encryption is not configured");
        }
    }

    private static IllegalArgumentException invalidKey() {
        return new IllegalArgumentException("Repository credential key must encode exactly 32 bytes as Base64");
    }

    private static ContentRepositoryException unavailable() {
        return new ContentRepositoryException("Workspace repository credentials are unavailable");
    }

    record Credentials(String username, String password) {
        Credentials {
            if (username == null
                    || username.isBlank()
                    || username.length() > 256
                    || password == null
                    || password.isBlank()
                    || password.length() > 4096) {
                throw new IllegalArgumentException(
                        "Repository username and token are required and must fit their limits");
            }
        }

        @Override
        public String toString() {
            return "RepositoryCredentials[redacted]";
        }
    }
}
