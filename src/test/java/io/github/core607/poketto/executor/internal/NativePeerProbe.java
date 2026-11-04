package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;

/** The root controller changes socket ownership while its actual listener remains an untrusted UID. */
public final class NativePeerProbe {
    private NativePeerProbe() {}

    public static void main(String[] args) {
        if (args.length != 1 || !System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            throw new IllegalArgumentException("Native peer acceptance requires Linux and one socket path");
        }
        SandcrabClient client = ExecutorConfiguration.sandcrabClient(Path.of(args[0]));
        try {
            client.info();
            throw new IllegalStateException("The native client accepted an untrusted peer");
        } catch (WorkerUnavailableException refused) {
            // The independent controller must observe a connection but zero request bytes.
            System.out.println("native-peer-refused");
        }
    }
}
