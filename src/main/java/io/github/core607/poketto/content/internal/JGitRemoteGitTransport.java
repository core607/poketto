package io.github.core607.poketto.content.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;

final class JGitRemoteGitTransport implements RemoteGitTransport {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String CACHE_MAIN = Constants.R_REMOTES + "poketto/main";
    private static final RefSpec FETCH_MAIN = new RefSpec("+" + MAIN + ":" + CACHE_MAIN);
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
        try (Transport transport = Transport.open(repository, binding.location())) {
            transport.setCredentialsProvider(binding.credentials());
            transport.setTimeout(timeoutSeconds);
            try (FetchConnection connection = transport.openFetch()) {
                Ref advertised = connection.getRef(MAIN);
                if (advertised == null || advertised.getObjectId() == null) {
                    return ObjectId.zeroId();
                }
            }
            FetchResult result = transport.fetch(NullProgressMonitor.INSTANCE, List.of(FETCH_MAIN));
            Ref main = result.getAdvertisedRef(MAIN);
            return main == null || main.getObjectId() == null
                    ? ObjectId.zeroId()
                    : main.getObjectId().copy();
        } catch (Exception exception) {
            throw new RemoteGitTransportException("fetch");
        } finally {
            // JGit records the source URI in FETCH_HEAD. The authority binding is secret-backed,
            // so the disposable cache must not retain that transport coordinate.
            try {
                Files.deleteIfExists(repository.getDirectory().toPath().resolve("FETCH_HEAD"));
            } catch (IOException exception) {
                throw new RemoteGitTransportException("fetch metadata cleanup");
            }
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
