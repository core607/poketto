package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaService;

record QaProvider(String id, String model, QaPrices prices, QaModel client, boolean configured) {
    QaProvider {
        if (!id.equals("anthropic") && !id.equals("deepseek")) {
            throw new IllegalArgumentException("Unknown QA provider");
        }
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid configured QA model ID");
        }
    }

    long callBound(QaPolicy policy) {
        // Reserve the upper cache-write price before the response reveals any cache hits.
        int inputMultiplier = id.equals("anthropic") ? 2 : 1;
        return prices.cost((long) QaPolicy.INPUT_TOKEN_BOUND * inputMultiplier, policy.outputTokens());
    }

    long runBound(QaPolicy policy) {
        return Math.multiplyExact(callBound(policy), policy.rounds());
    }

    QaService.ModelOption option(QaPolicy policy) {
        return new QaService.ModelOption(id, model, configured, QaPolicy.dollars(runBound(policy)));
    }
}
