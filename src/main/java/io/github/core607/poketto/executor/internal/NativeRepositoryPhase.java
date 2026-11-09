package io.github.core607.poketto.executor.internal;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Optional nested timings contain no command, path, account, copy or credential. */
@Name(NativeRepositoryPhase.EVENT_NAME)
@Label("Native repository phase")
@Category({"Poketto", "Executor"})
@Enabled(false)
@StackTrace(false)
final class NativeRepositoryPhase extends Event implements AutoCloseable {
    static final String EVENT_NAME = "poketto.NativeRepositoryPhase";
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<NativeRepositoryPhase> CURRENT = new ThreadLocal<>();

    enum Stage {
        REQUEST,
        NATIVE_EXCHANGE,
        AUTHORIZATION,
        COPY_ADMISSION,
        RUNTIME_RECONCILIATION,
        OPERATION_ADMISSION,
        TEMPLATE_LOOKUP,
        TEMPLATE_BUILD,
        SESSION_START,
        SESSION_RENEW,
        CHILD_START,
        CHILD_CONTAINMENT,
        SETUP_COMMAND,
        COMMAND_WAIT,
        UPPER_RESTORE,
        UPPER_CAPTURE,
        UPPER_VALIDATION,
        JOURNAL_COMMIT,
        JOURNAL_RELEASE,
        SESSION_CONTAINMENT
    }

    @Description("Process-local correlation number, unrelated to product identities")
    long invocation;

    String phase;
    String parentPhase;
    int depth;

    @Description("Calling platform-thread CPU only; -1 when unavailable, including virtual threads")
    long threadCpuNanos = -1;

    @Description("Archive payload bytes where known; -1 otherwise; not physical disk writes")
    long transferredBytes = -1;

    private transient NativeRepositoryPhase previous;
    private transient long cpuStarted;
    private transient boolean recording;

    static NativeRepositoryPhase start(Stage stage) {
        var event = new NativeRepositoryPhase();
        if (event.isEnabled()) {
            event.previous = CURRENT.get();
            event.invocation = event.previous == null ? SEQUENCE.incrementAndGet() : event.previous.invocation;
            event.parentPhase = event.previous == null ? null : event.previous.phase;
            event.depth = event.previous == null ? 0 : event.previous.depth + 1;
            event.phase = stage.name();
            event.recording = true;
            CURRENT.set(event);
            event.begin();
            event.cpuStarted = threadCpu();
        }
        return event;
    }

    static <T> T call(Stage stage, Supplier<T> action) {
        try (var phase = start(stage)) {
            return action.get();
        }
    }

    static void run(Stage stage, Runnable action) {
        try (var phase = start(stage)) {
            action.run();
        }
    }

    void bytes(long value) {
        transferredBytes = value;
    }

    @Override
    public void close() {
        if (!recording) {
            return;
        }
        recording = false;
        long finished = threadCpu();
        end();
        if (cpuStarted >= 0 && finished >= cpuStarted) {
            threadCpuNanos = finished - cpuStarted;
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
        commit();
    }

    private static long threadCpu() {
        if (Thread.currentThread().isVirtual()) {
            return -1;
        }
        ThreadMXBean bean = Cpu.BEAN;
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime()
                : -1;
    }

    private static final class Cpu {
        private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    }
}
