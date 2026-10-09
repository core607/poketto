package io.github.core607.poketto.qa;

import java.util.UUID;

/** The caller holds the account lock and transaction. A run reserves once and refunds at most once. */
public interface QaCandy {
    void reserve(UUID account);

    void refund(UUID account);
}
