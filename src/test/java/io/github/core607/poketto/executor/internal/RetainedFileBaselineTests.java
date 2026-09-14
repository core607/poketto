package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RetainedFileBaselineTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COMMIT = "a".repeat(40);
    private final WorkspaceId workspace = WorkspaceId.random();

    @Test
    void roundTripsTextNonTextAndAbsenceWithTheirCompleteMetadata() {
        var binary = new RepositoryFile(
                workspace,
                Optional.of(COMMIT),
                "one.bin",
                false,
                Optional.empty(),
                Optional.of(DocumentRevision.sha256(new byte[] {0, 1, -1})),
                List.of(new RepositoryDiagnostic("one.bin", "INVALID_UTF8", "not UTF-8")),
                false);
        var managed = new RepositoryFile(
                workspace,
                Optional.of(COMMIT),
                "one.bin",
                false,
                Optional.empty(),
                Optional.empty(),
                List.of(new RepositoryDiagnostic("one.bin", "MANAGED_MEDIA", "indexed original")),
                true);
        var absent = new RepositoryFile(
                workspace, Optional.of(COMMIT), "one.bin", true, Optional.empty(), Optional.empty(), List.of(), false);
        var text = RetainedFileBaseline.saved(COMMIT, "正文😸").file(workspace, "one.bin");
        for (var file : List.of(binary, managed, absent, text)) {
            var original = RetainedFileBaseline.capture(workspace, COMMIT, "one.bin", file);
            var restored = JSON.readValue(JSON.writeValueAsBytes(original), RetainedFileBaseline.class);
            assertThat(restored.file(workspace, "one.bin")).isEqualTo(file);
            assertThat(restored.content() instanceof RetainedFileBaseline.Absent)
                    .isEqualTo(file.expectedAbsence());
        }
        assertThat(RetainedFileBaseline.capture(workspace, COMMIT, "one.bin", binary)
                        .content())
                .isInstanceOf(RetainedFileBaseline.NonText.class);
        assertThat(RetainedFileBaseline.capture(workspace, COMMIT, "one.bin", managed)
                        .content())
                .isInstanceOf(RetainedFileBaseline.NonText.class);
    }

    @Test
    void validatesCaptureIdentityTextRevisionAndDiagnosticPath() {
        var file = RetainedFileBaseline.saved(COMMIT, "text").file(workspace, "one.md");
        assertThatThrownBy(() -> RetainedFileBaseline.capture(WorkspaceId.random(), COMMIT, "one.md", file))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetainedFileBaseline.capture(workspace, "b".repeat(40), "one.md", file))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetainedFileBaseline.capture(workspace, COMMIT, "other.md", file))
                .isInstanceOf(IllegalArgumentException.class);
        var badRevision = new RepositoryFile(
                workspace,
                file.commit(),
                file.path(),
                false,
                file.source(),
                Optional.of(DocumentRevision.sha256(new byte[] {0})),
                List.of(),
                false);
        assertThatThrownBy(() -> RetainedFileBaseline.capture(workspace, COMMIT, "one.md", badRevision))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("revision");
        var wrongPath = new RetainedFileBaseline(
                COMMIT,
                new RetainedFileBaseline.NonText(Optional.empty()),
                List.of(new RepositoryDiagnostic("elsewhere.bin", "MANAGED_MEDIA", "indexed")),
                false);
        assertThatThrownBy(() -> wrongPath.file(workspace, "one.bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file path");
    }

    @Test
    void unknownOrMissingVariantNeverBecomesAnAbsentFile() {
        var baseline = RetainedFileBaseline.saved(COMMIT, null);
        String encoded = JSON.writeValueAsString(baseline);
        assertThatThrownBy(() -> JSON.readValue(encoded.replace("absent", "unknown"), RetainedFileBaseline.class))
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> JSON.readValue(encoded.replace("\"kind\":\"absent\"", ""), RetainedFileBaseline.class))
                .hasMessageContaining("kind");
    }

    @Test
    void retainedStateRequiresEveryAdvancedPathAndChecksDiagnosticBinding() {
        var state = new SelectedFileSaves.State("b".repeat(40));
        assertThatThrownBy(() -> state.acknowledgeMove(COMMIT, Set.of("one.bin"), Map.of()))
                .hasMessageContaining("must cover affected paths");
        var nonText = new RetainedFileBaseline(
                COMMIT,
                new RetainedFileBaseline.NonText(Optional.empty()),
                List.of(new RepositoryDiagnostic("other.bin", "MANAGED_MEDIA", "indexed")),
                false);
        assertThatThrownBy(() -> state.acknowledgeMove(COMMIT, Set.of("one.bin"), Map.of("one.bin", nonText)))
                .hasMessageContaining("file path");
    }
}
