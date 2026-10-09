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
import org.springframework.beans.factory.annotation.Qualifier;
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
            @Value("${poketto.qa.max-output-tokens:8192}") int output,
            @Value("${poketto.qa.anthropic.monthly-usd:20}") BigDecimal monthly,
            @Value("${poketto.qa.timeout-seconds:90}") int seconds,
            @Value("${poketto.qa.personality:}") String personality) {
        return new QaPolicy(
                daily,
                micros(dollars),
                concurrency,
                rounds,
                output,
                micros(monthly),
                Duration.ofSeconds(seconds),
                personality);
    }

    @Bean
    QaProvider deepseekQaProvider(
            HttpClient qaHttpClient,
            QaPolicy policy,
            ObjectMapper json,
            @Value("${poketto.qa.deepseek.api-key:${DEEPSEEK_API_KEY:}}") String key,
            @Value("${poketto.qa.deepseek.base-url:${DEEPSEEK_BASE_URL:https://api.deepseek.com}}") String base,
            @Value("${poketto.qa.deepseek.model:deepseek-flash}") String model,
            @Value("${poketto.qa.deepseek.input-usd-per-million:0.30}") BigDecimal input,
            @Value("${poketto.qa.deepseek.output-usd-per-million:1.20}") BigDecimal output) {
        return new QaProvider(
                "deepseek",
                model,
                new QaPrices(input, output),
                new DeepSeekQaModel(qaHttpClient, endpoint(base, "/chat/completions"), key, model, policy, json),
                !key.isBlank());
    }

    @Bean
    QaProvider anthropicQaProvider(
            HttpClient qaHttpClient,
            QaPolicy policy,
            ObjectMapper json,
            @Value("${poketto.qa.anthropic.api-key:${ANTHROPIC_API_KEY:}}") String key,
            @Value("${poketto.qa.anthropic.base-url:https://api.anthropic.com/v1}") String base,
            @Value("${poketto.qa.anthropic.model:claude-haiku-5-5}") String model,
            @Value("${poketto.qa.anthropic.input-usd-per-million:0.10}") BigDecimal input,
            @Value("${poketto.qa.anthropic.output-usd-per-million:0.50}") BigDecimal output) {
        return new QaProvider(
                "anthropic",
                model,
                new QaPrices(input, output),
                new AnthropicQaModel(qaHttpClient, endpoint(base, "/messages"), key, model, policy, json),
                !key.isBlank());
    }

    @Bean
    QaModels qaModels(
            @Qualifier("anthropicQaProvider") QaProvider anthropic,
            @Qualifier("deepseekQaProvider") QaProvider deepseek,
            @Value("${poketto.qa.default-provider:anthropic}") String selected) {
        return new QaModels(anthropic, deepseek, selected);
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
            QaModels models,
            @Value("${poketto.qa.enabled:true}") boolean enabled) {
        Clock clock = Clock.systemUTC();
        var authority = new QaAuthority(people, machines, jdbc, transactions);
        var ledger = new QaLedger(jdbc, policy, candy, clock, models);
        var service = new DefaultQaService(authority, ledger, models, sources, policy, json, clock, enabled);
        service.start();
        return service;
    }

    private static long micros(BigDecimal dollars) {
        return dollars.movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private static URI endpoint(String base, String path) {
        URI uri = URI.create(base.replaceAll("/+$", "") + path);
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
