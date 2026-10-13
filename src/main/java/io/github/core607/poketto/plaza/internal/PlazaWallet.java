package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.plaza.PlazaException;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** The caller holds the account guard; one UTC day is the claim's idempotency identity. */
final class PlazaWallet {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    PlazaWallet(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Wallet read(UUID account, String client) {
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Entry> rows = jdbc.query(
                "select balance,claimed_day from plaza_wallets where account_id=?",
                (row, number) -> new Entry(row.getLong(1), row.getDate(2).toLocalDate()),
                account);
        Entry entry = rows.isEmpty() ? new Entry(0, null) : rows.getFirst();
        boolean claimed = entry.day() != null && !entry.day().isBefore(day);
        String next = claimed
                ? entry.day()
                        .plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toString()
                : clock.instant().toString();
        return new Wallet(entry.balance(), flavor(client), claimed, next);
    }

    Wallet claim(UUID account, String client) {
        Wallet before = read(account, client);
        if (before.claimedToday()) {
            throw new PlazaException("ALREADY_CLAIMED", "The door already opened for this account today.", "pocket");
        }
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        long balance = Math.addExact(before.balance(), 5);
        jdbc.update(
                "insert into plaza_wallets(account_id,balance,claimed_day) values (?,?,?) "
                        + "on conflict(account_id) do update set balance=excluded.balance,claimed_day=excluded.claimed_day",
                account,
                balance,
                Date.valueOf(day));
        return read(account, client);
    }

    private static String flavor(String client) {
        String name = client.toLowerCase(Locale.ROOT);
        if (name.contains("claude")) {
            return "amber";
        }
        if (name.contains("codex") || name.contains("chatgpt")) {
            return "mint";
        }
        if (name.contains("gemini")) {
            return "starlight";
        }
        return "unnamed";
    }

    record Wallet(long balance, String flavor, boolean claimedToday, String nextClaimAt) {}

    private record Entry(long balance, LocalDate day) {}
}
