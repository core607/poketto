package io.github.core607.poketto.auth;

import java.util.UUID;

/** Provider acceptance only; the same message ID must be used for every transport retry. */
public interface VerificationMail {
    boolean available();

    void send(String email, String code, EmailPurpose purpose, UUID messageId);
}
