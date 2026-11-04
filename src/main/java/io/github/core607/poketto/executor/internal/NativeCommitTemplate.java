package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/** Builds a commit's working tree and local Git objects in a disposable sandbox before sharing its immutable template. */
final class NativeCommitTemplate {
    private final SandcrabClient client;
    private final NativeTransferStore transfers;

    NativeCommitTemplate(SandcrabClient client, NativeTransferStore transfers) {
        this.client = client;
        this.transfers = transfers;
    }

    void withArchive(
            SandcrabProtocol.Info service,
            String profile,
            RepositorySnapshotExports.Export export,
            Path bundle,
            Runnable authorize,
            Consumer<NativeTransferStore.File> publish) {
        var settings = service.profiles().get(profile);
        ProtocolValues.require(
                settings != null && service.capabilities().contains("templates"),
                "commit template",
                "requires a supported native profile");
        ProtocolValues.require(
                export.bundleBytes() <= settings.transferBytes(),
                "commit bundle",
                "exceeds the profile transfer bound");
        var plan = new SandcrabProtocol.SessionPlan(
                UUID.randomUUID().toString(), service.serverId(), profile, settings.sessionTtlMillis(), null);
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
                    settings.transferBytes(),
                    authorize,
                    path -> client.snapshot(
                            session, new SandcrabFiles.Plan(".", "tar"), settings.transferBytes(), path, authorize))) {
                publish.accept(tree);
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
