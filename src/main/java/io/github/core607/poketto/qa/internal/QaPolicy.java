package io.github.core607.poketto.qa.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

record QaPolicy(
        int dailyQuestions,
        long dailyMicros,
        int concurrentRuns,
        int rounds,
        int outputTokens,
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        Duration runTime,
        String personality) {
    static final int INPUT_BYTES = 65_536;
    static final int INPUT_TOKEN_BOUND = INPUT_BYTES + 4096;
    static final int TOOL_LIMIT = 16;
    static final Duration CLARIFICATION_TIME = Duration.ofMinutes(10);

    QaPolicy {
        if (dailyQuestions < 1 || dailyQuestions > 1000) {
            throw new IllegalArgumentException("QA daily questions must be 1–1000");
        }
        if (dailyMicros < 1 || dailyMicros > 1_000_000_000L) {
            throw new IllegalArgumentException("QA daily USD budget must be positive and at most 1000");
        }
        if (concurrentRuns < 1 || concurrentRuns > 16) {
            throw new IllegalArgumentException("QA concurrency must be 1–16");
        }
        if (rounds < 1 || rounds > 12) {
            throw new IllegalArgumentException("QA model rounds must be 1–12");
        }
        if (outputTokens < 256 || outputTokens > 4096) {
            throw new IllegalArgumentException("QA output token cap must be 256–4096");
        }
        requirePrice(inputPerMillion);
        requirePrice(outputPerMillion);
        if (runTime.compareTo(Duration.ofSeconds(10)) < 0 || runTime.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("QA active request time must be 10–120 seconds");
        }
        if (personality == null || personality.length() > 2000) {
            throw new IllegalArgumentException("QA personality exceeds 2000 characters");
        }
    }

    long callBound() {
        return cost(INPUT_TOKEN_BOUND, outputTokens);
    }

    long runBound() {
        return Math.multiplyExact(callBound(), rounds);
    }

    long cost(long input, long output) {
        return inputPerMillion
                .multiply(BigDecimal.valueOf(input))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(output)))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
    }

    static String dollars(long micros) {
        return BigDecimal.valueOf(micros, 6).toPlainString();
    }

    private static void requirePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new IllegalArgumentException(
                    "QA peak uncached prices must be positive and at most 1000 USD per million tokens");
        }
    }
}
