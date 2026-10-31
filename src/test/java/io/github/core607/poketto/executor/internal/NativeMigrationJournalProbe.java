package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Separate fixture JVMs access the real bounded XFS journal; no test storage adapter is substituted. */
public final class NativeMigrationJournalProbe {
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private NativeMigrationJournalProbe() {}

    public static void main(String[] args) throws Exception {
        ProtocolValues.require(
                args.length == 3 || args.length == 5, "fixture arguments", "require journal, copy and action");
        UUID copy = UUID.fromString(args[1]);
        var owner = new AccountCopyRecord.Owner(ACCOUNT, WORKSPACE, true, copy);
        AccountCopyStore store = AccountCopyTestData.disk(Path.of(args[0]));
        try (AccountCommand held = AccountCommand.ifIdle(store, owner).orElseThrow()) {
            if (args[2].equals("create")) {
                String original = args.length == 5 ? args[3] : "a".repeat(40);
                String base = args.length == 5 ? args[4] : "b".repeat(40);
                var writer = new AccountCopyRecord.Writer(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                held.initialize(
                        copy,
                        writer,
                        seed(original, base),
                        null,
                        sink -> sink.accept(original(original)),
                        AccountCopyRecord.Phase.INITIALIZING);
                held.bind(writer);
                held.begin(UUID.randomUUID());
                held.bind(writer);
            }
            System.out.println(new String(SandcrabProtocol.write(held.record()), StandardCharsets.UTF_8));
            System.out.flush();
            if (args[2].equals("lock")) {
                System.in.read();
            }
        }
    }

    private static RetainedSaveState seed(String original, String base) {
        var binary = new RetainedFileBaseline(
                base,
                new RetainedFileBaseline.NonText(Optional.of(DocumentRevision.sha256(new byte[] {0, 1, -1}))),
                List.of(new RepositoryDiagnostic("private/blob.bin", "BINARY", "Synthetic acknowledged binary")),
                false);
        return new RetainedSaveState(
                original,
                base,
                Map.of("private/advanced.md", base, "private/blob.bin", base, "private/deleted.md", base),
                Map.of(
                        "private/advanced.md", RetainedFileBaseline.saved(base, "acknowledged text\n"),
                        "private/blob.bin", binary,
                        "private/deleted.md", RetainedFileBaseline.saved(base, null)),
                false,
                null,
                null,
                null,
                BridgeReplies.RestoredReceipt.capture(
                        BridgeReplies.succeeded(new Receipt("synthetic-save", base, "private/advanced.md"))),
                BridgeReplies.RestoredReceipt.capture(
                        BridgeReplies.succeeded(new Receipt("synthetic-import", base, "private/blob.bin"))),
                null,
                null);
    }

    private static RepositoryFile original(String commit) {
        String source = "immutable original before offline import\n";
        return new RepositoryFile(
                new WorkspaceId(WORKSPACE),
                Optional.of(commit),
                "private/original.md",
                false,
                Optional.of(source),
                Optional.of(DocumentRevision.sha256(source.getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
    }

    record Receipt(String kind, String commit, String path) {}
}
