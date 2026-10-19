package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class WorkerSignaturesTests {
    @Test
    void matchesTheRfc8032EmptyMessageVectorWithoutRegisteringAProvider() throws Exception {
        var providers = Security.getProviders();
        byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        PrivateKey original = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        PrivateKey key = WorkerSignatures.privateKey(original.getEncoded());

        // RFC 8032, section 7.1, test 1.
        byte[] expected = HexFormat.of()
                .parseHex("e5564300c360ac729086e2cc806e828a"
                        + "84877f1eb8e5d974d873e06522490155"
                        + "5fb8821590a33bacc61e39701cf9b46b"
                        + "d25bf5f0595bbe24655141438e7a100b");
        assertThat(WorkerSignatures.sign(key, new byte[0])).isEqualTo(expected);
        assertThat(Security.getProviders()).containsExactly(providers);
    }

    @Test
    void concurrentRequestsWithOneKeyMatchJdkSignatures() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PrivateKey key = WorkerSignatures.privateKey(pair.getPrivate().getEncoded());
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<?>>();
            for (int request = 0; request < 128; request++) {
                int id = request;
                results.add(executor.submit(() -> {
                    byte[] payload = new byte[600 + id];
                    new Random(id).nextBytes(payload);
                    byte[] actual = WorkerSignatures.sign(key, payload);
                    Signature reference = Signature.getInstance("Ed25519");
                    reference.initSign(pair.getPrivate());
                    reference.update(payload);
                    assertThat(actual).isEqualTo(reference.sign());
                    assertThat(verifies(pair.getPublic(), payload, actual)).isTrue();
                    return null;
                }));
            }
            for (Future<?> result : results) {
                result.get();
            }
        }
    }

    @Test
    void aSignatureDoesNotAuthorizeChangedBytesOrAnotherKey() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PrivateKey key = WorkerSignatures.privateKey(pair.getPrivate().getEncoded());
        byte[] payload = {1, 2, 3};
        byte[] signature = WorkerSignatures.sign(key, payload);
        assertThat(verifies(pair.getPublic(), payload, signature)).isTrue();
        assertThat(verifies(pair.getPublic(), new byte[] {1, 2, 4}, signature)).isFalse();
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThat(verifies(other.getPublic(), payload, signature)).isFalse();
    }

    @Test
    void rejectsAnotherCurveInsteadOfSilentlyChangingTheWireAlgorithm() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        assertThatThrownBy(() -> WorkerSignatures.privateKey(pair.getPrivate().getEncoded()))
                .isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> WorkerSignatures.sign(pair.getPrivate(), new byte[] {1}))
                .isInstanceOf(GeneralSecurityException.class);
    }

    private static boolean verifies(PublicKey key, byte[] payload, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(payload);
        return verifier.verify(signature);
    }
}
