package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.executor.enabled", havingValue = "true")
class ExecutorAccountConfiguration {
    @Bean
    AccountCopyStore accountCopyStore(
            @Value("${poketto.executor.copies.metadata-root}") Path root,
            @Value("${poketto.executor.copies.max-copies:128}") int copies,
            @Value("${poketto.executor.copies.max-record-bytes:16777216}") long recordBytes,
            @Value("${poketto.executor.copies.pool-bytes:34359738368}") long poolBytes,
            @Value("${poketto.executor.copies.idle-seconds:604800}") long idleSeconds,
            @Value("${poketto.executor.copies.original-bytes:268435456}") long originalBytes,
            @Value("${poketto.executor.copies.original-expanded-bytes:1073741824}") long expandedBytes,
            @Value("${poketto.executor.copies.original-entries:100000}") int entries) {
        return new AccountCopyStore(
                root,
                new AccountCopyStore.Limits(
                        copies,
                        recordBytes,
                        poolBytes,
                        Duration.ofSeconds(idleSeconds),
                        new RetainedBaseline.Limits(originalBytes, expandedBytes, entries)),
                Clock.systemUTC());
    }
}
