package io.github.core607.poketto.executor.internal;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Ed25519 for the worker protocol, with no change to the JVM's registered providers. */
final class WorkerSignatures {
    private static final Provider PROVIDER = new BouncyCastleProvider();

    private WorkerSignatures() {}

    static PrivateKey privateKey(byte[] encoded) throws GeneralSecurityException {
        // Keeping the provider's key lets it reuse the derived public key across request signatures.
        return KeyFactory.getInstance("Ed25519", PROVIDER).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    static byte[] sign(PrivateKey key, byte[] payload) throws GeneralSecurityException {
        // Signature contains mutable message state: each concurrent request gets its own instance.
        Signature signature = Signature.getInstance("Ed25519", PROVIDER);
        signature.initSign(key);
        signature.update(payload);
        return signature.sign();
    }
}
