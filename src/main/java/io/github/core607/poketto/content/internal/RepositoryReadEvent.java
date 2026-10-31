package io.github.core607.poketto.content.internal;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import jdk.jfr.Timespan;

/** Measures main resolution before the reader callback; no repository or credential identity is recorded. */
@Name("poketto.RepositoryReadResolution")
@Label("Repository read resolution")
@Category({"Poketto", "Content"})
@StackTrace(false)
@Threshold("0 ns")
final class RepositoryReadEvent extends Event {
    @Label("Workspace lock wait")
    @Timespan(Timespan.NANOSECONDS)
    long lockWaitNanos;

    @Label("Remote fetch elapsed")
    @Timespan(Timespan.NANOSECONDS)
    long fetchNanos;

    @Label("Recorded main reused")
    boolean cacheReused;

    @Label("Remote fetch attempted")
    boolean fetchAttempted;

    @Label("Main resolved")
    boolean resolved;

    static RepositoryReadEvent start(boolean read) {
        if (!read) {
            return null;
        }
        var event = new RepositoryReadEvent();
        if (!event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    void resolved() {
        resolved = true;
        end();
    }

    void finish() {
        if (!resolved) {
            end();
        }
        commit();
    }
}
