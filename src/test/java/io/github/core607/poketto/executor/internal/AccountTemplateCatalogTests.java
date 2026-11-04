package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class AccountTemplateCatalogTests {
    @TempDir
    Path root;

    private static final String TEMPLATE = "e".repeat(64);
    private final SandcrabClient client = mock(SandcrabClient.class);
    private final SandcrabTemplates remote = mock(SandcrabTemplates.class);
    private final Set<String> installed = new HashSet<>();
    private final AtomicBoolean lose = new AtomicBoolean();
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger submitted = new AtomicInteger();
    private final AccountTemplateCatalog.Source source =
            new AccountTemplateCatalog.Source(UUID.randomUUID(), true, "b".repeat(40), "repository");
    private NativeTransferStore transfers;
    private Path records;

    @BeforeEach
    void files() throws Exception {
        root = root.toRealPath();
        records = root.resolve("records");
        transfers = new NativeTransferStore(root.resolve("transfers"), 1024 * 1024, 8);
        when(client.templates()).thenReturn(remote);
        when(remote.ids()).thenAnswer(call -> Set.copyOf(installed));
        when(remote.get(TEMPLATE)).thenReturn(new SandcrabProtocol.Template(TEMPLATE, 1024, 1));
        when(remote.publish(anyString(), any(), any())).thenAnswer(call -> {
            NativeTransferStore.File input = call.getArgument(1);
            assertThat(Files.readAllBytes(input.path())).isEqualTo(new byte[2048]);
            submitted.incrementAndGet();
            installed.add(TEMPLATE);
            if (lose.getAndSet(false)) {
                throw new WorkerUnavailableException();
            }
            return new SandcrabProtocol.Template(TEMPLATE, 1024, 1);
        });
        doAnswer(call -> installed.remove(TEMPLATE)).when(remote).delete(TEMPLATE);
    }

    @AfterEach
    void close() {
        transfers.close();
    }

    @Test
    void aPreexistingMatchingTemplateIsBorrowedAndSurvivesLastRelease() throws Exception {
        installed.add(TEMPLATE);
        catalog()
                .bind(
                        source,
                        client,
                        transfers,
                        this::prepare,
                        id -> assertThat(id).isEqualTo(TEMPLATE),
                        () -> {});
        catalog()
                .bind(
                        source,
                        client,
                        transfers,
                        ignored -> {
                            throw new AssertionError("Known commit was rebuilt");
                        },
                        id -> assertThat(id).isEqualTo(TEMPLATE),
                        () -> {});
        catalog().retire(client, transfers, TEMPLATE);
        assertThat(prepared.get()).isEqualTo(1);
        assertThat(installed).containsExactly(TEMPLATE);
        verify(remote, never()).delete(anyString());
        assertNoReceipt();
    }

    @Test
    void anInterruptedPublicationIsReconciledFromItsDurableBytesAfterRestart() throws Exception {
        lose.set(true);
        assertThatThrownBy(() -> catalog()
                        .bind(
                                source,
                                client,
                                transfers,
                                this::prepare,
                                ignored -> {
                                    throw new AssertionError("An unconfirmed template cannot admit a copy");
                                },
                                () -> {}))
                .isInstanceOf(WorkerUnavailableException.class);
        assertThat(installed).containsExactly(TEMPLATE);
        catalog()
                .bind(
                        source,
                        client,
                        transfers,
                        ignored -> {
                            throw new AssertionError("Recovery must use the fixed archive");
                        },
                        id -> assertThat(id).isEqualTo(TEMPLATE),
                        () -> {});
        assertThat(prepared.get()).isEqualTo(1);
        assertThat(submitted.get()).isEqualTo(2);
        catalog().retire(client, transfers, TEMPLATE);
        assertThat(installed).isEmpty();
        assertNoReceipt();
    }

    @Test
    void collectionIdentifiesAnUnacknowledgedNewTemplateWithoutASecondClient() throws Exception {
        lose.set(true);
        assertThatThrownBy(() -> catalog()
                        .bind(
                                source,
                                client,
                                transfers,
                                this::prepare,
                                ignored -> {
                                    throw new AssertionError();
                                },
                                () -> {}))
                .isInstanceOf(WorkerUnavailableException.class);
        catalog().retire(client, transfers, null);
        assertThat(installed).isEmpty();
        assertThat(submitted.get()).isEqualTo(2);
        assertNoReceipt();
    }

    private AccountTemplateCatalog catalog() throws Exception {
        return new AccountTemplateCatalog(
                records, new RetainedDirectory(records), new RetainedRecordFiles(4096), 1 << 20);
    }

    private void prepare(java.util.function.Consumer<NativeTransferStore.File> publish) {
        prepared.incrementAndGet();
        try (var archive = transfers.write(2048, () -> {}, output -> output.write(new byte[2048]))) {
            publish.accept(archive);
        }
    }

    private void assertNoReceipt() throws Exception {
        try (var paths = Files.list(records)) {
            assertThat(paths.map(path -> path.getFileName().toString()).toList())
                    .containsExactly(".template-guard");
        }
    }
}
