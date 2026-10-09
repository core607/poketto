package io.github.core607.poketto.plaza;

import java.util.List;

/** Narrative and untrusted passages are separate from platform-owned status. */
public record PlazaResult(String scene, Object data, List<String> next, Status status) {
    public PlazaResult {
        next = List.copyOf(next);
    }

    public static PlazaResult ok(String scene, Object data, String... next) {
        return new PlazaResult(scene, data, List.of(next), new Status("ok", "OK"));
    }

    public static PlazaResult refused(String code, String message, String next) {
        return new PlazaResult(message, null, next.isEmpty() ? List.of() : List.of(next), new Status("refused", code));
    }

    public record Status(String outcome, String code) {}
}
