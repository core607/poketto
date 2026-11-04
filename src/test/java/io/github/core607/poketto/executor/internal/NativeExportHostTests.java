package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.JsonNode;

/** The real host dispatch and Git-backed ZIP service use a generic native-file installation peer. */
@EnabledOnOs(OS.LINUX)
class NativeExportHostTests extends NativePlanFixture {
    @BeforeEach
    void usePortablePackages() {
        when(lease.remaining()).thenReturn(Duration.ofSeconds(30));
        when(lease.transferLimit()).thenReturn(1_073_741_824L);
        var writer = new AccountCopyRecord.Writer(
                actor.subjectId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(record.writer()).thenReturn(writer);
        when(record.executionId()).thenReturn(UUID.randomUUID());
        host = new NativeRepositoryHost(
                saves,
                files,
                mock(NativeArtifactCommands.class),
                mock(NativeMediaCommands.class),
                commands,
                new NativeExportCommands(files, repository.packages(auth)));
    }

    @Test
    void hostExportsSavedBytesAndPreservesDifferentExistingFilesWithoutChangingBaselines() throws Exception {
        RetainedSaveState before = state.get();
        byte[] unsaved = "private unsaved draft".getBytes(StandardCharsets.UTF_8);
        local.put("private/secret.md", unsaved);
        JsonNode reply =
                call("export", new BridgeArguments.Export(List.of("private/secret.md"), "exports/saved.zip", false));
        assertThat(reply.path("ok").booleanValue()).isTrue();
        Map<String, String> archive = contents(local.get("exports/saved.zip"));
        assertThat(archive).containsEntry("content/private/secret.md", "current-secret-needle");
        assertThat(archive.values()).noneMatch(value -> value.contains("private unsaved draft"));
        assertThat(local.get("private/secret.md")).containsExactly(unsaved);
        assertThat(state.get()).isEqualTo(before);
        assertThat(repository.pushes()).isZero();
        assertThat(repository.retainedPackages()).isZero();
        local.put("exports/occupied.zip", unsaved);
        JsonNode collision =
                call("export", new BridgeArguments.Export(List.of("private/secret.md"), "exports/occupied.zip", false));
        assertThat(collision.path("code").stringValue()).isEqualTo("LOCAL_FILE_CHANGED");
        assertThat(local.get("exports/occupied.zip")).containsExactly(unsaved);
        assertThat(repository.retainedPackages()).isZero();
    }

    @Test
    void aPublicCopyForcesCurrentPublishedContentEvenWithoutThePublicFlag() throws Exception {
        RepositorySnapshotExports.PublicExport projection = repository.exports().createPublic(actor, workspace);
        var publicOwner = new AccountCopyRecord.Owner(account, workspace.value(), false);
        when(held.owner()).thenReturn(publicOwner);
        when(record.owner()).thenReturn(publicOwner);
        when(record.publicExport()).thenReturn(projection);
        capabilities.clear();
        capabilities.add(Capability.EXECUTE_REPOSITORY);
        try {
            JsonNode reply = call("export", new BridgeArguments.Export(List.of("."), "exports/public.zip", false));
            assertThat(reply.path("ok").booleanValue()).isTrue();
            assertThat(reply.at("/result/scope").stringValue()).isEqualTo("public");
            Map<String, String> archive = contents(local.get("exports/public.zip"));
            assertThat(archive.keySet()).noneMatch(path -> path.contains("private/") || path.endsWith("AGENTS.md"));
            assertThat(archive.values())
                    .noneMatch(value -> value.contains("current-secret-needle")
                            || value.contains("metadata-secret-needle")
                            || value.contains("comment-secret-needle"));
            JsonNode privateSelection = call(
                    "export", new BridgeArguments.Export(List.of("private/secret.md"), "exports/denied.zip", false));
            assertThat(privateSelection.path("ok").booleanValue()).isFalse();
            assertThat(local).doesNotContainKey("exports/denied.zip");
            assertThat(repository.retainedPackages()).isZero();
        } finally {
            repository.exports().release(projection.export().exportId());
        }
    }

    @Test
    void anUnknownInstallationRemainsUnconfirmedAndNeverAutomaticallyRepeatsTheTransfer() throws Exception {
        loseMaterialization.set(true);
        assertThatThrownBy(() -> call(
                        "export",
                        new BridgeArguments.Export(List.of("private/secret.md"), "exports/unknown.zip", false)))
                .isInstanceOf(WorkerUnavailableException.class);
        assertThat(contents(local.get("exports/unknown.zip")))
                .containsEntry("content/private/secret.md", "current-secret-needle");
        verify(files, times(1)).install(any(), anyString(), anyLong(), anyString(), any());
        assertThat(repository.retainedPackages()).isZero();
        assertThat(repository.pushes()).isZero();
    }

    private static Map<String, String> contents(byte[] zip) throws IOException {
        var entries = new HashMap<String, String>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return Map.copyOf(entries);
    }
}
