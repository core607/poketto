package io.github.core607.poketto.qa.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** USD per million tokens equals micro-USD per token. Round each settled call upward. */
record QaPrices(BigDecimal input, BigDecimal output) {
    QaPrices {
        requirePrice(input);
        requirePrice(output);
    }

    long cost(long inputTokens, long outputTokens) {
        return input.multiply(BigDecimal.valueOf(inputTokens))
                .add(output.multiply(BigDecimal.valueOf(outputTokens)))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
    }

    private static void requirePrice(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.compareTo(BigDecimal.valueOf(1000)) > 0
                || value.stripTrailingZeros().scale() > 8) {
            throw new IllegalArgumentException(
                    "QA prices must be positive, at most 1000 USD per million tokens and have at most eight decimal places");
        }
    }
}
