package io.github.core607.poketto.content.internal;

import io.github.core607.poketto.content.GitReplicationException;
import io.github.core607.poketto.content.GitReplicationFailure;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefLeaseSpec;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

final class JGitRemoteMirror implements GitRemoteMirror {

    private static final String MAIN = Constants.R_HEADS + "main";
    private static final String ZERO_ID = ObjectId.zeroId().name();

    private final String remote;
    private final int timeoutSeconds;

    JGitRemoteMirror(String remote, Duration timeout) {
        this.remote = remote;
        timeoutSeconds = Math.toIntExact(Math.max(1, timeout.toSeconds()));
    }

    @Override
    public Optional<ObjectId> main(Repository repository) {
        requireConfigured(repository);
        try {
            return Git.wrap(repository).lsRemote()
                    .setRemote(remote)
                    .setHeads(true)
                    .setTags(false)
                    .setTimeout(timeoutSeconds)
                    .call()
                    .stream()
                    .filter(ref -> MAIN.equals(ref.getName()))
                    .map(Ref::getObjectId)
                    .findFirst();
        } catch (GitAPIException exception) {
            throw classified(exception, "remote main cannot be read");
        }
    }

    @Override
    public void pushMain(
            Repository repository, ObjectId candidate, Optional<ObjectId> expectedRemote) {
        requireConfigured(repository);
        String expected = expectedRemote.map(ObjectId::name).orElse(ZERO_ID);
        try {
            Iterable<PushResult> results = Git.wrap(repository).push()
                    .setRemote(remote)
                    .setRefSpecs(new RefSpec(candidate.name() + ":" + MAIN))
                    .setRefLeaseSpecs(new RefLeaseSpec(MAIN, expected))
                    .setTimeout(timeoutSeconds)
                    .call();
            RemoteRefUpdate update = null;
            for (PushResult result : results) {
                RemoteRefUpdate candidateUpdate = result.getRemoteUpdate(MAIN);
                if (candidateUpdate != null) {
                    update = candidateUpdate;
                }
            }
            if (update == null) {
                throw failure(
                        GitReplicationFailure.UNKNOWN,
                        false,
                        "remote did not report a main update");
            }
            switch (update.getStatus()) {
                case OK, UP_TO_DATE -> {
                    return;
                }
                case REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED -> throw failure(
                        GitReplicationFailure.NON_FAST_FORWARD,
                        false,
                        "remote main changed before the candidate was accepted");
                default -> throw failure(
                        GitReplicationFailure.PERMISSION_DENIED,
                        false,
                        "remote refused the main update");
            }
        } catch (GitReplicationException exception) {
            throw exception;
        } catch (GitAPIException exception) {
            throw classified(exception, "remote main cannot be updated");
        }
    }

    @Override
    public boolean configured(Repository repository) {
        String url = repository.getConfig().getString("remote", remote, "pushurl");
        if (url == null) {
            url = repository.getConfig().getString("remote", remote, "url");
        }
        return url != null && !url.isBlank();
    }

    private void requireConfigured(Repository repository) {
        if (!configured(repository)) {
            throw failure(
                    GitReplicationFailure.MISSING_REMOTE,
                    false,
                    "configured Git remote is missing");
        }
    }

    private static GitReplicationException classified(Exception exception, String operation) {
        if (exception instanceof InvalidRemoteException) {
            return failure(
                    GitReplicationFailure.MISSING_REMOTE,
                    false,
                    "configured Git remote is missing",
                    exception);
        }
        String message = String.valueOf(exception.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("auth") || message.contains("credential")) {
            return failure(
                    GitReplicationFailure.AUTHENTICATION,
                    false,
                    operation + " because authentication failed",
                    exception);
        }
        if (message.contains("not authorized")
                || message.contains("permission")
                || message.contains("denied")) {
            return failure(
                    GitReplicationFailure.PERMISSION_DENIED,
                    false,
                    operation + " because permission was denied",
                    exception);
        }
        if (message.contains("not found") || message.contains("does not exist")) {
            return failure(
                    GitReplicationFailure.REMOTE_REPOSITORY_MISSING,
                    false,
                    operation + " because the remote repository is missing",
                    exception);
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return failure(
                    GitReplicationFailure.TIMEOUT,
                    true,
                    operation + " before the timeout",
                    exception);
        }
        return failure(
                GitReplicationFailure.NETWORK,
                true,
                operation + " because the remote transport failed",
                exception);
    }

    private static GitReplicationException failure(
            GitReplicationFailure category, boolean transientFailure, String message) {
        return new GitReplicationException(category, transientFailure, message);
    }

    private static GitReplicationException failure(
            GitReplicationFailure category,
            boolean transientFailure,
            String message,
            Throwable cause) {
        return new GitReplicationException(category, transientFailure, message, cause);
    }
}
