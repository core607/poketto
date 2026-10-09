package io.github.core607.poketto.qa.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Operator rates, atomically replaced outside account transactions. Questions persist their own snapshot. */
final class QaPriceBook {
    private static final Logger log = LoggerFactory.getLogger(QaPriceBook.class);
    private final ObjectMapper json;
    private final Path file;
    private final Set<Key> required;
    private final Clock clock;
    private volatile Map<Key, QaPrices> prices;
    private byte[] lastAttempt;
    private Instant nextRead = Instant.MIN;
    private boolean rejected;

    QaPriceBook(ObjectMapper json, Path file, Set<Key> required, Clock clock) {
        this.json = json;
        this.file = file;
        this.required = Set.copyOf(required);
        this.clock = clock;
        try {
            lastAttempt = read();
            prices = parse(lastAttempt);
        } catch (IOException | JacksonException | IllegalArgumentException invalid) {
            throw new IllegalStateException("QA pricing requires a valid catalog for every configured model", invalid);
        }
    }

    QaPriceBook(Map<Key, QaPrices> fixed) {
        json = null;
        file = null;
        required = Set.copyOf(fixed.keySet());
        clock = Clock.systemUTC();
        prices = Map.copyOf(fixed);
    }

    synchronized void refresh() {
        if (file == null || clock.instant().isBefore(nextRead)) {
            return;
        }
        nextRead = clock.instant().plusSeconds(5);
        try {
            byte[] bytes = read();
            if (Arrays.equals(bytes, lastAttempt)) {
                return;
            }
            lastAttempt = bytes;
            Map<Key, QaPrices> replacement = parse(bytes);
            prices = replacement;
            rejected = false;
            log.info("QA pricing catalog reloaded; existing question rates remain unchanged");
        } catch (IOException | JacksonException | IllegalArgumentException invalid) {
            if (!rejected) {
                log.warn("QA pricing update rejected; retaining the previous catalog", invalid);
            }
            rejected = true;
        }
    }

    QaPrices require(String provider, String model) {
        QaPrices result = prices.get(new Key(provider, model));
        if (result == null) {
            throw new IllegalArgumentException("QA model has no configured pricing");
        }
        return result;
    }

    private byte[] read() throws IOException {
        try (InputStream source =
                file == null ? QaPriceBook.class.getResourceAsStream("/qa/prices.json") : Files.newInputStream(file)) {
            if (source == null) {
                throw new IOException("QA price catalog is missing");
            }
            byte[] bytes = source.readNBytes(65_537);
            if (bytes.length > 65_536) {
                throw new IllegalArgumentException("QA price catalog exceeds 64 KiB");
            }
            return bytes;
        }
    }

    private Map<Key, QaPrices> parse(byte[] bytes) {
        Catalog catalog = json.readValue(bytes, Catalog.class);
        if (catalog == null) {
            throw new IllegalArgumentException("QA price catalog must be an object");
        }
        var result = new HashMap<Key, QaPrices>();
        for (Rate rate : catalog.models()) {
            if (result.putIfAbsent(new Key(rate.provider(), rate.model()), rate.usdPerMillion()) != null) {
                throw new IllegalArgumentException("QA price catalog contains duplicate model rates");
            }
        }
        if (!result.keySet().containsAll(required)) {
            throw new IllegalArgumentException("QA price catalog omits a configured model");
        }
        return Map.copyOf(result);
    }

    record Key(String provider, String model) {
        Key {
            if (!Set.of("anthropic", "deepseek").contains(provider == null ? "" : provider)) {
                throw new IllegalArgumentException("Unknown QA price provider");
            }
            if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
                throw new IllegalArgumentException("Invalid QA price model");
            }
        }
    }

    private record Rate(String provider, String model, QaPrices usdPerMillion) {
        Rate {
            if (usdPerMillion == null) {
                throw new IllegalArgumentException("QA model rates are missing");
            }
        }
    }

    private record Catalog(List<Rate> models) {
        Catalog {
            if (models == null
                    || models.isEmpty()
                    || models.size() > 64
                    || models.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("QA pricing requires 1–64 model entries");
            }
            models = List.copyOf(models);
        }
    }
}
