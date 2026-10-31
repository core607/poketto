package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Separate fixture JVMs access the real bounded XFS journal; no test storage adapter is substituted. */
public final class NativeMigrationJournalProbe {
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private NativeMigrationJournalProbe() {}

    public static void main(String[] args) throws Exception {
        UUID copy = UUID.fromString(args[1]);
        var owner = new AccountCopyRecord.Owner(ACCOUNT, WORKSPACE, true, copy);
        AccountCopyStore store = AccountCopyTestData.disk(Path.of(args[0]));
        try (AccountCommand held = AccountCommand.ifIdle(store, owner).orElseThrow()) {
            if (args[2].equals("create")) {
                var writer = new AccountCopyRecord.Writer(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                held.initialize(
                        copy,
                        writer,
                        new SelectedFileSaves.State("a".repeat(40)).snapshot(),
                        null,
                        sink -> sink.accept(original()),
                        AccountCopyRecord.Phase.INITIALIZING);
                held.bind(writer);
            }
            System.out.println(new String(SandcrabProtocol.write(held.record()), StandardCharsets.UTF_8));
            System.out.flush();
            if (args[2].equals("lock")) {
                System.in.read();
            }
        }
    }

    private static RepositoryFile original() {
        String source = "immutable original before offline import\n";
        return new RepositoryFile(
                new WorkspaceId(WORKSPACE),
                Optional.of("a".repeat(40)),
                "private/original.md",
                false,
                Optional.of(source),
                Optional.of(DocumentRevision.sha256(source.getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
    }
}
