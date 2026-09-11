package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RepositoryCredentialCipherTests {
    @Test
    void authenticatedCiphertextCannotBeTransplantedBetweenWorkspacesOrRepositories() {
        var cipher = cipher();
        WorkspaceId workspace = WorkspaceId.random();
        String remote = "https://cnb.cool/example/notes";
        var secret = new RepositoryCredentialCipher.Credentials("operator", "private-provider-token");
        byte[] envelope = cipher.encrypt(workspace, remote, secret);
        assertThat(cipher.decrypt(workspace, remote, envelope)).isEqualTo(secret);
        assertThat(cipher.encrypt(workspace, remote, secret)).isNotEqualTo(envelope);
        assertThatThrownBy(() -> cipher.decrypt(WorkspaceId.random(), remote, envelope))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> cipher.decrypt(workspace, "https://cnb.cool/example/another", envelope))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> cipher().decrypt(workspace, remote, envelope))
                .isInstanceOf(ContentRepositoryException.class);
        envelope[envelope.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(workspace, remote, envelope))
                .isInstanceOf(ContentRepositoryException.class)
                .hasMessageNotContaining(secret.password());
        assertThat(secret.toString()).doesNotContain(secret.username(), secret.password());
    }

    @Test
    void missingOrMalformedKeysNeverFallBackToPlaintext() {
        var missing = new RepositoryCredentialCipher("");
        assertThat(missing.available()).isFalse();
        assertThatThrownBy(() -> missing.encrypt(
                        WorkspaceId.random(),
                        "https://cnb.cool/example/notes",
                        new RepositoryCredentialCipher.Credentials("operator", "private-provider-token")))
                .isInstanceOf(ContentRepositoryException.class);
        assertThatThrownBy(() -> new RepositoryCredentialCipher("invalid-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("invalid-key");
        assertThatThrownBy(
                        () -> new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RepositoryCredentialCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new RepositoryCredentialCipher(Base64.getEncoder().encodeToString(key));
    }
}
