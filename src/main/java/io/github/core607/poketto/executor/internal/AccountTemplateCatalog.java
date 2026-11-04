package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Serializes template acquisition and retirement across application processes sharing an account journal. */
final class AccountTemplateCatalog {
    private static final int MAX_SOURCES = 1024;
    private final Path root;
    private final RetainedDirectory directory;
    private final RetainedRecordFiles accountFiles;
    private final RetainedRecordFiles receipts = new RetainedRecordFiles(128 * 1024);
    private final AccountCopyFiles archives;
    private final long stagingBytes;
    private String recoveryCursor = "";

    AccountTemplateCatalog(Path root, RetainedDirectory directory, RetainedRecordFiles accountFiles, long poolBytes) {
        this.root = root;
        this.directory = directory;
        this.accountFiles = accountFiles;
        archives = AccountCopyFiles.templateArchives(root, directory, poolBytes);
        stagingBytes = Math.min(1L << 30, poolBytes);
    }

    record Source(UUID workspace, boolean fullRead, String commit, String profile) {
        Source {
            Objects.requireNonNull(workspace);
            commit = ProtocolValues.hex(commit, 40, "template commit");
            profile = SandcrabProtocol.profile(profile);
        }

        String key() {
            return SandcrabProtocol.sha256(
                    (workspace + "/" + fullRead + "/" + commit + "/" + profile).getBytes(StandardCharsets.US_ASCII));
        }

        UUID group() {
            return UUID.nameUUIDFromBytes(key().getBytes(StandardCharsets.US_ASCII));
        }
    }

    record Receipt(Source source, String template, boolean owned, AccountCopyRecord.Upper archive, Set<String> before) {
        Receipt {
            Objects.requireNonNull(source);
            ProtocolValues.require(
                    (template == null) == (archive != null), "template receipt", "must be pending or installed");
            if (template != null) {
                template = ProtocolValues.hex(template, 64, "installed template");
            }
            before = Set.copyOf(before);
            ProtocolValues.require(
                    before.size() <= MAX_SOURCES && (archive != null || before.isEmpty()),
                    "template inventory",
                    "must fit the service bound");
            before.forEach(id -> ProtocolValues.hex(id, 64, "existing template"));
        }
    }

    @FunctionalInterface
    interface Prepare {
        void withArchive(Consumer<NativeTransferStore.File> publish);
    }

