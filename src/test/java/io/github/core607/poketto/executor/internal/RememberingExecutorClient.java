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
    private final Map<Key, String> copies = new ConcurrentHashMap<>();

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
                copies.getOrDefault(key, RepositoryExecutor.NEW_COPY),
                commit,
                command,
                timeout,
                cancellation);
        copies.put(key, result.copyId());
        return result;
    }

    private record Key(RepositoryExecutor executor, UUID principal, WorkspaceId workspace, String session) {}
}
