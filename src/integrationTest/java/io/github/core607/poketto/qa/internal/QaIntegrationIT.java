package io.github.core607.poketto.qa.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.Accounts;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.qa.QaCandy;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import io.github.core607.poketto.qa.QaSources;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Real eligibility, transactions and budget persistence; model completions are fixtures and spend no money. */
@Testcontainers
class QaIntegrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("poketto.postgres.image")).asCompatibleSubstituteFor("postgres"));

    private final MutableClock clock = new MutableClock();
    private final QaModel model = mock(QaModel.class);
    private final QaModel claude = mock(QaModel.class);
    private QaModels models;
    private QaProvider deepseek;
    private QaProvider anthropic;
    private final QaPrices prices = new QaPrices(new BigDecimal("0.30"), new BigDecimal("1.20"));
    private final QaSources sources = mock(QaSources.class);
    private JdbcTemplate jdbc;
    private AuthService auth;
    private MachineAccounts machines;
    private AuthPrincipal owner;
    private AuthPrincipal key;
    private WorkspaceId workspace;
    private QaAuthority authority;
    private QaLedger ledger;
    private QaPolicy policy;
    private DefaultQaService qa;

    @BeforeEach
    void setup() {
        var data = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(data).load().migrate();
        jdbc = new JdbcTemplate(data);
        jdbc.execute(
                "truncate qa_runs,qa_budget_days,qa_anthropic_months,plaza_wallets,workspaces,auth_accounts cascade");
        jdbc.update("update auth_initialization set initialized_at=null");
        workspace = WorkspaceId.random();
        jdbc.update(
                "insert into workspaces(workspace_id,display_name,is_default,public_slug,public_delivery) values (?,'QA fixture',true,'qa-fixture',true)",
                workspace.value());
        var transactions = new DataSourceTransactionManager(data);
        var passwords = new DelegatingPasswordEncoder(
                "pbkdf2", Map.of("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        auth = new AuthService(jdbc, transactions, passwords, event -> {}, clock);
        owner = auth.initializeOwner("qa-owner", "QA-fixture-password-2026!");
        var accounts = new Accounts(jdbc, transactions);
        machines = new MachineAccounts(jdbc, accounts, auth, transactions);
        key = auth.authenticateApiKey(
                auth.createApiKey(owner, workspace, owner.accountId(), Set.of()).token());
        machines.set(owner, key.subjectId(), Set.of(MachinePermission.WISH));
        jdbc.update(
                "insert into plaza_wallets(account_id,balance,claimed_day) values (?,5,'2026-10-09')",
                owner.accountId());
        authority = new QaAuthority(new CommunityAccounts(jdbc, accounts), machines, jdbc, transactions);
        policy = new QaPolicy(5, 2_000_000, 1, 6, 2048, 20_000_000, Duration.ofSeconds(90), "");
        deepseek = new QaProvider("deepseek", "deepseek-flash", prices, model, true);
        anthropic = new QaProvider(
                "anthropic",
                "claude-haiku-5-5",
                new QaPrices(new BigDecimal("0.10"), new BigDecimal("0.50")),
                claude,
                true);
        models = new QaModels(anthropic, deepseek, "anthropic");
        ledger = new QaLedger(jdbc, policy, candy(), clock, models);
        qa = service();
        when(model.complete(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return noAnswer();
        });
    }

    @AfterEach
    void close() {
        qa.close();
    }

    @Test
    void dailyAllowanceAndDuplicateIdsDoNotTriggerAnotherPaidCallOrStoreQuestionText() {
        QaService.Question first = question();
        assertThat(qa.ask(owner, null, first).status()).isEqualTo("COMPLETED");
        assertThat(qa.ask(owner, null, first).paragraphs()).isEmpty();
        for (int index = 0; index < 4; index++) {
            qa.ask(owner, null, question());
        }
        assertThatThrownBy(() -> qa.ask(owner, null, question()))
                .isInstanceOf(QaException.class)
                .hasMessageContaining("allowance");
        assertThat(qa.allowance(owner).remaining()).isZero();
        verify(model, times(5)).complete(any(), any());
        assertThat(number("select sum(cost_micros) from qa_runs")).isEqualTo(5 * prices.cost(100, 20));
        assertThat(number("select sum(reserved_micros) from qa_budget_days")).isZero();
        assertThat(jdbc.queryForList("select row_to_json(qa_runs)::text from qa_runs", String.class)
                        .toString())
                .doesNotContain("private-question-marker");
    }

    @Test
    void ordinaryAccountsAndKeysWithoutPersonalConsentCannotStartQuestions() {
        AuthPrincipal ordinary = AccountFixtures.create(auth, "qa-reader", "QA-fixture-password-2026!");
        assertThatThrownBy(() -> qa.ask(ordinary, null, question())).isInstanceOf(AuthException.class);
        machines.set(owner, key.subjectId(), Set.of());
        assertThatThrownBy(() -> qa.ask(key, workspace, question()))
                .isInstanceOf(QaException.class)
                .hasMessageContaining("authorize wishes");
        assertThatThrownBy(() -> qa.ask(key, null, question())).isInstanceOf(AuthException.class);
        verifyNoInteractions(model);
        assertThat(number("select count(*) from qa_runs")).isZero();
    }

    @Test
    void failureBeforeDispatchRefundsCandyAndDoesNotConsumeACall() {
        doThrow(new QaException("INPUT_LIMIT", "Too much text")).when(model).validate(any());
        QaService.Reply reply = qa.ask(key, workspace, question());
        assertThat(reply.status()).isEqualTo("FAILED");
        assertThat(reply.usage().calls()).isZero();
        assertThat(balance()).isEqualTo(5);
        assertThat(number("select sum(spent_micros+reserved_micros) from qa_budget_days"))
                .isZero();
        verify(model, times(0)).complete(any(), any());
    }

    @Test
    void uncertainPaidFailureRefundsCandyButRetainsTheCallBoundAndCannotReplay() {
        doThrow(new QaException("UPSTREAM_UNCERTAIN", "Connection lost"))
                .when(model)
                .complete(any(), any());
        QaService.Question input = question();
        QaService.Reply reply = qa.ask(key, workspace, input);
        assertThat(reply.code()).isEqualTo("UPSTREAM_UNCERTAIN");
        assertThat(reply.usage().uncertain()).isTrue();
        assertThat(reply.usage().costUpperUsd()).isEqualTo(QaPolicy.dollars(deepseek.callBound(policy)));
        assertThat(balance()).isEqualTo(5);
        qa.ask(key, workspace, input);
        qa.expire();
        assertThat(balance()).isEqualTo(5);
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void clarificationReleasesConcurrencyAndContinuesOnceWithTheSameWishAndRunBudget() {
        var calls = new AtomicInteger();
        doAnswer(call -> calls.getAndIncrement() == 0 ? clarification() : noAnswer())
                .when(model)
                .complete(any(), any());
        QaService.Question input = question();
        QaService.Reply waiting = qa.ask(key, workspace, input);
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(balance()).isEqualTo(4);
        assertThat(qa.ask(key, workspace, input).clarification().options())
                .containsExactly("One article", "Several articles");
        qa.status(key, workspace, input.requestId());
        assertThat(calls).hasValue(1);
        assertThat(qa.ask(owner, null, question()).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() ->
                        qa.resume(key, workspace, new QaService.Choice(input.requestId(), 99, "Several articles")))
                .isInstanceOf(QaException.class);
        assertThat(calls).hasValue(2);
        QaService.Reply result = qa.resume(
                key, workspace, new QaService.Choice(input.requestId(), waiting.revision(), "Several articles"));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.usage().calls()).isEqualTo(2);
        assertThat(balance()).isEqualTo(4);
        assertThat(qa.resume(
                                key,
                                workspace,
                                new QaService.Choice(input.requestId(), waiting.revision(), "Several articles"))
                        .status())
                .isEqualTo("COMPLETED");
        assertThat(calls).hasValue(3);
    }

    @Test
    void revokingConsentDuringAnUpstreamCallPreventsDeliveryButStillSettlesUsageAndRefunds() {
        doAnswer(call -> {
                    machines.set(owner, key.subjectId(), Set.of());
                    return noAnswer();
                })
                .when(model)
                .complete(any(), any());
        QaService.Reply refused = qa.ask(key, workspace, question());
        assertThat(refused.code()).isEqualTo("OWNER_CONSENT_REQUIRED");
        assertThat(refused.paragraphs()).isEmpty();
        assertThat(refused.activity()).isEmpty();
        assertThat(balance()).isEqualTo(5);
        assertThat(number("select sum(cost_micros) from qa_runs")).isEqualTo(prices.cost(100, 20));
    }

    @Test
    void aCreatorLosingEligibilityCannotContinueEvenWithCandyRemaining() {
        when(model.complete(any(), any())).thenReturn(clarification());
        QaService.Question input = question();
        qa.ask(key, workspace, input);
        jdbc.update("update auth_accounts set site_group='VIEWER' where account_id=?", owner.accountId());
        assertThatThrownBy(() -> qa.resume(key, workspace, new QaService.Choice(input.requestId(), 1, "One article")))
                .isInstanceOf(AuthException.class);
        clock.now = clock.now.plusSeconds(601);
        qa.expire();
        assertThat(balance()).isEqualTo(5);
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void restartLosesOnlyTransientClarificationAndNeverReplaysOrDoubleRefunds() {
        when(model.complete(any(), any())).thenReturn(clarification());
        QaService.Question input = question();
        qa.ask(key, workspace, input);
        qa.close();
        qa = service();
        assertThat(qa.status(key, workspace, input.requestId()).code()).isEqualTo("CONTINUATION_LOST");
        assertThat(qa.ask(key, workspace, input).code()).isEqualTo("CONTINUATION_LOST");
        assertThat(balance()).isEqualTo(5);
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void inFlightDeduplicationAndGlobalCapacityHoldWithoutKeepingAnAccountTransactionOpen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    entered.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return noAnswer();
                })
                .when(model)
                .complete(any(), any());
        QaService.Question input = question();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = threads.submit(() -> qa.ask(key, workspace, input));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(qa.ask(key, workspace, input).status()).isEqualTo("RUNNING");
                assertThatThrownBy(() -> qa.ask(owner, null, question()))
                        .isInstanceOf(QaException.class)
                        .hasMessageContaining("concurrency");
                assertThat(balance()).isEqualTo(4);
            } finally {
                release.countDown();
            }
            assertThat(running.get(10, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
        }
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void expiryBooksAnUnknownCallBeforeLateCompletionAndPreventsItsAnswerFromEscaping() {
        doAnswer(call -> {
                    clock.now = clock.now.plusSeconds(91);
                    qa.expire();
                    return noAnswer();
                })
                .when(model)
                .complete(any(), any());
        QaService.Reply result = qa.ask(key, workspace, question());
        assertThat(result.code()).isEqualTo("QA_EXPIRED");
        assertThat(result.usage().uncertain()).isTrue();
        assertThat(result.usage().costUpperUsd()).isEqualTo(QaPolicy.dollars(deepseek.callBound(policy)));
        assertThat(balance()).isEqualTo(5);
        assertThat(number("select sum(reserved_micros) from qa_budget_days")).isZero();
    }

    @Test
    void theWholeRunReservationMustFitBeforeCandyOrModelUse() {
        jdbc.update("insert into qa_budget_days(day,spent_micros) values ('2026-10-09',?)", policy.dailyMicros());
        assertThatThrownBy(() -> qa.ask(key, workspace, question()))
                .isInstanceOf(QaException.class)
                .hasMessageContaining("budget");
        assertThat(balance()).isEqualTo(5);
        verifyNoInteractions(model);
    }

    @Test
    void midnightDoesNotReleaseConcurrencyWhileAnEarlierPaidCallIsStillInFlight() {
        clock.now = Instant.parse("2026-10-09T23:59:59Z");
        doAnswer(call -> {
                    clock.now = clock.now.plusSeconds(2);
                    qa.expire();
                    assertThatThrownBy(() -> qa.ask(owner, null, question()))
                            .isInstanceOf(QaException.class)
                            .hasMessageContaining("concurrency");
                    return noAnswer();
                })
                .when(model)
                .complete(any(), any());
        QaService.Reply reply = qa.ask(key, workspace, question());
        assertThat(reply.code()).isEqualTo("QA_EXPIRED");
        assertThat(reply.usage().uncertain()).isFalse();
        assertThat(reply.usage().costUpperUsd()).isEqualTo(QaPolicy.dollars(prices.cost(100, 20)));
        assertThat(balance()).isEqualTo(5);
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void claudeMonthlyExhaustionFallsBackBeforeDispatchAndPinsTheSelectionOnReplay() {
        jdbc.update("insert into qa_anthropic_months(month,spent_micros) values ('2026-10-01',20000000)");
        QaService.Question input = new QaService.Question(UUID.randomUUID(), "Find public papers", null);
        QaService.Reply reply = qa.ask(owner, null, input);
        assertThat(reply.selection().requestedProvider()).isEqualTo("anthropic");
        assertThat(reply.selection().provider()).isEqualTo("deepseek");
        assertThat(reply.selection().fallbackReason()).isEqualTo("ANTHROPIC_MONTHLY_BUDGET");
        jdbc.update("update qa_anthropic_months set spent_micros=0");
        assertThat(qa.ask(owner, null, input).selection()).isEqualTo(reply.selection());
        verifyNoInteractions(claude);
        verify(model, times(1)).complete(any(), any());
    }

    @Test
    void uncertainClaudeCallNeverFallsBackAndKeepsItsMonthlyChargeAfterCandyRefund() {
        doThrow(new QaException("UPSTREAM_UNCERTAIN", "Connection lost"))
                .when(claude)
                .complete(any(), any());
        QaService.Question input = new QaService.Question(UUID.randomUUID(), "Find public papers", "anthropic");
        QaService.Reply reply = qa.ask(key, workspace, input);
        assertThat(reply.usage().uncertain()).isTrue();
        assertThat(reply.selection().provider()).isEqualTo("anthropic");
        assertThat(number("select spent_micros from qa_anthropic_months")).isEqualTo(anthropic.callBound(policy));
        assertThat(number("select reserved_micros from qa_anthropic_months")).isZero();
        assertThat(balance()).isEqualTo(5);
        qa.ask(key, workspace, input);
        verify(claude, times(1)).complete(any(), any());
        verifyNoInteractions(model);
    }

    @Test
    void refusalSettlesReportedUsageRefundsCandyAndNeverExecutesToolsOrFallsBack() {
        when(claude.complete(any(), any()))
                .thenReturn(new QaModel.Completion(
                        List.of(new QaModel.Call("ignored", "function", new QaModel.Function("search", "{}"))),
                        100,
                        20,
                        0,
                        "Refused",
                        "",
                        null,
                        true));
        var input = new QaService.Question(UUID.randomUUID(), "Find public papers", "anthropic");
        QaService.Reply reply = qa.ask(key, workspace, input);
        assertThat(reply.status()).isEqualTo("FAILED");
        assertThat(reply.code()).isEqualTo("MODEL_REFUSED");
        assertThat(reply.paragraphs()).isEmpty();
        assertThat(reply.usage().calls()).isEqualTo(1);
        assertThat(reply.usage().uncertain()).isFalse();
        assertThat(number("select spent_micros from qa_anthropic_months")).isEqualTo(20);
        assertThat(number("select reserved_micros from qa_anthropic_months")).isZero();
        assertThat(balance()).isEqualTo(5);
        assertThat(qa.ask(key, workspace, input).code()).isEqualTo("MODEL_REFUSED");
        verify(claude, times(1)).complete(any(), any());
        verifyNoInteractions(model, sources);
    }

    @Test
    void monthlyReservationsShareTheDailyTransactionAndDoNotLeakWhenDailyAdmissionFails() {
        jdbc.update("insert into qa_budget_days(day,spent_micros) values ('2026-10-09',2000000)");
        assertThatThrownBy(() -> qa.ask(
                        owner, null, new QaService.Question(UUID.randomUUID(), "Find public papers", "anthropic")))
                .isInstanceOf(QaException.class);
        assertThat(number("select count(*) from qa_anthropic_months")).isZero();
        verifyNoInteractions(model, claude);
    }

    @Test
    void concurrentAccountsCannotReserveTheSameClaudeRemainder() throws Exception {
        var concurrentPolicy = new QaPolicy(5, 2_000_000, 2, 6, 2048, 20_000_000, Duration.ofSeconds(90), "");
        var concurrentLedger = new QaLedger(jdbc, concurrentPolicy, candy(), clock, models);
        AuthPrincipal other = AccountFixtures.create(auth, "qa-second", "QA-fixture-password-2026!");
        jdbc.update(
                "insert into qa_anthropic_months(month,spent_micros) values ('2026-10-01',?)",
                20_000_000 - anthropic.runBound(concurrentPolicy));
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> authority.record(
                    owner.accountId(),
                    () -> concurrentLedger.begin(owner.accountId(), UUID.randomUUID(), null, "anthropic")));
            var second = threads.submit(() -> authority.record(
                    other.accountId(),
                    () -> concurrentLedger.begin(other.accountId(), UUID.randomUUID(), null, "anthropic")));
            assertThat(List.of(
                            first.get().run().selection().provider(),
                            second.get().run().selection().provider()))
                    .containsExactlyInAnyOrder("anthropic", "deepseek");
        }
        assertThat(number("select reserved_micros+spent_micros from qa_anthropic_months"))
                .isEqualTo(20_000_000);
        verifyNoInteractions(model, claude);
    }

    @Test
    void lateResponsesBillTheOriginalMonthAndPriceSnapshotEvenAfterConfigurationChanges() {
        clock.now = Instant.parse("2026-10-31T23:59:59Z");
        QaLedger.Run run = authority.record(
                owner.accountId(),
                () -> ledger.dispatch(ledger.begin(owner.accountId(), UUID.randomUUID(), null, "anthropic")
                        .run()));
        clock.now = Instant.parse("2026-11-01T00:00:01Z");
        var changed = new QaProvider(
                "anthropic", "different-model", new QaPrices(new BigDecimal("99"), new BigDecimal("99")), claude, true);
        var restarted = new QaLedger(jdbc, policy, candy(), clock, new QaModels(changed, deepseek, "anthropic"));
        QaLedger.Run settled = authority.record(owner.accountId(), () -> restarted.settle(run, noAnswer()));
        authority.record(owner.accountId(), () -> restarted.expire(settled));
        assertThat(number("select spent_micros from qa_anthropic_months where month='2026-10-01'"))
                .isEqualTo(anthropic.prices().cost(100, 20));
        assertThat(number("select reserved_micros from qa_anthropic_months")).isZero();
        assertThat(qa.allowance(owner).anthropicBudget().remainingUsd()).isEqualTo("20.000000");
    }

    @Test
    void activityIncludesPublicReasoningAndToolResultsButIsGoneAfterCompletion() {
        QaService.Question input = question();
        QaService.Reply result = qa.ask(owner, null, input);
        assertThat(result.activity()).hasSize(2);
        assertThat(result.activity().getFirst().output()).isEqualTo("Public provider reasoning.");
        assertThat(result.activity().getLast().name()).isEqualTo("answer");
        assertThat(qa.status(owner, null, input.requestId()).activity()).isEmpty();
        assertThat(jdbc.queryForList("select row_to_json(qa_runs)::text from qa_runs", String.class)
                        .toString())
                .doesNotContain("Public provider reasoning.");
    }

    private DefaultQaService service() {
        return new DefaultQaService(authority, ledger, models, sources, policy, JsonMapper.shared(), clock, true);
    }

    private static QaService.Question question() {
        return new QaService.Question(
                UUID.randomUUID(), "private-question-marker: what do the public papers say?", "deepseek");
    }

    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long balance() {
        return number("select balance from plaza_wallets");
    }

    private static QaModel.Completion noAnswer() {
        return completion("answer", "{\"status\":\"insufficient_evidence\",\"paragraphs\":[]}");
    }

    private static QaModel.Completion clarification() {
        return completion(
                "clarify", "{\"question\":\"Which scope?\",\"options\":[\"One article\",\"Several articles\"]}");
    }

    private static QaModel.Completion completion(String name, String arguments) {
        return new QaModel.Completion(
                List.of(new QaModel.Call("call_1", "function", new QaModel.Function(name, arguments))),
                100,
                20,
                0,
                "",
                "Public provider reasoning.",
                null,
                false);
    }

    private QaCandy candy() {
        return new QaCandy() {
            @Override
            public void reserve(UUID account) {
                if (jdbc.update("update plaza_wallets set balance=balance-1 where account_id=? and balance>0", account)
                        == 0) {
                    throw new QaException("NO_CANDY", "No candy");
                }
            }

            @Override
            public void refund(UUID account) {
                jdbc.update("update plaza_wallets set balance=balance+1 where account_id=?", account);
            }
        };
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-09T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
