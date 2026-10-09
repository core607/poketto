package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.qa.QaCandy;
import io.github.core607.poketto.qa.QaService;
import io.github.core607.poketto.qa.QaSources;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.workspace.catalog.enabled", havingValue = "true", matchIfMissing = true)
class QaConfiguration {
    @Bean
    QaPolicy qaPolicy(
            @Value("${poketto.qa.daily-questions:5}") int daily,
            @Value("${poketto.qa.daily-usd:2}") BigDecimal dollars,
            @Value("${poketto.qa.max-concurrency:2}") int concurrency,
            @Value("${poketto.qa.max-rounds:6}") int rounds,
            @Value("${poketto.qa.max-output-tokens:2048}") int output,
            @Value("${poketto.qa.input-usd-per-million:0.30}") BigDecimal inputPrice,
            @Value("${poketto.qa.output-usd-per-million:1.20}") BigDecimal outputPrice,
            @Value("${poketto.qa.timeout-seconds:90}") int seconds,
            @Value("${poketto.qa.personality:}") String personality) {
        return new QaPolicy(
                daily,
                dollars.movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact(),
                concurrency,
                rounds,
                output,
                inputPrice,
                outputPrice,
                Duration.ofSeconds(seconds),
                personality);
    }

    @Bean(destroyMethod = "shutdownNow")
    HttpClient qaHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    QaService qaService(
            CommunityAccounts people,
            MachineAccounts machines,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            QaPolicy policy,
            QaSources sources,
            QaCandy candy,
            ObjectMapper json,
            HttpClient qaHttpClient,
            @Value("${poketto.qa.enabled:true}") boolean enabled,
            @Value("${poketto.qa.api-key:${DEEPSEEK_API_KEY:}}") String key,
            @Value("${poketto.qa.base-url:${DEEPSEEK_BASE_URL:https://api.deepseek.com}}") String base,
            @Value("${poketto.qa.model:deepseek-flash}") String model) {
        Clock clock = Clock.systemUTC();
        var authority = new QaAuthority(people, machines, jdbc, transactions);
        var ledger = new QaLedger(jdbc, policy, candy, clock);
        var provider = new DeepSeekQaModel(qaHttpClient, endpoint(base), key, model, policy, json);
        var service = new DefaultQaService(
                authority, ledger, provider, sources, policy, json, clock, enabled && !key.isBlank());
        service.start();
        return service;
    }

    private static URI endpoint(String base) {
        URI uri = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "QA provider base URL must be HTTPS without credentials, query or fragment");
        }
        return uri;
    }
}
