package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test client retains only IDs acknowledged by actual results; a rejected call never adopts another copy. */
final class RememberingExecutorClient {
    private final Map<Key, RepositoryExecutor.CopyRequest> copies = new ConcurrentHashMap<>();

    RepositoryExecutor.ExecutionResult execute(
            RepositoryExecutor executor,
            AuthPrincipal principal,
            WorkspaceId workspace,
            String session,
            Optional<String> commit,
            String command,
            Duration timeout,
            ExecutionCancellation cancellation) {
        Key key = new Key(executor, principal.subjectId(), workspace, session);
        var result = executor.execute(
                principal,
                workspace,
                session,
                copies.getOrDefault(key, new RepositoryExecutor.CopyRequest(RepositoryExecutor.NEW_COPY, null, false)),
                commit,
                command,
                timeout,
                cancellation);
        copies.put(
                key,
                new RepositoryExecutor.CopyRequest(
                        result.copyId(),
                        result.retention() == null ? null : result.retention().generation(),
                        false));
        return result;
    }

    private record Key(RepositoryExecutor executor, UUID principal, WorkspaceId workspace, String session) {}
}
