package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class NativeRepositoryMetricsTests {
    @Test
    void nativeExporterEmitsEveryMetricNamedByMonitoringExamples() throws Exception {
        var meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new NativeRepositoryMetrics().bind(meters, () -> 0, () -> 0);
            List<String> alerted = Pattern.compile("poketto_executor_[a-z0-9_]+")
                    .matcher(Files.readString(Path.of("deploy/observability/alerts.yml"))
                            + Files.readString(Path.of("deploy/observability/README.md")))
                    .results()
                    .map(MatchResult::group)
                    .distinct()
                    .toList();
            assertThat(alerted).isNotEmpty();
            assertThat(meters.scrape()).contains(alerted);
            assertThat(meters.getMeters())
                    .allSatisfy(meter -> assertThat(meter.getId().getTags())
                            .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("reason")));
        } finally {
            meters.close();
        }
    }
}
