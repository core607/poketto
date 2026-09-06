package io.github.core607.poketto.assets;

import java.util.concurrent.atomic.AtomicBoolean;

/** Releases a reservation only after response completion and every actual producer has exited. */
public final class ImageRequestScope {
    public static final String ATTRIBUTE = ImageRequestScope.class.getName();
    private final Runnable release;
    private int producers;
    private boolean responseComplete;
    private boolean released;

    ImageRequestScope(Runnable release) {
        this.release = release;
    }

    /** Call inside the actual read/encode operation, not around scheduling or a cancellable future. */
    public synchronized Producer producer() {
        if (responseComplete || released) throw new IllegalStateException("image response has completed");
        producers++;
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) producerFinished();
        };
    }

    public synchronized void responseComplete() {
        responseComplete = true;
        releaseIfFinished();
    }

    private synchronized void producerFinished() {
        producers--;
        releaseIfFinished();
    }

    private void releaseIfFinished() {
        if (responseComplete && producers == 0 && !released) {
            released = true;
            release.run();
        }
    }

    @FunctionalInterface
    public interface Producer extends AutoCloseable {
        @Override
        void close();
    }
}
