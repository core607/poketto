package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A copy owns an opaque provider key. The stored generation fences late commands and deletion. */
final class SandcrabWorkspaces {
    private final SandcrabClient client;

    SandcrabWorkspaces(SandcrabClient client) {
        this.client = client;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Handle(
            String id,
            String key,
            String generation,
            String serverId,
            String profile,
            String templateId,
            String state,
            @JsonProperty(required = true) long submissionWindow) {
        Handle {
            id = ProtocolValues.uuid(id, "workspace volume");
            Objects.requireNonNull(key, "workspace key");
            generation = ProtocolValues.uuid(generation, "workspace generation");
            serverId = ProtocolValues.uuid(serverId, "workspace epoch");
            profile = SandcrabProtocol.profile(profile);
            templateId = ProtocolValues.hex(templateId, 64, "copy template");
            ProtocolValues.require(
                    Set.of("creating", "ready", "deleting", "deleted", "failed", "closing")
                            .contains(state),
                    "workspace state",
                    "must be recognized");
            ProtocolValues.require(submissionWindow >= 0, "workspace submission window", "must be nonnegative");
        }

        String route() {
            return "/v1/workspaces/" + key;
        }

        void requireGeneration(String expected) {
            verify(generation.equals(expected), "Native workspace generation changed");
        }
    }

    static void scope(Handle handle, UUID copy, AccountCopyRecord.NativeStorage storage) {
        verify(
                key(copy).equals(handle.key())
                        && storage.profile().equals(handle.profile())
                        && storage.templateId().equals(handle.templateId()),
                "Native workspace changed its copy scope");
        if (storage.workspaceGeneration() != null) {
            handle.requireGeneration(storage.workspaceGeneration().toString());
        }
    }

    private record Plan(String profile, String templateId) {}

    private record Expected(String generation) {}

    private record Submission(
            String id,
            String serverId,
            String generation,
            long submissionWindow,
            String command,
            long timeoutMillis,
            int outputLimitBytes,
            boolean freshChild,
            @JsonInclude(JsonInclude.Include.NON_NULL) String shellInit) {}

    static String key(UUID copy) {
        return "poketto-" + copy;
    }

    Handle ensure(UUID copy, AccountCopyRecord.NativeStorage storage, Runnable authorize) {
        String key = key(copy);
        String route = "/v1/workspaces/" + key;
        authorize.run();
        var current = client.request(
                "POST", route, new Plan(storage.profile(), storage.templateId()), Handle.class, 200, 202);
        String generation = current.generation();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (current.state().equals("creating")) {
            NativeRepositoryLease.pause(deadline, authorize);
            current = client.request("GET", route, null, Handle.class, 200);
            current.requireGeneration(generation);
        }
        scope(current, copy, storage);
        verify(current.state().equals("ready"), "Native workspace did not become ready");
        return current;
    }

    Optional<Handle> find(UUID copy) {
        String key = key(copy);
        try {
            var result = client.request("GET", "/v1/workspaces/" + key, null, Handle.class, 200);
            verify(key.equals(result.key()), "Native workspace reply changed its key");
            return Optional.of(result);
        } catch (SandcrabClient.Rejected refused) {
            if (GameSandboxJob.missing(refused, "WORKSPACE_NOT_FOUND")) {
                return Optional.empty();
            }
            throw refused;
        }
    }

    SandcrabProtocol.Run submit(Handle workspace, SandcrabProtocol.CommandPlan command, String initialization) {
        verify(workspace.serverId().equals(command.serverId()), "Workspace changed before admission");
        var request = new Submission(
                command.id(),
                command.serverId(),
                workspace.generation(),
                workspace.submissionWindow(),
                command.command(),
                command.timeoutMillis(),
                command.outputLimitBytes(),
                true,
                initialization);
        var run =
                client.request("POST", workspace.route() + "/commands", request, SandcrabProtocol.Run.class, 200, 202);
        verify(
                command.reference().equals(run.reference())
                        && workspace.profile().equals(run.profile()),
                "Workspace admission changed the command identity or profile");
        verify(
                workspace.generation().equals(run.workspaceGeneration())
                        && Boolean.TRUE.equals(run.freshShell())
                        && run.sessionId() != null,
                "Workspace admission changed its generation or child shell");
        return run;
    }

    void remove(Handle workspace, Runnable authorize) {
        var expected = new Expected(workspace.generation());
        authorize.run();
        Handle current = workspace;
        if (!Set.of("failed", "deleted").contains(current.state())) {
            current = client.request("POST", workspace.route() + "/delete", expected, Handle.class, 200, 202);
            long deadline = System.nanoTime() + Duration.ofSeconds(65).toNanos();
            while (current.state().equals("deleting")) {
                current.requireGeneration(workspace.generation());
                NativeRepositoryLease.pause(deadline, authorize);
                current = client.request("GET", workspace.route(), null, Handle.class, 200);
            }
        }
        current.requireGeneration(workspace.generation());
        verify(Set.of("deleted", "failed").contains(current.state()), "Workspace cleanup is unconfirmed");
        authorize.run();
        client.request("POST", workspace.route() + "/forget", expected, SandcrabProtocol.Forgotten.class, 200);
    }

    private static void verify(boolean condition, String message) {
        if (!condition) {
            throw new WorkerUnavailableException(new IOException(message));
        }
    }
}
