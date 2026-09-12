package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.ContentExportException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PortableExportsPlatformTests {
    @TempDir
    Path root;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unsupportedPosixPermissionsReturnUnavailableBeforeReadingContent() {
        var actor = mock(AuthPrincipal.class);
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.ACCOUNT);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        var planner = mock(PortableContentPlanner.class);
        try (var service = new LocalPortableContentExports(
                mock(AuthService.class),
                planner,
                root.resolve("exports"),
                Clock.systemUTC(),
                new LocalPortableContentExports.Limits(
                        1048576, 2097152, 1048576, 2, Duration.ofMinutes(1), Duration.ofSeconds(5)))) {
            assertThatThrownBy(() ->
                            service.create(actor, WorkspaceId.random(), List.of("article.md"), false, Optional.empty()))
                    .isInstanceOfSatisfying(
                            ContentExportException.class,
                            error -> assertThat(error.reason()).isEqualTo(ContentExportException.Reason.UNAVAILABLE));
            verifyNoInteractions(planner);
        }
    }
}
