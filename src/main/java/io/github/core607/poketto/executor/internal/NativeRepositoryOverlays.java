package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Durable overlay bytes belong to the account journal. Only an app-controlled parent can restore or capture them. */
final class NativeRepositoryOverlays {
    private final SandcrabClient client;
    private final NativeTransferStore transfers;
    private final NativeCommitTemplate templates;
    private final AccountTemplateCatalog catalog;

    NativeRepositoryOverlays(
            SandcrabClient client,
            NativeTransferStore transfers,
            AccountTemplateCatalog catalog,
            NativeRepositoryProfiles profiles) {
        this.catalog = catalog;
        this.client = client;
        this.transfers = transfers;
        templates = new NativeCommitTemplate(client, transfers, profiles);
    }

    void bind(
            AccountTemplateCatalog.Source source,
            SandcrabProtocol.Info server,
            RepositorySnapshotExports.Export export,
            Runnable authorize,
            Bundle withBundle,
            Consumer<String> initialize) {
        catalog.bind(
                source,
                client,
                transfers,
                publish -> withBundle.read(bundle -> templates.withArchive(server, export, bundle, authorize, publish)),
                initialize,
                authorize);
    }

    @FunctionalInterface
    interface Bundle {
        void read(Consumer<Path> reader);
    }

    void retire(String id) {
        catalog.retire(client, transfers, id);
    }

    void restore(AccountCommand held, NativeRepositoryLease lease) {
        var storage = lease.writer().storage();
        var upper = storage.upper();
        if (upper == null) {
            return;
        }
        String name = ".poketto-upper-" + UUID.randomUUID();
        try (var archive =
                transfers.write(upper.bytes(), lease::authorize, out -> held.copyUpper(out, lease::authorize))) {
            client.putFile(
                    lease.session(),
                    new SandcrabProtocol.FilePut(name, upper.bytes(), upper.sha256()),
                    archive.path(),
                    lease::authorize);
        }
        String source = RestoreScript.SOURCE.replace("'", "'\"'\"'");
        lease.setup("python3 -I -c '" + source + "' " + name + " " + storage.byteLimit() + " " + storage.inodeLimit());
    }

    void checkpoint(AccountCommand held, NativeRepositoryLease lease) {
        var storage = lease.writer().storage();
        try (var archive = transfers.capture(
                Math.min(lease.transferLimit(), storage.byteLimit()),
                lease::authorize,
                path -> client.diff(
                        lease.session(),
                        Math.min(lease.transferLimit(), storage.byteLimit()),
                        path,
                        lease::authorize))) {
            checkLimits(archive, storage, lease::authorize);
            held.completeWithUpper(archive, lease::authorize);
        }
    }

    private static void checkLimits(
            NativeTransferStore.File file, AccountCopyRecord.NativeStorage limits, Runnable authorize) {
        try (var input = file.open();
                var archive = new TarArchiveInputStream(input)) {
            long entries = 0;
            long expanded = 0;
            for (TarArchiveEntry entry; (entry = archive.getNextEntry()) != null; ) {
                authorize.run();
                if (++entries > limits.inodeLimit()
                        || entry.getSize() < 0
                        || entry.getSize() > limits.byteLimit() - expanded) {
                    throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
                }
                expanded += entry.getSize();
            }
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    private static final class RestoreScript {
        private static final String SOURCE = load();

        private static String load() {
            try (var input = NativeRepositoryOverlays.class.getResourceAsStream(
                    "/io/github/core607/poketto/executor/restore-upper.py")) {
                return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
