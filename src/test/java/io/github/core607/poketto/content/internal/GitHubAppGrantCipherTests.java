package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GitHubAppGrantCipherTests {
    @Test
    void separatesAccountProviderOwnerVersionAndExistingRepositoryCredentialContexts() {
        var repositoryCipher = new RepositoryCredentialCipher(key());
        var cipher = new GitHubAppGrantCipher(repositoryCipher, "Iv.fixture");
        UUID account = UUID.randomUUID();
        var tokens = new GitHubAppOAuth.Tokens(
                "ghu_fixture",
                Instant.parse("2026-09-21T01:00:00Z"),
                "ghr_fixture",
                Instant.parse("2027-01-21T00:00:00Z"));
        byte[] sealed = cipher.seal(account, 42, 1, tokens);
        assertThat(cipher.open(account, 42, 1, sealed)).isEqualTo(tokens);
        assertThat(cipher.seal(account, 42, 1, tokens)).isNotEqualTo(sealed);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("ghu_fixture", "ghr_fixture");
        assertThatThrownBy(() -> cipher.open(UUID.randomUUID(), 42, 1, sealed))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> cipher.open(account, 43, 1, sealed)).isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> cipher.open(account, 42, 2, sealed)).isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> new GitHubAppGrantCipher(repositoryCipher, "Iv.other").open(account, 42, 1, sealed))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() ->
                        repositoryCipher.decrypt(new WorkspaceId(account), "https://github.com/octocat/notes", sealed))
                .isInstanceOf(ContentRepositoryException.class);
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.open(account, 42, 1, sealed)).isInstanceOf(ContentRepositoryException.class);
    }

    @Test
    void readsAnEnvelopeProducedByTheOriginalRepositoryFormat() throws Exception {
        String encoded = key();
        var cipher = new RepositoryCredentialCipher(encoded);
        WorkspaceId workspace = WorkspaceId.random();
        String remote = "https://github.com/octocat/notes";
        byte[] username = "owner".getBytes(StandardCharsets.UTF_8);
        byte[] password = "fixture-token".getBytes(StandardCharsets.UTF_8);
        byte[] plain = ByteBuffer.allocate(4 + username.length + password.length)
                .putInt(username.length)
                .put(username)
                .put(password)
                .array();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher original = Cipher.getInstance("AES/GCM/NoPadding");
        original.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES"),
                new GCMParameterSpec(128, nonce));
        original.updateAAD(("poketto-repository-v1\n" + workspace + "\n" + remote).getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = original.doFinal(plain);
        byte[] envelope = ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                .put((byte) 1)
                .put(nonce)
                .put(ciphertext)
                .array();
        assertThat(cipher.decrypt(workspace, remote, envelope))
                .isEqualTo(new RepositoryCredentialCipher.Credentials("owner", "fixture-token"));
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
