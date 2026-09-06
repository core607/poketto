package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicDocumentController.class)
@Import(PublicDocumentControllerTests.Fakes.class)
class PublicDocumentControllerTests {
    private static final Workspace DEFAULT = new Workspace(WorkspaceId.random(), "Default");
    private static final Instant VERIFIED = Instant.parse("2026-09-05T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeSnapshots snapshots;

    @BeforeEach
    void reset() {
        snapshots.failure = null;
        snapshots.calls = 0;
    }

    @Test
    void listsPublicSummariesWithSnapshotIdentityAndNoRawMetadata() throws Exception {
        String body = mvc.perform(get("/api/public/documents"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].route").value("/城市/雨"))
                .andExpect(jsonPath("$.commit").value("a".repeat(40)))
                .andExpect(jsonPath("$.verifiedAt").value(VERIFIED.toString()))
                .andExpect(
                        jsonPath("$.expiresAt").value(VERIFIED.plusSeconds(3600).toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body)
                .doesNotContain("repositoryPath", "source", "diagnostics", "workspaceId", "revision", "frontmatter");
        assertThat(snapshots.calls).isOne();
    }

    @Test
    void servesUnicodeRouteAndUninterpretedPublicBody() throws Exception {
        mvc.perform(get("/api/public/document").param("route", "/城市/雨"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Rain"))
                .andExpect(jsonPath("$.body").value("# Rain\n\nLiteral [.*] 知识"))
                .andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test
    void missingPrivateMalformedAndOldIdsRevealNoResourceDetails() throws Exception {
        for (String route : List.of("/private/secret", "/missing", "../private/secret")) {
            String body = mvc.perform(get("/api/public/document").param("route", route))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("public document not found"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body).doesNotContain(route, "secret");
        }
        mvc.perform(get("/api/public/documents/11111111-1111-4111-8111-111111111111"))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchesLiteralTextAndCombinesTagDateAndPaginationBounds() throws Exception {
        mvc.perform(get("/api/public/documents")
                        .param("query", "[.*]")
                        .param("tag", "notes")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-04T00:00:00Z")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].route").value("/城市/雨"));
        mvc.perform(get("/api/public/documents").param("query", ".*"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/public/documents").param("offset", "1").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].route").value("/older"));
        for (String limit : List.of("0", "101", "-1"))
            mvc.perform(get("/api/public/documents").param("limit", limit)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/documents").param("query", "a".repeat(201)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tagsComeFromTheSamePublicSnapshot() throws Exception {
        mvc.perform(get("/api/public/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("notes"))
                .andExpect(jsonPath("$.tags[1]").value("知识"));
        assertThat(snapshots.calls).isOne();
    }

    @Test
    void expiredOrInvalidSnapshotsReturnGenericServiceUnavailableEverywhere() throws Exception {
        snapshots.failure = new ContentRepositoryException("private workspace or policy diagnostic");
        for (String path : List.of("/api/public/documents", "/api/public/tags", "/api/public/document?route=/城市/雨")) {
            String body = mvc.perform(get(path))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(body).doesNotContain("private workspace", "policy diagnostic");
        }
    }

    @Test
    void frameworkFailuresRetainTheirHttpStatus() throws Exception {
        mvc.perform(post("/api/public/documents")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/public/unknown")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/document")).andExpect(status().isBadRequest());
    }

    static class FakeSnapshots implements PublicContentSnapshots {
        RuntimeException failure;
        int calls;

        @Override
        public void ensureReady(WorkspaceId workspace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new UnsupportedOperationException("public requests must not fetch");
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspace) {
            assertThat(workspace).isEqualTo(DEFAULT.id());
            calls++;
            if (failure != null) throw failure;
            return new PublicContentSnapshot(
                    workspace,
                    Optional.of("a".repeat(40)),
                    VERIFIED,
                    VERIFIED.plusSeconds(3600),
                    List.of(
                            new PublicArticle(
                                    "城市/雨.md",
                                    "/城市/雨",
                                    "Rain",
                                    "# Rain\n\nLiteral [.*] 知识",
                                    List.of("notes", "知识"),
                                    Instant.parse("2026-09-03T00:00:00Z"),
                                    VERIFIED,
                                    false),
                            new PublicArticle(
                                    "older.md",
                                    "/older",
                                    "Older",
                                    "Older body",
                                    List.of("notes"),
                                    Instant.parse("2026-09-01T00:00:00Z"),
                                    VERIFIED,
                                    false)));
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspace, java.util.function.Function<PublicContentSnapshot, T> action) {
            return action.apply(current(workspace));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        FakeSnapshots snapshots() {
            return new FakeSnapshots();
        }

        @Bean
        WorkspaceCatalog workspaceCatalog() {
            return new WorkspaceCatalog() {
                @Override
                public Workspace defaultWorkspace() {
                    return DEFAULT;
                }

                @Override
                public Optional<Workspace> findById(WorkspaceId id) {
                    return id.equals(DEFAULT.id()) ? Optional.of(DEFAULT) : Optional.empty();
                }
            };
        }

        @Bean
        PublicDocuments publicDocuments(FakeSnapshots snapshots, WorkspaceCatalog workspaces) {
            var assets = new io.github.core607.poketto.assets.AssetService(
                    org.mockito.Mockito.mock(io.github.core607.poketto.auth.AuthService.class),
                    org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryContentReader.class),
                    org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryBlobReader.class),
                    org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryMarkdownInspector.class),
                    snapshots,
                    () -> {
                        throw new IllegalStateException("no managed fixture");
                    },
                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "unused-public-images")
                            .toAbsolutePath(),
                    16L * 1024 * 1024,
                    128,
                    java.time.Clock.fixed(VERIFIED, java.time.ZoneOffset.UTC));
            return new PublicDocuments(snapshots, workspaces, assets);
        }
    }
}
