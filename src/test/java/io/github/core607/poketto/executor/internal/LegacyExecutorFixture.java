package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import tools.jackson.databind.ObjectMapper;

/** Constructs the retiring signed backend only for its containment and migration probes. */
final class LegacyExecutorFixture {
    IsolatedRepositoryExecutor isolatedRepositoryExecutor(
            AccountCopyStore accounts,
            AuthService auth,
            RepositorySnapshotExports exports,
            PortableContentExports packages,
            MediaFileService media,
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches,
            RepositoryMoveService moves,
            ObjectMapper json,
            Path socket,
            Path key,
            ExecutionLimits limits,
            long openSeconds,
            long closeSeconds) {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            throw new IllegalStateException("The isolated execution service requires Linux");
        }
        if (openSeconds < 1 || openSeconds > 300 || closeSeconds < 1 || closeSeconds > 60) {
            throw new IllegalArgumentException("Invalid isolated executor bounds");
        }
        return new IsolatedRepositoryExecutor(
                accounts,
                packages,
                media,
                new SelectedFileSaves(auth, reader, patches, moves),
                auth,
                exports,
                workerClient(json, socket, key),
                limits,
                Duration.ofSeconds(openSeconds),
                Duration.ofSeconds(closeSeconds));
    }

    static WorkerClient workerClient(ObjectMapper json, Path socket, Path key) {
        return new WorkerClient(
                socket,
                privateKey(key),
                () -> ExecutorConfiguration.socketPermissions(socket),
                ExecutorConfiguration::verifyPeer,
                json,
                Clock.systemUTC());
    }

    private static PrivateKey privateKey(Path path) {
        try {
            ExecutorConfiguration.ancestors(path);
            // Every check reads the path itself, never what a link points at: following one would
            // let anyone who can create a link inside a writable directory substitute another file
            // between the check and the read.
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16 * 1024) {
                throw new IllegalArgumentException("the signing key must be a regular file of at most 16 KiB");
            }
            // This key signs every request the worker trusts. Any access beyond its owner means
            // another account on this host could sign requests as the application.
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream()
                    .anyMatch(permission -> permission.name().startsWith("GROUP_")
                            || permission.name().startsWith("OTHERS_"))) {
                throw new IllegalArgumentException(
                        "the signing key must not be readable or writable by group or others");
            }
            String pem = Files.readString(path);
            byte[] bytes = Base64.getMimeDecoder()
                    .decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            return WorkerSignatures.privateKey(bytes);
        } catch (Exception exception) {
            // The message states the requirement; the cause says which part of it failed.
            throw new IllegalStateException(
                    "Executor signing key must be a private, regular Ed25519 PKCS8 file", exception);
        }
    }
}
