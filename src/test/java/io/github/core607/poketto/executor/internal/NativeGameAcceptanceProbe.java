package io.github.core607.poketto.executor.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent game acceptance with the current native repository owner and exact controller rendezvous. */
public final class NativeGameAcceptanceProbe {
    private NativeGameAcceptanceProbe() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            throw new IllegalArgumentException("Native game acceptance requires Linux and one fixture configuration");
        }
        JsonNode config = JsonMapper.shared().readTree(Files.readAllBytes(Path.of(arguments[0])));
        var control = new NativeGameControl(
                Path.of(config.path("control").stringValue()),
                config.path("token").stringValue());
        try (var repository = new NativeGameRepository(
                Path.of(config.path("socket").stringValue()),
                Path.of(config.path("consumer").stringValue()))) {
            GameNativeProbe.Summary game =
                    new GameNativeProbe(config).run(() -> repository.read(control), control::run);
            NativeGameRepository.Summary retained = repository.finish();
            control.complete(new Summary("PASS", game, retained));
        }
    }

    record Summary(String result, GameNativeProbe.Summary game, NativeGameRepository.Summary repository) {}
}
