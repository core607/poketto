package io.github.core607.poketto.capture.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.capture.CaptureInbox;
import io.github.core607.poketto.capture.CaptureLimitException;
import io.github.core607.poketto.content.RepositoryConflictException;
import io.github.core607.poketto.content.RepositoryContentReader;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryPatch;
import io.github.core607.poketto.content.RepositoryPatchResult;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RepositoryCaptureInboxTests {
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final UUID sender = UUID.randomUUID();
    private final AuthService auth = mock(AuthService.class);
    private final RepositoryContentReader reader = mock(RepositoryContentReader.class);
    private final RepositoryPatchService patches = mock(RepositoryPatchService.class);
    private final AssetService assets = mock(AssetService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T07:20:31Z"), ZoneOffset.UTC);
    private final List<RepositoryPatch> written = new ArrayList<>();

    private RepositoryCaptureInbox inbox(int perMinute) {
        when(actor.accountId()).thenReturn(sender);
        when(patches.apply(eq(actor), eq(workspace), any(RepositoryPatch.class)))
                .thenAnswer(invocation -> {
                    written.add(invocation.getArgument(2));
                    return new RepositoryPatchResult("b".repeat(40), true, false, Map.of());
                });
        return new RepositoryCaptureInbox(
                auth,
                reader,
                patches,
                assets,
                new CaptureLimits(perMinute, 500, 100, clock),
                JsonMapper.builder().build(),
                clock);
    }

    @Test
    void aCaptureBecomesOnePrivateInboxNoteWithAnEscapedHeader() {
        absent("a".repeat(40));
        var captured = inbox(30)
                .capture(
                        actor,
                        workspace,
                        new CaptureInbox.Capture(
                                "Title with \"quotes\"\nand: colon",
                                "https://example.com/a?b=1",
                                "line one\n\nline two",
                                "我的备注"),
                        Optional.empty());

        assertThat(captured.path()).isEqualTo("private/inbox/2026-09-24-0720-title-with-quotes-and-colon.md");
        verify(auth).authorize(actor, workspace, Capability.CAPTURE);
        var change = written.getFirst().changes().getFirst();
        assertThat(written.getFirst().baseCommit()).contains("a".repeat(40));
        assertThat(change.expectedAbsence()).isTrue();
        assertThat(change.content().orElseThrow())
                .matches("(?s)---\nid: [0-9a-f-]{36}\n.*")
                .contains("title: \"Title with \\\"quotes\\\" and: colon\"\n")
                .contains("source: \"https://example.com/a?b=1\"\n")
                .contains("saved: 2026-09-24T07:20:31Z\n---\n")
                .contains("\n> line one\n>\n> line two\n")
                .endsWith("\n我的备注\n");
    }

    @Test
    void anImageIsStoredFirstAndReferencedFromTheNote() {
        absent("a".repeat(40));
        var reference = new ManagedAssetReference(UUID.randomUUID(), "c".repeat(64));
        when(assets.uploadCaptured(eq(actor), eq(workspace), anyString(), any(InputStream.class)))
                .thenReturn(new ManagedAsset(reference, "image/png", 10));
        inbox(30)
                .capture(
                        actor,
                        workspace,
                        new CaptureInbox.Capture("", "", "", ""),
                        Optional.of(new ByteArrayInputStream(new byte[] {1})));

        assertThat(written.getFirst().changes().getFirst().path()).isEqualTo("private/inbox/2026-09-24-0720-收集.md");
        assertThat(written.getFirst().changes().getFirst().content().orElseThrow())
                .endsWith("![图片](managed:" + reference.assetId() + ":" + "c".repeat(64) + ")\n");
    }

    @Test
    void takenNamesGetSuffixesAndAMovedRemoteIsRetried() {
        String stem = "private/inbox/2026-09-24-0720-example-com";
        when(reader.getFile(workspace, Optional.empty(), stem + ".md"))
                .thenReturn(file(stem + ".md", false, "a".repeat(40)))
                .thenReturn(file(stem + ".md", false, "d".repeat(40)));
        when(reader.getFile(workspace, Optional.of("a".repeat(40)), stem + "-2.md"))
                .thenReturn(file(stem + "-2.md", true, "a".repeat(40)));
        when(reader.getFile(workspace, Optional.of("d".repeat(40)), stem + "-2.md"))
                .thenReturn(file(stem + "-2.md", false, "d".repeat(40)));
        when(reader.getFile(workspace, Optional.of("d".repeat(40)), stem + "-3.md"))
                .thenReturn(file(stem + "-3.md", true, "d".repeat(40)));
        var inbox = inbox(30);
        when(patches.apply(eq(actor), eq(workspace), any(RepositoryPatch.class)))
                .thenThrow(new RepositoryConflictException("remote moved"))
                .thenReturn(new RepositoryPatchResult("e".repeat(40), true, false, Map.of()));

        var captured = inbox.capture(
                actor, workspace, new CaptureInbox.Capture("", "https://example.com/x", "", ""), Optional.empty());
        assertThat(captured).isEqualTo(new CaptureInbox.Captured(stem + "-3.md", "e".repeat(40)));
    }

    @Test
    void emptyCapturesAndExhaustedSendersStoreNothing() {
        absent("a".repeat(40));
        var inbox = inbox(1);
        assertThatThrownBy(() ->
                        inbox.capture(actor, workspace, new CaptureInbox.Capture("", "", "", ""), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        inbox.capture(actor, workspace, new CaptureInbox.Capture("", "", "", "one"), Optional.empty());
        assertThatThrownBy(() ->
                        inbox.capture(actor, workspace, new CaptureInbox.Capture("", "", "", "two"), Optional.empty()))
                .isInstanceOf(CaptureLimitException.class);
        assertThat(written).hasSize(1);
        verify(assets, never()).uploadCaptured(any(), any(), anyString(), any());
        assertThatThrownBy(() -> new CaptureInbox.Capture("", "javascript:alert(1)", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void slugsKeepLettersAndDigitsOnly() {
        assertThat(RepositoryCaptureInbox.slug("雨后 的 数学课!")).isEqualTo("雨后-的-数学课");
        assertThat(RepositoryCaptureInbox.slug("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(RepositoryCaptureInbox.slug("!!!")).isEqualTo("note");
        assertThat(RepositoryCaptureInbox.slug("字".repeat(60))).hasSize(40);
        assertThat(Set.of("a-b")).contains(RepositoryCaptureInbox.slug("A  b"));
    }

    private void absent(String commit) {
        when(reader.getFile(eq(workspace), eq(Optional.empty()), anyString()))
                .thenAnswer(invocation -> file(invocation.getArgument(2), true, commit));
    }

    private RepositoryFile file(String path, boolean absent, String commit) {
        return new RepositoryFile(
                workspace,
                Optional.of(commit),
                path,
                absent,
                absent ? Optional.empty() : Optional.of("# taken"),
                Optional.empty(),
                List.of(),
                false);
    }
}
