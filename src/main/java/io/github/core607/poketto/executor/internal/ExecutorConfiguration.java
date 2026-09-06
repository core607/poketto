package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "poketto.executor.enabled", havingValue = "true")
class ExecutorConfiguration {
    @Bean
    IsolatedRepositoryExecutor isolatedRepositoryExecutor(
            AuthService auth,
            RepositorySnapshotExports exports,
            ObjectMapper json,
            @Value("${poketto.executor.socket}") Path socket,
            @Value("${poketto.executor.signing-key}") Path key,
            @Value("${poketto.executor.max-sessions:2}") int maxSessions,
            @Value("${poketto.executor.open-timeout-seconds:60}") long openSeconds,
            @Value("${poketto.executor.close-timeout-seconds:15}") long closeSeconds) {
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux"))
            throw new IllegalStateException("The isolated execution service requires Linux");
        if (maxSessions < 1
                || maxSessions > 64
                || openSeconds < 1
                || openSeconds > 300
                || closeSeconds < 1
                || closeSeconds > 60) throw new IllegalArgumentException("Invalid isolated executor bounds");
        WorkerClient client = new WorkerClient(
                socket,
                privateKey(key),
                () -> socketPermissions(socket),
                channel -> {
                    try {
                        if (!channel.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED)
                                .user()
                                .getName()
                                .equals("root")) throw new WorkerUnavailableException();
                    } catch (java.io.IOException exception) {
                        throw new WorkerUnavailableException();
                    }
                },
                json,
                Clock.systemUTC());
        return new IsolatedRepositoryExecutor(
                auth, exports, client, maxSessions, Duration.ofSeconds(openSeconds), Duration.ofSeconds(closeSeconds));
    }

    private static PrivateKey privateKey(Path path) {
        try {
            ancestors(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16 * 1024)
                throw new IllegalArgumentException();
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream()
                    .anyMatch(permission -> permission.name().startsWith("GROUP_")
                            || permission.name().startsWith("OTHERS_"))) throw new IllegalArgumentException();
            String pem = Files.readString(path);
            byte[] bytes = Base64.getMimeDecoder()
                    .decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Executor signing key must be a private, regular Ed25519 PKCS8 file");
        }
    }

    private static void socketPermissions(Path path) {
        try {
            ancestors(path);
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                if (!Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS).getName().equals("root"))
                    throw new IllegalArgumentException();
            }
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            int mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if ((mode & 0170000) != 0140000
                    || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)
                            .getName()
                            .equals("root")
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new WorkerUnavailableException();
        }
    }

    private static void ancestors(Path path) throws java.io.IOException {
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException();
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException();
            if (!current.equals(path)) {
                var permissions = Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS);
                if (permissions.contains(PosixFilePermission.OTHERS_WRITE)
                        || permissions.contains(PosixFilePermission.GROUP_WRITE)) throw new IllegalArgumentException();
            }
        }
    }
}
