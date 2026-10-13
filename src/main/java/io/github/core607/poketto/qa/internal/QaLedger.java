package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaCandy;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Methods run under QaAuthority's account-first transaction and global QA ledger guard. No text is persisted. */
final class QaLedger {
    private final JdbcTemplate jdbc;
    private final QaPolicy policy;
    private final QaCandy candy;
    private final Clock clock;
    private final QaModels models;
    private final QaMonth month;

    QaLedger(JdbcTemplate jdbc, QaPolicy policy, QaCandy candy, Clock clock, QaModels models) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.candy = candy;
        this.clock = clock;
        this.models = models;
        this.month = new QaMonth(jdbc, policy.anthropicMonthlyMicros(), clock);
    }

    Start begin(UUID account, UUID request, UUID credential, String requestedProvider) {
        Run previous = find(account, request);
        if (previous != null) {
            entrance(previous, credential);
            return new Start(expire(previous), false);
        }
        LocalDate day = today();
        if (credential == null && used(account, day) >= policy.dailyQuestions()) {
            throw new QaException("DAILY_LIMIT", "Today's account question allowance is used");
        }
        capacity();
        long retained =
                jdbc.queryForObject("select count(*) from qa_runs where status in ('RUNNING','WAITING')", Long.class);
        if (retained >= 64) {
            throw new QaException("QA_CAPACITY", "The question continuation capacity is full");
        }
        Admission admission = select(requestedProvider, day);
        QaProvider provider = admission.provider();
        jdbc.update("insert into qa_budget_days(day) values (?) on conflict do nothing", Date.valueOf(day));
        int reserved = jdbc.update(
                "update qa_budget_days set reserved_micros=reserved_micros+? where day=? and spent_micros+reserved_micros+?<=?",
                provider.runBound(policy),
                Date.valueOf(day),
                provider.runBound(policy),
                policy.dailyMicros());
        if (reserved == 0) {
            throw new QaException("BUDGET_LIMIT", "The shared daily model budget is reserved or spent");
        }
        if (credential != null) {
            candy.reserve(account);
        }
        jdbc.update(
                "insert into qa_runs(account_id,request_id,channel,credential_id,budget_day,status,reserved_micros,call_bound_micros,candy_reserved,created_at,expires_at,requested_provider,provider,model,fallback_reason,input_price,cache_read_price,cache_write_price,output_price) values (?,?,?,?,?,'RUNNING',?,?,?,?,?,?,?,?,?,?,?,?,?)",
                account,
                request,
                credential == null ? "WEB" : "WISH",
                credential,
                Date.valueOf(day),
                provider.runBound(policy),
                provider.callBound(policy),
                credential != null,
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant().plus(policy.runTime())),
                admission.requested(),
                provider.id(),
                provider.model(),
                admission.reason(),
                provider.prices().input(),
                provider.prices().cacheRead(),
                provider.prices().cacheWrite(),
                provider.prices().output());
        return new Start(find(account, request), true);
    }

    private Admission select(String requestedProvider, LocalDate day) {
        QaProvider requested = models.require(requestedProvider);
        if (requested.id().equals("anthropic") && !month.reserve(day, requested.runBound(policy))) {
            return new Admission(models.require("deepseek"), requested.id(), "ANTHROPIC_MONTHLY_BUDGET");
        }
        return new Admission(requested, requested.id(), null);
    }

    Run resume(Run run, int revision) {
        run = expire(run);
        if (!run.status().equals("WAITING") || run.revision() != revision) {
            throw new QaException("QA_CONFLICT", "The clarification has changed or finished; read its current status");
        }
        capacity();
        jdbc.update(
                "update qa_runs set status='RUNNING',expires_at=? where account_id=? and request_id=?",
                Timestamp.from(clock.instant().plus(policy.runTime())),
                run.account(),
                run.request());
        return find(run.account(), run.request());
    }

    Run dispatch(Run run) {
        run = expire(run);
        requireRunning(run);
        if (run.inFlight()) {
            throw new QaException("QA_CONFLICT", "An upstream call is already recorded as in flight");
        }
        if (run.calls() >= policy.rounds() || run.reserved() < run.callBound()) {
            throw new QaException("MODEL_LIMIT", "The bounded model run is exhausted");
        }
        jdbc.update(
                "update qa_runs set in_flight=true,calls=calls+1 where account_id=? and request_id=?",
                run.account(),
                run.request());
        return find(run.account(), run.request());
    }

    Run settle(Run run, QaModel.Completion completion) {
        if (!run.inFlight() || !run.status().equals("RUNNING")) {
            return run;
        }
        boolean unknown = completion == null;
        long cost = unknown
                ? run.callBound()
                : run.prices()
                        .cost(
                                completion.inputTokens()
                                        - completion.cacheCreationTokens()
                                        - completion.cacheReadTokens(),
                                completion.cacheReadTokens(),
                                completion.cacheCreationTokens(),
                                completion.outputTokens());
        if (cost > run.callBound()) {
            cost = run.callBound();
            unknown = true;
        }
        month.settle(run, cost);
        jdbc.update(
                "update qa_budget_days set reserved_micros=reserved_micros-?,spent_micros=spent_micros+? where day=?",
                cost,
                cost,
                Date.valueOf(run.day()));
        jdbc.update(
                "update qa_runs set reserved_micros=reserved_micros-?,cost_micros=cost_micros+?,input_tokens=input_tokens+?,cache_read_tokens=cache_read_tokens+?,cache_write_tokens=cache_write_tokens+?,output_tokens=output_tokens+?,in_flight=false,uncertain=uncertain or ? where account_id=? and request_id=?",
                cost,
                cost,
                completion == null ? 0 : completion.inputTokens(),
                completion == null ? 0 : completion.cacheReadTokens(),
                completion == null ? 0 : completion.cacheCreationTokens(),
                completion == null ? 0 : completion.outputTokens(),
                unknown,
                run.account(),
                run.request());
        return find(run.account(), run.request());
    }

    Run waiting(Run run) {
        requireRunning(run);
        if (run.revision() >= 2) {
            throw new QaException("CLARIFICATION_LIMIT", "At most two clarifications belong to a question");
        }
        jdbc.update(
                "update qa_runs set status='WAITING',revision=revision+1,expires_at=? where account_id=? and request_id=?",
                Timestamp.from(clock.instant().plus(QaPolicy.CLARIFICATION_TIME)),
                run.account(),
                run.request());
        return find(run.account(), run.request());
    }

    Run finish(Run run, String code) {
        if (!run.pending()) {
            return run;
        }
        run = settle(run, null);
        month.release(run);
        jdbc.update(
                "update qa_budget_days set reserved_micros=reserved_micros-? where day=?",
                run.reserved(),
                Date.valueOf(run.day()));
        if (code != null && run.candyReserved()) {
            candy.refund(run.account());
        }
        jdbc.update(
                "update qa_runs set status=?,error_code=?,reserved_micros=0,candy_reserved=false where account_id=? and request_id=?",
                code == null ? "COMPLETED" : "FAILED",
                code,
                run.account(),
                run.request());
        return find(run.account(), run.request());
    }

    Run expire(Run run) {
        // Keep admission for a call already sent before midnight until it returns or times out.
        boolean changedDay = !run.day().equals(today()) && !run.inFlight();
        if (run.pending() && (!run.expires().isAfter(clock.instant()) || changedDay)) {
            return finish(run, "QA_EXPIRED");
        }
        return run;
    }

    Run require(UUID account, UUID request, UUID credential) {
        Run run = find(account, request);
        if (run == null) {
            throw new QaException("QA_NOT_FOUND", "This account has no such question request");
        }
        entrance(run, credential);
        return expire(run);
    }

    Run find(UUID account, UUID request) {
        List<Run> rows = jdbc.query(
                "select * from qa_runs where account_id=? and request_id=?",
                (row, index) -> new Run(
                        account,
                        request,
                        row.getObject("credential_id", UUID.class),
                        row.getDate("budget_day").toLocalDate(),
                        row.getString("status"),
                        row.getInt("revision"),
                        row.getInt("calls"),
                        row.getLong("input_tokens"),
                        row.getLong("cache_read_tokens"),
                        row.getLong("cache_write_tokens"),
                        row.getLong("output_tokens"),
                        row.getLong("cost_micros"),
                        row.getLong("reserved_micros"),
                        row.getLong("call_bound_micros"),
                        row.getBoolean("in_flight"),
                        row.getBoolean("uncertain"),
                        row.getBoolean("candy_reserved"),
                        row.getString("error_code"),
                        row.getTimestamp("expires_at").toInstant(),
                        new QaService.Selection(
                                row.getString("requested_provider"),
                                row.getString("provider"),
                                row.getString("model"),
                                row.getString("fallback_reason")),
                        new QaPrices(
                                row.getBigDecimal("input_price"),
                                row.getBigDecimal("cache_read_price"),
                                row.getBigDecimal("cache_write_price"),
                                row.getBigDecimal("output_price"))),
                account,
                request);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Key> pending() {
        return jdbc.query(
                "select account_id,request_id from qa_runs where status in ('RUNNING','WAITING') order by expires_at limit 64",
                (row, index) -> new Key(row.getObject(1, UUID.class), row.getObject(2, UUID.class)));
    }

    QaService.Allowance allowance(UUID account) {
        return new QaService.Allowance(
                Math.max(0, policy.dailyQuestions() - used(account, today())),
                policy.dailyQuestions(),
                today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                models.defaultProvider(),
                models.options(policy),
                month.snapshot());
    }

    private int used(UUID account, LocalDate day) {
        return jdbc.queryForObject(
                "select count(*) from qa_runs where account_id=? and budget_day=? and channel='WEB' and (calls>0 or status in ('RUNNING','WAITING'))",
                Integer.class,
                account,
                Date.valueOf(day));
    }

    private void capacity() {
        long active = jdbc.queryForObject("select count(*) from qa_runs where status='RUNNING'", Long.class);
        if (active >= policy.concurrentRuns()) {
            throw new QaException("QA_BUSY", "The shared model concurrency is occupied");
        }
    }

    private static void entrance(Run run, UUID credential) {
        if (!Objects.equals(run.credential(), credential)) {
            throw new QaException("QA_NOT_FOUND", "This entrance does not own that question request");
        }
    }

    static void requireRunning(Run run) {
        if (!run.status().equals("RUNNING")) {
            throw new QaException("QA_EXPIRED", "This question is no longer running");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    record Key(UUID account, UUID request) {}

    record Start(Run run, boolean created) {}

    private record Admission(QaProvider provider, String requested, String reason) {}

    record Run(
            UUID account,
            UUID request,
            UUID credential,
            LocalDate day,
            String status,
            int revision,
            int calls,
            long input,
            long cacheRead,
            long cacheWrite,
            long output,
            long cost,
            long reserved,
            long callBound,
            boolean inFlight,
            boolean uncertain,
            boolean candyReserved,
            String error,
            Instant expires,
            QaService.Selection selection,
            QaPrices prices) {
        boolean pending() {
            return status.equals("RUNNING") || status.equals("WAITING");
        }

        QaService.Usage usage() {
            return new QaService.Usage(calls, input, cacheRead, cacheWrite, output, QaPolicy.dollars(cost), uncertain);
        }
    }
}
