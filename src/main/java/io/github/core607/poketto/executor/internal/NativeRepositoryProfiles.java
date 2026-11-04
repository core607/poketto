package io.github.core607.poketto.executor.internal;

/** User commands and the fixed Git checkout used to prepare a template can have different disk budgets. */
record NativeRepositoryProfiles(String execution, String preparation) {
    NativeRepositoryProfiles {
        execution = SandcrabProtocol.profile(execution);
        preparation = SandcrabProtocol.profile(preparation);
    }

    SandcrabProtocol.Profile offlinePreparation(SandcrabProtocol.Info info) {
        SandcrabProtocol.Profile settings = info.profiles().get(preparation);
        if (settings == null || !settings.networkDisabled()) {
            throw new WorkerUnavailableException(
                    new IllegalStateException("Template preparation requires an advertised offline profile"), false);
        }
        return settings;
    }
}
