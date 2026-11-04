package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Installs confirmed remote commits in a full copy while its caller waits for the host reply. */
final class NativeGitBaseline {
    static final String BUNDLE = ".poketto-git-baseline.bundle";
    static final String CLEAN = "/bin/rm -f -- /workspace/" + BUNDLE;
    private static final Logger log = LoggerFactory.getLogger(NativeGitBaseline.class);
    private static final String SCRIPT = script();
    private final RepositorySnapshotExports exports;
    private final SandcrabClient client;

    NativeGitBaseline(RepositorySnapshotExports exports, SandcrabClient client) {
        this.exports = exports;
        this.client = client;
    }

    void reconcile(AuthPrincipal actor, AccountCommand held, NativeRepositoryLease lease) {
        if (!held.owner().fullRead()) {
            return;
        }
        String target = held.record().state().baseCommit();
        String previous = held.gitCommit();
        if (target.equals(previous)) {
            return;
        }
        var workspace = new WorkspaceId(held.owner().workspaceId());
        try {
            lease.authorize();
            int result = install(
                    actor, workspace, lease, exports.update(actor, workspace, previous, target), previous, target);
            if (result == 10) {
                lease.authorize();
                result = install(
                        actor, workspace, lease, exports.create(actor, workspace, Optional.of(target)), "-", target);
            }
            if (result != 0) {
                throw new Pending("Local Git installation exited " + result);
            }
            lease.authorize();
            held.installedGitCommit(target);
        } catch (WorkerUnavailableException | SandcrabClient.Rejected | ContentRepositoryException failure) {
            throw new Pending("Cannot confirm local Git installation", failure);
        }
    }

    private int install(
            AuthPrincipal actor,
            WorkspaceId workspace,
            NativeRepositoryLease lease,
            RepositorySnapshotExports.Export export,
            String previous,
            String target) {
        try {
            if (!target.equals(export.commit()) || export.bundleBytes() > lease.transferLimit()) {
                throw new Pending("Git export differs from its target or exceeds the profile transfer bound");
            }
            if (lease.outOfTime(Duration.ofMillis(250))) {
                throw new Pending("Local Git installation has no remaining command budget");
            }
            try (var shell = new NativeBaselineShell(client, lease)) {
                shell.open();
                shell.prepareBundle();
                exports.withBundle(
                        actor,
                        workspace,
                        export,
                        file -> client.putFile(
                                lease.session(),
                                new SandcrabProtocol.FilePut(BUNDLE, export.bundleBytes(), export.bundleSha256()),
                                file,
                                () -> {
                                    lease.authorize();
                                    lease.remaining();
                                }));
                String source = "/usr/bin/python3 -I - " + previous + " " + target + " <<'POKETTO_GIT_BASELINE'\n"
                        + SCRIPT + "\nPOKETTO_GIT_BASELINE";
                return shell.execute(source, lease.remaining());
            }
        } finally {
            try {
                exports.release(export.exportId());
            } catch (ContentRepositoryException cleanup) {
                log.warn("Git export cleanup failed after native baseline installation", cleanup);
            }
        }
    }

    private static String script() {
        try (var input = NativeGitBaseline.class.getResourceAsStream(
                "/io/github/core607/poketto/executor/install-git-baseline.py")) {
            return new String(
                    Objects.requireNonNull(input, "Missing Git baseline installer")
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    static final class Pending extends IllegalStateException {
        Pending(String message) {
            super(message);
        }

        Pending(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
