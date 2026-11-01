package io.github.core607.poketto.acceptance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A fixture-only identity permits reopening its own data without reseeding Git or PostgreSQL. */
final class AcceptanceRestartState {
    private static final String MARKER = ".retained-process-fixture";

    private AcceptanceRestartState() {}

    static boolean reopen(Path root, String identity) throws IOException {
        if (identity != null && !identity.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Retained fixture identity must contain 32 lowercase hex characters");
        }
        Files.createDirectories(root);
        try (var entries = Files.list(root)) {
            if (entries.findAny().isEmpty()) {
                return false;
            }
        }
        Path marker = root.resolve(MARKER);
        if (!matches(root, marker, identity)) {
            throw new IllegalArgumentException("Acceptance root is nonempty or does not match its retained fixture");
        }
        return true;
    }

    private static boolean matches(Path root, Path marker, String identity) throws IOException {
        return identity != null
                && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && Files.size(marker) == 33
                && Files.readString(marker).equals(identity + "\n")
                && Files.isDirectory(root.resolve("remote.git/objects"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(root.resolve("remote.git/HEAD"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(root.resolve("data"), LinkOption.NOFOLLOW_LINKS);
    }

    static void ready(Path root, String identity, boolean reopened) throws IOException {
        if (identity != null && !reopened) {
            Files.writeString(root.resolve(MARKER), identity + "\n", StandardOpenOption.CREATE_NEW);
        }
    }
}
