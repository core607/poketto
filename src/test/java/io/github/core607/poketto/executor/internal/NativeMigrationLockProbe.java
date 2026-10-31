package io.github.core607.poketto.executor.internal;

/** A separate app-UID JVM pins the real inherited FLOCKs while the controller disappears. */
public final class NativeMigrationLockProbe {
    private NativeMigrationLockProbe() {}

    public static void main(String[] args) throws Exception {
        for (int offset = 0; offset < args.length; offset += 3) {
            NativeMigrationControl.requireInheritedLock(
                    Integer.parseInt(args[offset]), Long.parseLong(args[offset + 1]), Long.parseLong(args[offset + 2]));
        }
        System.out.println("inherited-locks-held");
        System.out.flush();
        System.in.read();
    }
}
