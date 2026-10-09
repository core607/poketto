package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import java.util.List;

final class QaModels {
    private final List<QaProvider> providers;
    private final String defaultProvider;

    QaModels(QaProvider anthropic, QaProvider deepseek, String defaultProvider) {
        providers = List.of(anthropic, deepseek);
        this.defaultProvider = defaultProvider;
        find(defaultProvider);
    }

    QaProvider find(String id) {
        String selected = id == null ? defaultProvider : id;
        return providers.stream()
                .filter(value -> value.id().equals(selected))
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
        return providers.stream().map(value -> value.option(policy)).toList();
    }
}
