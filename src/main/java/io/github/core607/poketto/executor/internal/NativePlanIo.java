package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySyncEntry;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Captures protected originals and materializes only unique, non-replacing plan paths. */
final class NativePlanIo {
    private final NativeRepositoryFiles files;
    private final SelectedFileSaves saves;

    NativePlanIo(NativeRepositoryFiles files, SelectedFileSaves saves) {
        this.files = files;
        this.saves = saves;
    }

    record Captured(NativePlanFile file, String text) {
        WorkspaceSynchronization.Local local() {
            return new WorkspaceSynchronization.Local(file == null ? null : file.sha256(), text, false);
        }
    }

    Captured capture(AccountCommand held, NativeRepositoryLease lease, UUID plan, String path) {
        try (NativeTransferStore.File captured = files.capture(lease, path, NativeArtifacts.MAX_FILE)) {
            var reference = new NativePlanFile(captured.size(), captured.sha256());
            held.publishPlanFile(
                    plan,
                    reference,
                    output -> {
                        try (InputStream input = captured.open()) {
                            input.transferTo(output);
                        }
                    },
                    lease::authorize);
            String text = null;
            if (captured.size() <= ContentLimits.MAX_DOCUMENT_BYTES) {
                try (InputStream input = captured.open()) {
                    text = text(input.readAllBytes());
                }
            }
            return new Captured(reference, text);
        } catch (SandcrabClient.Rejected missing) {
            if (GameSandboxJob.missing(missing, "FILE_NOT_FOUND")) {
                return new Captured(null, null);
            }
            requireRegular(path, missing);
            throw missing;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    NativePlanFile candidate(
            AuthPrincipal actor,
            WorkspaceId workspace,
            AccountCommand held,
            NativeRepositoryLease lease,
            UUID plan,
            PendingWorkspaceSync.File selected,
            Captured original) {
        if (!selected.install()) {
            return original.file();
        }
        if (selected.delete()) {
            return null;
        }
        if (selected.text() != null) {
            return retain(held, lease, plan, selected.text().getBytes(StandardCharsets.UTF_8));
        }
        RepositorySyncEntry blob = selected.blob();
        var reference = new NativePlanFile(blob.bytes(), blob.sha256());
        held.publishPlanFile(
                plan,
                reference,
                output -> saves.repository().copyBlob(actor, workspace, blob, output),
                lease::authorize);
        return reference;
    }

    NativePlanFile retain(AccountCommand held, NativeRepositoryLease lease, UUID plan, byte[] bytes) {
        var reference = new NativePlanFile(bytes.length, SandcrabProtocol.sha256(bytes));
        held.publishPlanFile(plan, reference, output -> output.write(bytes), lease::authorize);
        return reference;
    }

    Optional<String> index(NativeRepositoryLease lease) {
        return files.optional(lease, RepositoryMediaIndex.PATH, RepositoryMediaIndex.MAX_BYTES)
                .map(bytes -> {
                    String value = text(bytes);
                    if (value == null) {
                        throw new IllegalArgumentException("The local media index must be UTF-8 text");
                    }
                    return value;
                });
    }

    byte[] read(AccountCommand held, NativeRepositoryLease lease, UUID plan, NativePlanFile file, int maximum) {
        ProtocolValues.inRange(file.bytes(), 0, maximum, "plan definition bytes");
        var output = new ByteArrayOutputStream((int) file.bytes());
        try {
            held.copyPlanFile(plan, file, output, lease::authorize);
            return output.toByteArray();
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    void prepareDirectories(NativeRepositoryLease lease, UUID plan) {
        String source = """
                /usr/bin/python3 -I - %s <<'POKETTO_PLAN_DIRS'
                import errno, os, sys
                flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
                def directory(parent, name):
                    try:
                        os.mkdir(name, 0o700, dir_fd=parent)
                    except FileExistsError:
                        pass
                    return os.open(name, flags, dir_fd=parent)

                parent = os.open('/workspace', flags)
                try:
                    for name in ('work', 'repository'):
                        parent = os.open(name, flags, dir_fd=parent)
                    for name in ('.poketto', 'plans', sys.argv[1]):
                        parent = directory(parent, name)
                    for name in ('original', 'candidate'):
                        os.close(directory(parent, name))
                except OSError as error:
                    if error.errno in (errno.ENOTDIR, errno.ELOOP):
                        sys.exit(20)
                    raise
                POKETTO_PLAN_DIRS""".formatted(plan);
        int exit = lease.runMaintenance(source);
        if (exit == 20) {
            throw new UnsupportedPath(".poketto/plans/" + plan);
        }
        if (exit != 0) {
            throw new WorkerUnavailableException(
                    new IllegalStateException("Preparing plan directories exited " + exit));
        }
    }

    void stage(AccountCommand held, NativeRepositoryLease lease, NativeRepositoryPlan plan, String path) {
        NativeRepositoryPlan.File file = plan.files().get(path);
        materialize(held, lease, plan.id(), file.original(), destination(plan.id(), "original", file.original()));
        materialize(held, lease, plan.id(), file.candidate(), destination(plan.id(), "candidate", file.candidate()));
    }

    void materialize(
            AccountCommand held, NativeRepositoryLease lease, UUID plan, NativePlanFile file, String destination) {
        if (file != null
                && !files.install(
                        lease,
                        destination,
                        file.bytes(),
                        file.sha256(),
                        output -> held.copyPlanFile(plan, file, output, lease::authorize))) {
            throw new IllegalArgumentException(
                    "A different local file occupies the plan destination; preserve it and choose another path");
        }
    }

    String observed(NativeRepositoryLease lease, String path) {
        try (NativeTransferStore.File captured = files.capture(lease, path, NativeArtifacts.MAX_FILE)) {
            lease.authorize();
            return captured.sha256();
        } catch (SandcrabClient.Rejected missing) {
            if (GameSandboxJob.missing(missing, "FILE_NOT_FOUND")) {
                return "absent";
            }
            requireRegular(path, missing);
            throw missing;
        }
    }

    private static void requireRegular(String path, SandcrabClient.Rejected refused) {
        if (Set.of("NOT_REGULAR_FILE", "NOT_DIRECTORY", "INVALID_LINK").contains(refused.code())) {
            throw new UnsupportedPath(path);
        }
    }

    static final class UnsupportedPath extends IllegalArgumentException {
        private final String path;

        UnsupportedPath(String path) {
            super("A path in the working copy has an incompatible type");
            this.path = path;
        }

        String path() {
            return path;
        }
    }

    static String destination(UUID plan, String version, NativePlanFile file) {
        return file == null ? null : ".poketto/plans/" + plan + "/" + version + "/" + file.sha256();
    }

    private static String text(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return value.indexOf('\0') < 0 ? value : null;
        } catch (CharacterCodingException binary) {
            return null;
        }
    }
}
