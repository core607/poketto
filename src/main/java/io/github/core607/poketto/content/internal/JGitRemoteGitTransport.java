package io.github.core607.poketto.content.internal;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;

final class JGitRemoteGitTransport implements RemoteGitTransport {

    private static final String MAIN = Constants.R_HEADS + "main";
    private final int timeoutSeconds;

    JGitRemoteGitTransport() {
        this(30);
    }

    JGitRemoteGitTransport(int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("remote Git timeout must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ObjectId fetchMain(Repository repository, RepositoryBinding binding) {
        // A direct connection transfers only the advertised objects. JGit's fetch process would
        // record the secret-backed source URI in FETCH_HEAD inside the disposable cache, and a
        // cleanup of that file could fail and misreport an operation that already succeeded.
        try (Transport transport = Transport.open(repository, binding.location())) {
            transport.setCredentialsProvider(binding.credentials());
            transport.setTimeout(timeoutSeconds);
            try (FetchConnection connection = transport.openFetch()) {
                Ref advertised = connection.getRef(MAIN);
                if (advertised == null || advertised.getObjectId() == null) {
                    return ObjectId.zeroId();
                }
                ObjectId main = advertised.getObjectId().copy();
                if (!repository.getObjectDatabase().has(main)) {
                    connection.fetch(NullProgressMonitor.INSTANCE, List.of(advertised), Set.of());
                }
                return main;
            }
        } catch (Exception exception) {
            throw new RemoteGitTransportException("fetch");
        }
    }

    @Override
    public PushStatus pushMain(
            Repository repository,
            RepositoryBinding binding,
            ObjectId expectedCommit,
            ObjectId candidateCommit) {
        try {
            RemoteRefUpdate update = new RemoteRefUpdate(
                    repository,
                    candidateCommit.name(),
                    MAIN,
                    false,
                    null,
                    expectedCommit);
            try (Transport transport = Transport.open(repository, binding.location())) {
                transport.setCredentialsProvider(binding.credentials());
                transport.setTimeout(timeoutSeconds);
                transport.push(NullProgressMonitor.INSTANCE, List.of(update));
            }
            return switch (update.getStatus()) {
                case OK, UP_TO_DATE -> PushStatus.UPDATED;
                case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED -> PushStatus.CONFLICT;
                default -> throw new RemoteGitTransportException("ref update");
            };
        } catch (RemoteGitTransportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RemoteGitTransportException("ref update");
        }
    }
}
