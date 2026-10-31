package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.mcp.RepositoryExecutor.ExecutionResult;
import io.github.core607.poketto.mcp.RepositoryExecutor.TerminationReason;

/** Maps native terminal states and application-owned artifacts to the MCP execution result. */
final class NativeRepositoryResults {
    private NativeRepositoryResults() {}

    static ExecutionResult create(
            SandcrabProtocol.Run run, AccountCommand held, NativeArtifacts.Output output, boolean fresh) {
        SandcrabProtocol.Result value = run.result();
        TerminationReason reason =
                switch (run.state()) {
                    case "timed_out" -> TerminationReason.TIMEOUT;
                    case "output_limit" -> TerminationReason.OUTPUT_LIMIT;
                    case "resource_limit" -> TerminationReason.RESOURCE_LIMIT;
                    case "cancelled" -> TerminationReason.CANCELLED;
                    case "succeeded", "failed" ->
                        value.code() == null ? TerminationReason.NORMAL : TerminationReason.SANDBOX_FAILURE;
                    default -> TerminationReason.SANDBOX_FAILURE;
                };
        return new ExecutionResult(
                held.record().copyId().toString(),
                held.record().state().originalCommit(),
                value.exitCode() == null ? -1 : value.exitCode(),
                output.stdout(),
                output.stderr(),
                output.stdoutTruncated(),
                output.stderrTruncated(),
                reason == TerminationReason.TIMEOUT,
                fresh,
                false,
                reason,
                output.artifacts(),
                output.errors(),
                held.view());
    }
}