    void bind(
            Source source,
            SandcrabClient client,
            NativeTransferStore transfers,
            Prepare prepare,
            Consumer<String> initialize,
            Runnable authorize) {
        try (var gate = lock()) {
            gate.requireValid();
            authorize.run();
            Receipt existing = read(source);
            if (existing == null) {
                discardUnpublished(source.key());
                if (sources().size() == MAX_SOURCES) {
                    throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
                }
                prepare.withArchive(file -> {
                    var pending = prepare(source, file, client, authorize);
                    install(pending, file, client, authorize);
                });
            } else if (existing.archive() != null) {
                resume(existing, transfers, client, authorize);
            }
            var installed = Objects.requireNonNull(read(source));
            archives.retainOnly(source.key(), null, Set.of());
            var template = client.templates().get(installed.template());
            authorize.run();
            // The owner journal pins this digest before a concurrent last-copy disposal can delete it.
            initialize.accept(template.id());
        } catch (IOException failure) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private Receipt prepare(Source source, NativeTransferStore.File file, SandcrabClient client, Runnable authorize) {
        var receipt = new Receipt(
                source,
                null,
                false,
                new AccountCopyRecord.Upper(file.size(), file.sha256()),
                client.templates().ids());
        try {
            long reserved = 0;
            for (Path path : sources()) {
                var staged = read(path).archive();
                if (staged != null) {
                    reserved += staged.bytes();
                }
            }
            if (file.size() > stagingBytes - reserved) {
                throw new RetainedCopyException(RetainedCopyException.Reason.LIMIT);
            }
            archives.publish(
                    source.key(),
                    source.group(),
                    receipt.archive(),
                    out -> {
                        try (var in = file.open()) {
                            in.transferTo(out);
                        }
                    },
                    authorize);
            save(receipt);
            return receipt;
        } catch (IOException failure) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private void resume(Receipt pending, NativeTransferStore transfers, SandcrabClient client, Runnable authorize)
            throws IOException {
        var source = pending.source();
        try (var file = transfers.write(
                pending.archive().bytes(),
                authorize,
                out -> archives.copy(source.key(), source.group(), pending.archive(), out, authorize))) {
            install(pending, file, client, authorize);
        }
    }

    private void install(Receipt pending, NativeTransferStore.File file, SandcrabClient client, Runnable authorize) {
        var source = pending.source();
        // Re-importing these exact bytes yields the same normalized tree after a lost reply.
        String id =
                client.templates().publish(source.profile(), file, authorize).id();
        try {
            save(new Receipt(source, id, !pending.before().contains(id), null, Set.of()));
            archives.retainOnly(source.key(), null, Set.of());
        } catch (IOException failure) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    void retire(SandcrabClient client, NativeTransferStore transfers, String requested) {
        try (var gate = lock()) {
            gate.requireValid();
            if (requested == null) {
                cleanAbandonedStages();
            }
            var sources = sources();
            if (sources.isEmpty()) {
                return;
            }
            var pinned = journalPins();
            if (requested != null && pinned.contains(requested)) {
                return;
            }
            var installed = new ArrayList<Receipt>();
            var pending = new ArrayList<Receipt>();
            for (Path path : sources) {
                var receipt = read(path);
                if (receipt.template() != null) {
                    installed.add(receipt);
                } else {
                    pending.add(receipt);
                }
            }
            if (requested == null && !pending.isEmpty()) {
                recoverUnused(client, transfers, pending, installed);
            }
            var candidates = installed.stream()
                    .map(Receipt::template)
                    .distinct()
                    .filter(id -> (requested == null || requested.equals(id)) && !pinned.contains(id))
                    .limit(8)
                    .toList();
            for (String id : candidates) {
                var aliases = installed.stream()
                        .filter(row -> id.equals(row.template()))
                        .toList();
                if (aliases.stream().anyMatch(Receipt::owned)) {
                    try {
                        client.templates().delete(id);
                    } catch (SandcrabClient.Rejected refused) {
                        if (refused.status() == 409 && refused.code().equals("TEMPLATE_BUSY")) {
                            continue;
                        }
                        if (!GameSandboxJob.missing(refused, "TEMPLATE_NOT_FOUND")) {
                            throw refused;
                        }
                    }
                }
                for (Receipt alias : aliases) {
                    Files.delete(path(alias.source()));
                    archives.retainOnly(alias.source().key(), null, Set.of());
                }
                RetainedDirectory.sync(root);
            }
        } catch (IOException failure) {
            throw new RetainedCopyException(RetainedCopyException.Reason.UNAVAILABLE, failure);
        }
    }

    private void recoverUnused(
            SandcrabClient client, NativeTransferStore transfers, List<Receipt> pending, List<Receipt> installed)
            throws IOException {
        pending.sort(Comparator.comparing(receipt -> receipt.source().key()));
        var selected = pending.stream()
                .filter(row -> row.source().key().compareTo(recoveryCursor) > 0)
                .findFirst()
                .orElse(pending.getFirst());
        recoveryCursor = selected.source().key();
        // A failed submit can leave only an existing object. If new IDs exist, replay the
        // fixed archive to identify ours before deciding what may be retired.
        if (selected.before().containsAll(client.templates().ids())) {
            Files.delete(path(selected.source()));
            RetainedDirectory.sync(root);
            discardUnpublished(selected.source().key());
        } else {
            resume(selected, transfers, client, () -> {});
            installed.add(Objects.requireNonNull(read(selected.source())));
        }
    }

    private void cleanAbandonedStages() throws IOException {
        int found = 0;
        try (var markers = Files.newDirectoryStream(root, "*.template-stages")) {
            for (Path path : markers) {
                String name = path.getFileName().toString();
                if (++found > MAX_SOURCES || !name.matches("[0-9a-f]{64}\\.template-stages")) {
                    throw new IOException("Invalid staged template inventory");
                }
                String key = name.substring(0, 64);
                if (!Files.exists(root.resolve(key + ".template"), NOFOLLOW_LINKS)) {
                    discardUnpublished(key);
                }
            }
        }
    }

    private void discardUnpublished(String key) throws IOException {
        Path temporary = root.resolve(ProtocolValues.hex(key, 64, "template source") + ".pending");
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            directory.checkFile(temporary);
            Files.delete(temporary);
        }
        archives.retainOnly(key, null, Set.of());
    }

    private Set<String> journalPins() throws IOException {
        var pinned = new HashSet<String>();
        int count = 0;
        try (var accounts = Files.newDirectoryStream(root, "*.account")) {
            for (Path path : accounts) {
                if (++count > MAX_SOURCES) {
                    throw new IOException("Account inventory exceeds its bound");
                }
                directory.checkFile(path);
                var record = accountFiles.read(path, StoredAccountCopy.class);
                if (!path.equals(root.resolve(AccountCopyStore.key(record.owner()) + ".account"))) {
                    throw new IOException("Invalid account journal address");
                }
                var storage = record.writer().storage();
                if (storage != null && storage.templateId() != null) {
                    pinned.add(storage.templateId());
                }
            }
        }
        return Set.copyOf(pinned);
    }

    private List<Path> sources() throws IOException {
        var paths = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(root, "*.template")) {
            for (Path path : stream) {
                if (paths.size() >= MAX_SOURCES
                        || !path.getFileName().toString().matches("[0-9a-f]{64}\\.template")) {
                    throw new IOException("Invalid template receipt inventory");
                }
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    private RetainedFileLocks.Held lock() throws IOException {
        directory.checkRoot();
        Path path = root.resolve(".template-guard");
        return RetainedFileLocks.await(path, () -> directory.openLock(path), Duration.ofSeconds(90));
    }

    private Path path(Source source) {
        return root.resolve(source.key() + ".template");
    }

    private Receipt read(Source source) throws IOException {
        return Files.exists(path(source), NOFOLLOW_LINKS) ? read(path(source)) : null;
    }

    private Receipt read(Path path) throws IOException {
        directory.checkFile(path);
        var record = receipts.read(path, Receipt.class);
        if (!path(record.source()).equals(path)) {
            throw new IOException("Template source does not match its receipt address");
        }
        return record;
    }

    private void save(Receipt receipt) throws IOException {
        Path temporary = root.resolve(".template-pending");
        if (Files.exists(temporary, NOFOLLOW_LINKS)) {
            directory.checkFile(temporary);
            Files.delete(temporary);
        }
        receipts.write(temporary, receipt, 128 * 1024);
        Files.move(
                temporary, path(receipt.source()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        RetainedDirectory.sync(root);
    }
}
