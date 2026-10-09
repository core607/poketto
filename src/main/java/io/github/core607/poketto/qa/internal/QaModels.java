package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import java.util.List;

final class QaModels {
    private final List<QaProvider> providers;
    private final String defaultProvider;
    private final QaPriceBook prices;

    QaModels(QaProvider anthropic, QaProvider deepseek, String defaultProvider, QaPriceBook prices) {
        providers = List.of(anthropic, deepseek);
        this.defaultProvider = defaultProvider;
        this.prices = prices;
        find(defaultProvider);
    }

    QaProvider find(String id) {
        String selected = id == null ? defaultProvider : id;
        return providers.stream()
                .filter(value -> value.id().equals(selected))
                .map(value -> value.withPrices(prices.require(value.id(), value.model())))
                .findFirst()
                .orElseThrow(() -> new QaException("UNKNOWN_MODEL", "Select a configured QA model"));
    }

    QaProvider require(String id) {
        QaProvider selected = find(id);
        if (!selected.configured()) {
            throw new QaException("MODEL_UNAVAILABLE", "The selected QA provider has no configured credential");
        }
        return selected;
    }

    boolean available() {
        return providers.stream().anyMatch(QaProvider::configured);
    }

    String defaultProvider() {
        return defaultProvider;
    }

    List<QaService.ModelOption> options(QaPolicy policy) {
        return providers.stream().map(value -> find(value.id()).option(policy)).toList();
    }

    void refreshPrices() {
        prices.refresh();
    }
}
