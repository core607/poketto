package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.ManagedAsset;
import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.assets.ManagedBlobStore;
import io.github.core607.poketto.assets.ManagedImage;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicImageGrantCapacityTests {
    @TempDir
    Path directory;

    @Test
    void saturatedAuthorizationStillReturnsTheArticleWithoutAnUnapprovedImageUrl() throws Exception {
        var workspace = new Workspace(io.github.core607.poketto.workspace.WorkspaceId.random(), "Public fixture");
        var catalog = mock(WorkspaceCatalog.class);
        when(catalog.defaultWorkspace()).thenReturn(workspace);
        Instant at = Instant.parse("2026-09-07T00:00:00Z");
        var reference = new ManagedAssetReference(UUID.randomUUID(), "a".repeat(64));
        String body = "# Still readable\n![Unavailable image](" + reference + ")";
        List<PublicArticle> articles = new ArrayList<>();
        for (int i = 0; i < 129; i++) {
            articles.add(new PublicArticle(
                    "article-" + i + ".md", "/article-" + i, "Still readable", body, List.of(), at, at, false));
        }
        var snapshot = new PublicContentSnapshot(
                workspace.id(), Optional.of("b".repeat(40)), at, at.plusSeconds(3600), articles);
        var snapshots = mock(PublicContentSnapshots.class);
        when(snapshots.withCurrent(any(), any()))
                .thenAnswer(call -> ((Function<PublicContentSnapshot, ?>) call.getArgument(1)).apply(snapshot));
        var store = mock(ManagedBlobStore.class);
        when(store.read(workspace.id(), reference))
                .thenReturn(new ManagedImage(new ManagedAsset(reference, "image/png", 1), new byte[] {1}));
        var service = new AssetService(
                null,
                null,
                null,
                null,
                snapshots,
                () -> store,
                directory.toAbsolutePath(),
                16L * 1024 * 1024,
                128,
                Clock.fixed(at, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PublicDocumentController(new PublicDocuments(snapshots, catalog, service)))
                .setControllerAdvice(new ProblemResponses())
                .build();
        for (int i = 0; i < 128; i++) {
            mvc.perform(get("/api/public/document").param("route", "/article-" + i))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.images.length()").value(1));
        }
        String response = mvc.perform(get("/api/public/document").param("route", "/article-128"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("Still readable"))
                .andExpect(jsonPath("$.body").value(body))
                .andExpect(jsonPath("$.images").isEmpty())
                .andExpect(jsonPath("$.gallery").isEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(response).doesNotContain("/api/public/assets/", "workspaceId", "repositoryPath", "diagnostics");
        mvc.perform(get("/api/public/document").param("route", "/article-0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1));
    }
}
