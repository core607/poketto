package io.github.core607.poketto.qa.internal;

import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaService;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Public transient activity only. Provider signatures, credentials and opaque reasoning never enter this log. */
final class QaActivity {
    private final List<QaService.Activity> entries = new ArrayList<>();
    private final List<Long> starts = new ArrayList<>();
    private final ObjectMapper json;

    QaActivity(ObjectMapper json) {
        this.json = json;
    }

    synchronized int start(String kind, String name, String input) {
        int id = entries.size();
        var entry = new QaService.Activity(id, kind, name, "RUNNING", input, "", 0);
        check(entry, -1);
        entries.add(entry);
        starts.add(System.nanoTime());
        return id;
    }

    synchronized void finish(int id, String output, String state) {
        QaService.Activity before = entries.get(id);
        var after = new QaService.Activity(
                id,
                before.kind(),
                before.name(),
                state,
                before.input(),
                output,
                Math.max(0, (System.nanoTime() - starts.get(id)) / 1_000_000));
        check(after, id);
        entries.set(id, after);
    }

    synchronized void fail(String code) {
        for (int index = 0; index < entries.size(); index++) {
            QaService.Activity before = entries.get(index);
            if (before.state().equals("RUNNING")) {
                String reason = code.matches("[A-Z_]{1,80}") ? code : "QA_FAILED";
                entries.set(
                        index,
                        new QaService.Activity(
                                index,
                                before.kind(),
                                before.name(),
                                "FAILED",
                                before.input(),
                                reason,
                                Math.max(0, (System.nanoTime() - starts.get(index)) / 1_000_000)));
            }
        }
    }

    synchronized List<QaService.Activity> snapshot() {
        return List.copyOf(entries);
    }

    private void check(QaService.Activity entry, int index) {
        var candidate = new ArrayList<>(entries);
        if (index < 0) {
            candidate.add(entry);
        } else {
            candidate.set(index, entry);
        }
        // Leave space to mark every running entry failed even when the activity bound itself was exceeded.
        if (json.writeValueAsBytes(candidate).length > 253_952 || candidate.size() > 40) {
            throw new QaException("ACTIVITY_LIMIT", "The complete question activity exceeds its limit");
        }
    }
}
