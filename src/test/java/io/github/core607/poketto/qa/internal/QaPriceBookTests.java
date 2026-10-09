package io.github.core607.poketto.qa.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class QaPriceBookTests {
    @TempDir
    Path directory;

    @Test
    void reloadReplacesTheWholeCatalogButBadFilesKeepTheLastValidRates() throws Exception {
        Path file = directory.resolve("prices.json");
        Files.writeString(file, catalog("0.10"));
        var clock = new MutableClock();
        var book =
                new QaPriceBook(JsonMapper.shared(), file, Set.of(new QaPriceBook.Key("anthropic", "fixture")), clock);
        QaPrices before = book.require("anthropic", "fixture");
        Files.writeString(file, catalog("0.20"));
        book.refresh();
        assertThat(book.require("anthropic", "fixture").input()).isEqualByComparingTo("0.20");
        assertThat(before.input()).isEqualByComparingTo("0.10");
        for (String invalid : new String[] {
            "{",
            "null",
            "[]",
            catalog("-1"),
            "{\"models\":[]}",
            "{\"models\":[null]}",
            catalog("0.10").replace("fixture", "missing"),
            " ".repeat(65537)
        }) {
            Files.writeString(file, invalid);
            clock.now = clock.now.plusSeconds(6);
            book.refresh();
            assertThat(book.require("anthropic", "fixture").input()).isEqualByComparingTo("0.20");
        }
        Files.delete(file);
        clock.now = clock.now.plusSeconds(6);
        book.refresh();
        assertThat(book.require("anthropic", "fixture").input()).isEqualByComparingTo("0.20");
        Files.writeString(file, catalog("0.30"));
        clock.now = clock.now.plusSeconds(6);
        book.refresh();
        assertThat(book.require("anthropic", "fixture").input()).isEqualByComparingTo("0.30");
    }

    @Test
    void explicitMissingFileCannotSilentlyUseBundledPrices() {
        assertThatThrownBy(() -> new QaPriceBook(
                        JsonMapper.shared(),
                        directory.resolve("missing.json"),
                        Set.of(new QaPriceBook.Key("anthropic", "fixture")),
                        Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ordinaryCachedAndWrittenInputUseDisjointRatesAndRoundOnlyTheFinalCharge() {
        var prices = new QaPrices(
                new BigDecimal("0.10"), new BigDecimal("0.01"), new BigDecimal("0.125"), new BigDecimal("0.50"));
        assertThat(prices.cost(100, 1000, 1000, 10)).isEqualTo(150);
        assertThat(prices.cost(1, 1, 1, 1)).isEqualTo(1);
        assertThat(prices.bound(2100, 10)).isEqualTo(268);
    }

    private static String catalog(String input) {
        return """
                {"models":[{"provider":"anthropic","model":"fixture","usdPerMillion":
                {"input":%s,"cacheRead":0.01,"cacheWrite":0.125,"output":0.50}}]}
                """.formatted(input);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-09T12:00:00Z");

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
