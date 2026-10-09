package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaService;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the same global ledger transaction as daily admission; only Anthropic consumes this budget. */
final class QaMonth {
    private final JdbcTemplate jdbc;
    private final long limit;
    private final Clock clock;

    QaMonth(JdbcTemplate jdbc, long limit, Clock clock) {
        this.jdbc = jdbc;
        this.limit = limit;
        this.clock = clock;
    }

    boolean reserve(LocalDate day, long bound) {
        Date month = Date.valueOf(day.withDayOfMonth(1));
        jdbc.update("insert into qa_anthropic_months(month) values (?) on conflict do nothing", month);
        return jdbc.update(
                        "update qa_anthropic_months set reserved_micros=reserved_micros+? "
                                + "where month=? and spent_micros+reserved_micros+?<=?",
                        bound,
                        month,
                        bound,
                        limit)
                == 1;
    }

    void settle(QaLedger.Run run, long cost) {
        if (run.selection().provider().equals("anthropic")) {
            jdbc.update(
                    "update qa_anthropic_months set reserved_micros=reserved_micros-?,spent_micros=spent_micros+? where month=?",
                    cost,
                    cost,
                    Date.valueOf(run.day().withDayOfMonth(1)));
        }
    }

    void release(QaLedger.Run run) {
        if (run.selection().provider().equals("anthropic")) {
            jdbc.update(
                    "update qa_anthropic_months set reserved_micros=reserved_micros-? where month=?",
                    run.reserved(),
                    Date.valueOf(run.day().withDayOfMonth(1)));
        }
    }

    QaService.MonthBudget snapshot() {
        LocalDate month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        List<Amounts> rows = jdbc.query(
                "select spent_micros,reserved_micros from qa_anthropic_months where month=?",
                (row, index) -> new Amounts(row.getLong(1), row.getLong(2)),
                Date.valueOf(month));
        Amounts amounts = rows.isEmpty() ? new Amounts(0, 0) : rows.getFirst();
        return new QaService.MonthBudget(
                QaPolicy.dollars(limit),
                QaPolicy.dollars(amounts.spent()),
                QaPolicy.dollars(amounts.reserved()),
                QaPolicy.dollars(Math.max(0, limit - amounts.spent() - amounts.reserved())),
                month.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
    }

    private record Amounts(long spent, long reserved) {}
}
