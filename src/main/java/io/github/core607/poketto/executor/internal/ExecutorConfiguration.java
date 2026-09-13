package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import jdk.net.ExtendedSocketOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.executor.enabled", havingValue = "true")
class ExecutorConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ExecutorConfiguration.class);

    @Bean
    MeterBinder executorMetrics(IsolatedRepositoryExecutor executor) {
        return executor::bindMetrics;
    }

    @Bean
    IsolatedRepositoryExecutor isolatedRepositoryExecutor(
            AuthService auth,
            RepositorySnapshotExports exports,
            PortableContentExports packages,
            MediaFileService media,
            AuthorizedRepositoryReader reader,
            RepositoryPatchService patches,
            RepositoryMoveService moves,
            ObjectMapper json,
            @Value("${poketto.executor.socket}") Path socket,
            @Value("${poketto.executor.signing-key}") Path key,
            @Value("${poketto.executor.max-sessions:2}") int maxSessions,
            @Value("${poketto.executor.open-timeout-seconds:60}") long openSeconds,
            @Value("${poketto.executor.close-timeout-seconds:15}") long closeSeconds) {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")) {
            throw new IllegalStateException("The isolated execution service requires Linux");
        }
        if (maxSessions < 1
                || maxSessions > 64
                || openSeconds < 1
                || openSeconds > 300
                || closeSeconds < 1
                || closeSeconds > 60) {
            throw new IllegalArgumentException("Invalid isolated executor bounds");
        }
        WorkerClient client = new WorkerClient(
                socket,
                privateKey(key),
                () -> socketPermissions(socket),
                channel -> {
                    try {
                        if (!channel.getOption(ExtendedSocketOptions.SO_PEERCRED)
                                .user()
                                .getName()
                                .equals("root")) {
                            throw new WorkerUnavailableException();
                        }
                    } catch (IOException exception) {
                        throw new WorkerUnavailableException();
                    }
                },
                json,
                Clock.systemUTC());
        return new IsolatedRepositoryExecutor(
                packages,
                media,
                new SelectedFileSaves(auth, reader, patches, moves),
                auth,
                exports,
                client,
                maxSessions,
                Duration.ofSeconds(openSeconds),
                Duration.ofSeconds(closeSeconds));
    }

    private static PrivateKey privateKey(Path path) {
        try {
            ancestors(path);
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
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception exception) {
            // The message states the requirement; the cause says which part of it failed.
            throw new IllegalStateException(
                    "Executor signing key must be a private, regular Ed25519 PKCS8 file", exception);
        }
    }

    private static void socketPermissions(Path path) {
        try {
            ancestors(path);
            // A directory anyone else owns can be renamed or replaced, which moves the socket this
            // application connects to. Requiring root ownership the whole way up means only root
            // can put a different endpoint where the worker is expected to be.
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                if (!Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS).getName().equals("root")) {
                    throw new IllegalArgumentException("every ancestor of a protected path must be owned by root");
                }
            }
            // The endpoint must be a socket owned by root and closed to everyone outside its
            // group: a regular file or a socket someone else can bind would let another account
            // answer in the worker's place, and the application would sign real leases to it.
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if ((mode & 0170000) != 0140000
                    || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                            .getName()
                            .equals("root")
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalArgumentException("a protected path must not be writable by group or others");
            }
        } catch (Exception exception) {
            // The caller only learns the socket is unusable, so the reason has to reach the log.
            log.warn("Executor socket path failed its ownership and permission check", exception);
            throw new WorkerUnavailableException(exception);
        }
    }

    private static void ancestors(Path path) throws IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("a protected path must be absolute and already normalized");
        }
        // Checked from the path upwards, because a link anywhere along the way makes every
        // check below it describe a different file than the one that will be opened.
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("a protected path must not traverse a symbolic link");
            }
            if (!current.equals(path)) {
                var permissions = Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(PosixFilePermission.OTHERS_WRITE)
                        || permissions.contains(PosixFilePermission.GROUP_WRITE)) {
                    throw new IllegalArgumentException("a protected directory must not be writable by group or others");
                }
            }
        }
    }
}
