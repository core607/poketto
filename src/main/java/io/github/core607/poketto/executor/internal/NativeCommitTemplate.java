package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.executor.internal.NativeRepositoryPhase.Stage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/** Builds a commit's working tree and local Git objects in a disposable sandbox before sharing its immutable template. */
final class NativeCommitTemplate {
    private final SandcrabClient client;
    private final NativeTransferStore transfers;
    private final NativeRepositoryProfiles profiles;

    NativeCommitTemplate(SandcrabClient client, NativeTransferStore transfers, NativeRepositoryProfiles profiles) {
        this.client = client;
        this.transfers = transfers;
        this.profiles = profiles;
    }

    void withArchive(
            SandcrabProtocol.Info service,
            RepositorySnapshotExports.Export export,
            Path bundle,
            Runnable authorize,
            Consumer<NativeTransferStore.File> publish) {
        try (var phase = NativeRepositoryPhase.start(Stage.TEMPLATE_BUILD)) {
            SandcrabProtocol.Profile settings = profiles.offlinePreparation(service);
            SandcrabProtocol.Profile execution = service.profiles().get(profiles.execution());
            ProtocolValues.require(
                    execution != null && service.capabilities().contains("templates"),
                    "commit template",
                    "requires a supported native profile");
            ProtocolValues.require(
                    export.bundleBytes() <= settings.transferBytes(),
                    "commit bundle",
                    "exceeds the profile transfer bound");
            long archiveLimit = Math.min(settings.transferBytes(), execution.transferBytes());
            var plan = new SandcrabProtocol.SessionPlan(
                    UUID.randomUUID().toString(),
                    service.serverId(),
                    profiles.preparation(),
                    settings.sessionTtlMillis(),
                    null);
            try (var builder = new Builder(plan)) {
                authorize.run();
                var session = builder.open(authorize);
                client.putFile(
                        session,
                        new SandcrabProtocol.FilePut(
                                NativeRepositoryLayout.SEED, export.bundleBytes(), export.bundleSha256()),
                        bundle,
                        authorize);
                builder.checkout(session, settings, export.commit(), authorize);
                try (var tree = transfers.capture(
                        archiveLimit,
                        authorize,
                        path -> client.snapshot(
                                session, new SandcrabFiles.Plan(".", "tar"), archiveLimit, path, authorize))) {
                    phase.bytes(tree.size());
                    publish.accept(tree);
                }
            }
        }
    }

    private final class Builder implements AutoCloseable {
        private final SandcrabProtocol.SessionPlan plan;
        private boolean submitted;

        private Builder(SandcrabProtocol.SessionPlan plan) {
            this.plan = plan;
        }

        SandcrabProtocol.Session open(Runnable authorize) {
            submitted = true;
            SandcrabProtocol.Session session;
            try {
                session = client.open(plan);
            } catch (SandcrabClient.Rejected refused) {
                submitted = false;
                throw refused;
            } catch (WorkerUnavailableException failure) {
                submitted = failure.mayHaveExecuted();
                throw failure;
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!session.state().equals("ready")) {
                if (session.terminal() || session.state().equals("closing")) {
                    throw new WorkerUnavailableException();
                }
                NativeRepositoryLease.pause(deadline, authorize);
                session = client.session(plan.reference());
            }
            return session;
        }

        void checkout(
                SandcrabProtocol.Session session,
                SandcrabProtocol.Profile settings,
                String commit,
                Runnable authorize) {
            authorize.run();
            long timeout = Math.min(30_000, settings.timeoutMillis());
            var command = new SandcrabProtocol.CommandPlan(
                    UUID.randomUUID().toString(),
                    plan.serverId(),
                    NativeRepositoryLayout.commitTemplate(commit),
                    timeout,
                    settings.outputLimitBytes());
            var run = client.command(session, command);
            long deadline =
                    System.nanoTime() + Duration.ofMillis(timeout + 5000).toNanos();
            while (!run.terminal()) {
                NativeRepositoryLease.pause(deadline, authorize);
                run = client.get(command.reference());
            }
            client.forget(run);
            if (!run.state().equals("succeeded") || !run.result().success()) {
                throw new WorkerUnavailableException();
            }
        }

        @Override
        public void close() {
            if (!submitted) {
                return;
            }
            try {
                NativeRepositoryLease.close(client, plan.reference(), Duration.ofSeconds(15));
                client.forget(client.session(plan.reference()));
            } catch (SandcrabClient.Rejected missing) {
                if (!GameSandboxJob.missing(missing, "SESSION_NOT_FOUND")) {
                    throw missing;
                }
            }
        }
    }
}
