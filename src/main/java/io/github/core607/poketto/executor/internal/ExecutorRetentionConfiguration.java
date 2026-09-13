package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"poketto.executor.enabled", "poketto.executor.retention.enabled"},
        havingValue = "true")
class ExecutorRetentionConfiguration {
    @Bean
    RetainedCopyMaintenance retainedCopyMaintenance(RetainedCopyStore store) {
        return new RetainedCopyMaintenance(store, Duration.ofMinutes(1));
    }

    @Bean
    RetainedCopyStore retainedCopyStore(
            @Value("${poketto.executor.retention.root}") Path root,
            @Value("${poketto.executor.retention.max-copies:32}") int copies,
            @Value("${poketto.executor.retention.max-record-bytes:100663296}") long recordBytes,
            @Value("${poketto.executor.retention.max-total-bytes:536870912}") long totalBytes,
            @Value("${poketto.executor.retention.disk-reserve-bytes:1073741824}") long reserve,
            @Value("${poketto.executor.retention.seconds:86400}") long seconds) {
        return new RetainedCopyStore(
                root,
                new RetainedCopyStore.Limits(copies, recordBytes, totalBytes, reserve, Duration.ofSeconds(seconds)),
                Clock.systemUTC());
    }
}
