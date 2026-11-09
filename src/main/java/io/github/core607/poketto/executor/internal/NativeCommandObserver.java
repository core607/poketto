package io.github.core607.poketto.executor.internal;

import java.time.Duration;
import java.util.function.Function;

@FunctionalInterface
interface NativeCommandObserver {
    void observe(SandcrabProtocol.Reference run, Duration timeout, Function<byte[], byte[]> handler);
}
