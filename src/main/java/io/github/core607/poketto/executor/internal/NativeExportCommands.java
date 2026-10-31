package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.assets.AssetStorageException;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PortableContentExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** Exports current authorized remote content; the sandbox supplies selection and destination, never package bytes. */
final class NativeExportCommands {
    private static final Logger log = LoggerFactory.getLogger(NativeExportCommands.class);
    private static final long MAX_TRANSFER = 1024L * 1024 * 1024;
    private final NativeRepositoryFiles files;
    private final PortableContentExports packages;

    NativeExportCommands(NativeRepositoryFiles files, PortableContentExports packages) {
        this.files = files;
        this.packages = packages;
    }

    BridgeReplies.Reply command(
            AuthPrincipal principal, AccountCommand held, NativeRepositoryLease lease, JsonNode arguments) {
        try {
            return export(principal, held, lease, arguments);
        } catch (AuthException denied) {
            return BridgeReplies.failed("ACCESS_DENIED");
        } catch (IllegalArgumentException invalid) {
            return BridgeReplies.failed("INVALID_EXPORT_SELECTION");
        } catch (ContentExportException unavailable) {
            return BridgeReplies.failed("EXPORT_" + unavailable.reason().name());
        } catch (ContentRepositoryException | AssetStorageException unavailable) {
            return BridgeReplies.failed("EXPORT_UNAVAILABLE");
        }
    }

    private BridgeReplies.Reply export(
            AuthPrincipal principal, AccountCommand held, NativeRepositoryLease lease, JsonNode arguments) {
        lease.authorize();
        BridgeArguments.Export selected = BridgeArguments.export(arguments);
        AccountCopyRecord record = held.record();
        List<String> paths = SessionExportSelection.resolve(
                selected.paths(), record.owner().fullRead() ? null : record.publicExport());
        boolean publicOnly = !record.owner().fullRead() || selected.publicOnly();
        var workspace = new WorkspaceId(record.owner().workspaceId());
        String client = SandcrabProtocol.sha256(SandcrabProtocol.write(new Client(
                record.owner(), record.copyId(), record.writer().leaseId(), record.executionId(), UUID.randomUUID())));
        try (var build = new Build(principal, workspace, paths, publicOnly, client, packageBudget(lease.remaining()))) {
            PortableContentExports.Export receipt = build.await(lease);
            requireReceipt(receipt, publicOnly);
            if (receipt.bytes() > Math.min(MAX_TRANSFER, lease.transferLimit())) {
                return BridgeReplies.failed("EXPORT_CAPACITY");
            }
            lease.authorize();
            lease.remaining();
            if (!files.install(
                    lease,
                    selected.output(),
                    receipt.bytes(),
                    receipt.sha256(),
                    output -> packages.copyTo(principal, workspace, receipt.handle(), Optional.of(client), output))) {
                return BridgeReplies.failed(
                        "LOCAL_FILE_CHANGED",
                        "The output path is unavailable or contains different local bytes. Keep it or choose another --output.");
            }
            lease.authorize();
            lease.remaining();
            return BridgeReplies.succeeded(new BridgeReplies.ExportResult(
                    selected.output(), receipt.bytes(), receipt.sha256(), publicOnly ? "public" : "private", false));
        }
    }

    private static Duration packageBudget(Duration remaining) {
        Duration reserve = remaining.dividedBy(5);
        if (reserve.compareTo(Duration.ofSeconds(5)) > 0) {
            reserve = Duration.ofSeconds(5);
        }
        return remaining.minus(reserve);
    }

    private static void requireReceipt(PortableContentExports.Export receipt, boolean publicOnly) {
        if (receipt == null || receipt.handle() == null || receipt.expiresAt() == null) {
            throw new WorkerUnavailableException(new IllegalStateException("Portable export receipt is incomplete"));
        }
        if (receipt.publicOnly() != publicOnly || receipt.bytes() < 1) {
            throw new WorkerUnavailableException(
                    new IllegalStateException("Portable export changed its scope or size"));
        }
        if (receipt.sha256() == null || !receipt.sha256().matches("[0-9a-f]{64}")) {
            throw new WorkerUnavailableException(new IllegalStateException("Portable export digest is invalid"));
        }
    }

    private record Client(AccountCopyRecord.Owner owner, UUID copy, UUID lease, UUID command, UUID operation) {}

    private enum BuildState {
        BUILDING,
        HANDED_OFF,
        ABANDONED
    }

    /** Only the export service runs in the background; it never touches the held journal or native files. */
    private final class Build implements AutoCloseable {
        private final AuthPrincipal principal;
        private final WorkspaceId workspace;
        private final String client;
        private final AtomicReference<BuildState> state = new AtomicReference<>(BuildState.BUILDING);
        private final FutureTask<PortableContentExports.Export> result;
        private final Thread deadline;

        Build(
                AuthPrincipal principal,
                WorkspaceId workspace,
                List<String> paths,
                boolean publicOnly,
                String client,
                Duration budget) {
            this.principal = principal;
            this.workspace = workspace;
            this.client = client;
            result = new FutureTask<>(() -> {
                try {
                    return packages.create(principal, workspace, paths, publicOnly, Optional.of(client));
                } finally {
                    // A close that precedes reserve can be reset by the service. Late completion
                    // therefore repeats only cleanup, under a unique host-owned invocation scope.
                    if (state.get() == BuildState.ABANDONED) {
                        closeQuietly();
                    }
                }
            });
            deadline = Thread.ofVirtual().name("native-export-deadline").start(() -> expire(budget));
            Thread.ofVirtual().name("native-export-build").start(result);
        }

        PortableContentExports.Export await(NativeRepositoryLease lease) {
            while (true) {
                lease.authorize();
                lease.remaining();
                try {
                    PortableContentExports.Export receipt = result.get(25, TimeUnit.MILLISECONDS);
                    if (!state.compareAndSet(BuildState.BUILDING, BuildState.HANDED_OFF)) {
                        throw new ContentExportException(ContentExportException.Reason.UNAVAILABLE);
                    }
                    deadline.interrupt();
                    return receipt;
                } catch (TimeoutException pending) {
                    // Observe this same build while current command authority and time remain.
                } catch (CancellationException cancelled) {
                    throw new ContentExportException(ContentExportException.Reason.UNAVAILABLE);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new WorkerUnavailableException(interrupted);
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof RuntimeException cause) {
                        throw cause;
                    }
                    if (failure.getCause() instanceof Error cause) {
                        throw cause;
                    }
                    throw new WorkerUnavailableException(failure.getCause());
                }
            }
        }

        private void expire(Duration budget) {
            try {
                Thread.sleep(budget);
            } catch (InterruptedException stopped) {
                return;
            }
            if (!state.compareAndSet(BuildState.BUILDING, BuildState.ABANDONED)) {
                return;
            }
            result.cancel(true);
            closeQuietly();
        }

        private void closeQuietly() {
            try {
                packages.closeClient(principal, workspace, client);
            } catch (ContentExportException failure) {
                log.warn("Cancelled native export retains package cleanup", failure);
            }
        }

        @Override
        public void close() {
            state.set(BuildState.ABANDONED);
            deadline.interrupt();
            result.cancel(true);
            packages.closeClient(principal, workspace, client);
        }
    }
}
