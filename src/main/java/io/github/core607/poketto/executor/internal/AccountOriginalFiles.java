package io.github.core607.poketto.executor.internal;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.core607.poketto.content.RepositoryFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Reuses the immutable archive codec; the account lease owns all access and cleanup. */
final class AccountOriginalFiles implements AutoCloseable {
    private final Path root;
    private final Path archive;
    private final Path temporary;
    private final RetainedDirectory directory;
    private final RetainedBaseline.Limits limits;
    private final Supplier<AccountCopyRecord> live;
    private RetainedBaselineFiles.Reader reader;

    AccountOriginalFiles(
            Path root,
            String key,
            RetainedDirectory directory,
            RetainedBaseline.Limits limits,
            Supplier<AccountCopyRecord> live) {
        this.root = root;
        archive = root.resolve(key + ".baseline");
        temporary = root.resolve(".original-" + key);
        this.directory = directory;
        this.limits = limits;
        this.live = live;
    }

    AccountCopyRecord.Original capture(Consumer<Consumer<RepositoryFile>> source) throws IOException {
        var record = live.get();
        ProtocolValues.require(
                record.owner().fullRead()
                        && record.phase() == AccountCopyRecord.Phase.INITIALIZING
                        && record.original() == null,
                "original capture",
                "requires an initializing full copy without a published reference");
        directory.checkRoot();
        deletePrivate(temporary);
        try {
            var reference = RetainedBaselineFiles.write(temporary, identity(record), limits, source);
            live.get();
            directory.checkFile(temporary);
            // An interrupted initialization may have published bytes without publishing their reference.
            if (Files.exists(archive, NOFOLLOW_LINKS)) {
                directory.checkFile(archive);
            }
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            RetainedDirectory.sync(root);
            return new AccountCopyRecord.Original(reference.sha256(), reference.bytes(), reference.entries());
        } finally {
            deletePrivate(temporary);
        }
    }

    Optional<RepositoryFile> find(String path) throws IOException {
        var record = live.get();
        ProtocolValues.require(
                record.owner().fullRead() && record.original() != null,
                "original lookup",
                "requires a full copy with a published reference");
        if (reader == null) {
            directory.checkRoot();
            directory.checkFile(archive);
            var original = record.original();
            var reference = new RetainedBaseline.Reference(
                    identity(record), original.sha256(), original.bytes(), original.entries());
            reader = RetainedBaselineFiles.open(archive, reference, limits);
        }
        var result = reader.find(path);
        live.get();
        return result;
    }

    void remove() throws IOException {
        close();
        directory.checkRoot();
        deletePrivate(temporary);
        deletePrivate(archive);
        RetainedDirectory.sync(root);
    }

    private void deletePrivate(Path file) throws IOException {
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            directory.checkFile(file);
            Files.delete(file);
        }
    }

    private static RetainedBaseline.Identity identity(AccountCopyRecord record) {
        return new RetainedBaseline.Identity(
                record.owner(), record.copyId(), record.state().originalCommit());
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }
}
