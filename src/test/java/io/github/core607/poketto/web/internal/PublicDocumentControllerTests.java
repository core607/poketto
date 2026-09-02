package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.ContentRepositoryStore;
import io.github.core607.poketto.content.DocumentContent;
import io.github.core607.poketto.content.DocumentId;
import io.github.core607.poketto.content.DocumentMetadata;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.DocumentVisibility;
import io.github.core607.poketto.content.RepositoryWriteAmbiguousException;
import io.github.core607.poketto.content.StoredDocument;
import io.github.core607.poketto.workspace.Workspace;
import io.github.core607.poketto.workspace.WorkspaceCatalog;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(PublicDocumentController.class)
@Import(PublicDocumentControllerTests.Fakes.class)
class PublicDocumentControllerTests {

    private static final Workspace DEFAULT_WORKSPACE = new Workspace(WorkspaceId.random(), "Default workspace");
    private static final DocumentId OLDER = DocumentId.parse("11111111-1111-4111-8111-111111111111");
    private static final DocumentId NEWER = DocumentId.parse("22222222-2222-4222-8222-222222222222");
    private static final DocumentId PRIVATE = DocumentId.parse("33333333-3333-4333-8333-333333333333");
    private static final DocumentId UNKNOWN = DocumentId.parse("44444444-4444-4444-8444-444444444444");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FakeStore store;

    @BeforeEach
    void resetStore() {
        store.documents = List.of(
                document(OLDER, "Older", DocumentVisibility.PUBLIC, Optional.of(Instant.parse("2026-09-01T00:00:00Z"))),
                document(PRIVATE, "Private", DocumentVisibility.PRIVATE, Optional.empty()),
                document(
                        NEWER, "Newer", DocumentVisibility.PUBLIC, Optional.of(Instant.parse("2026-09-02T00:00:00Z"))));
        store.failure = null;
        store.scannedWorkspaces.clear();
    }

    @Test
    void listsOnlyPublicDocumentsNewestPublicationFirst() throws Exception {
        mvc.perform(get("/api/public/documents"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(NEWER.toString()))
                .andExpect(jsonPath("$[0].publishedAt").value("2026-09-02T00:00:00Z"))
                .andExpect(jsonPath("$[1].id").value(OLDER.toString()))
                .andExpect(jsonPath("$[0].title").value("Newer"))
                .andExpect(jsonPath("$[1].title").value("Older"))
                .andExpect(jsonPath("$[0].body").doesNotExist());

        assertThat(store.scannedWorkspaces).containsExactly(DEFAULT_WORKSPACE.id());
    }

    @Test
    void returnsThePublicDocumentBody() throws Exception {
        mvc.perform(get("/api/public/documents/" + OLDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(OLDER.toString()))
                .andExpect(jsonPath("$.title").value("Older"))
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("notes"))
                .andExpect(jsonPath("$.body").value("Body of Older"));
    }

    @Test
    void privateUnknownAndMalformedIdsAreIndistinguishable() throws Exception {
        String privateProblem = problemBody("/api/public/documents/" + PRIVATE, 404);
        String unknownProblem = problemBody("/api/public/documents/" + UNKNOWN, 404);
        String malformedProblem = problemBody("/api/public/documents/not-a-uuid", 404);

        assertThat(privateProblem).contains("\"title\":\"Not found\"").doesNotContain("Private");
        assertThat(privateProblem.replace(PRIVATE.toString(), "ID"))
                .isEqualTo(unknownProblem.replace(UNKNOWN.toString(), "ID"))
                .isEqualTo(malformedProblem.replace("not-a-uuid", "ID"));
    }

    @Test
    void hidesRepositoryDiagnosticsBehindAServiceUnavailableProblem() throws Exception {
        store.failure = new ContentRepositoryException(
                "workspace " + DEFAULT_WORKSPACE.id() + " repository authority: remote unreachable");

        String problem = problemBody("/api/public/documents", 503);

        assertThat(problem)
                .contains("\"title\":\"Repository unavailable\"")
                .contains("\"detail\":\"the content repository is unavailable\"")
                .doesNotContain(DEFAULT_WORKSPACE.id().toString())
                .doesNotContain("unreachable");
    }

    @Test
    void reportsAnUnverifiedWriteWithoutInvitingABlindRetry() throws Exception {
        store.failure = new RepositoryWriteAmbiguousException("workspace x remote write response was lost");

        String problem = problemBody("/api/public/documents", 503);

        assertThat(problem)
                .contains("\"title\":\"Write outcome unknown\"")
                .contains("re-read before retrying")
                .doesNotContain("workspace x");
    }

    @Test
    void hidesUnexpectedFailuresBehindAnInternalServerErrorProblem() throws Exception {
        store.failure = new IllegalStateException("private server diagnostic");

        String problem = problemBody("/api/public/documents", 500);

        assertThat(problem)
                .contains("\"title\":\"Internal server error\"")
                .contains("\"detail\":\"an unexpected error occurred\"")
                .doesNotContain("private server diagnostic");
    }

    @Test
    void unknownRoutesRenderAsProblems() throws Exception {
        mvc.perform(get("/api/public/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void springMvcFailuresKeepTheirSpecificStatus() throws Exception {
        mvc.perform(post("/api/public/documents"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405));
    }

    private String problemBody(String path, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static StoredDocument document(
            DocumentId id, String title, DocumentVisibility visibility, Optional<Instant> publishedAt) {
        Instant created = Instant.parse("2026-08-30T00:00:00Z");
        DocumentMetadata metadata = new DocumentMetadata(
                id, title, visibility, List.of("notes"), created, publishedAt.orElse(created), publishedAt);
        byte[] bytes = ("Body of " + title).getBytes(StandardCharsets.UTF_8);
        return new StoredDocument(
                "documents/" + title.toLowerCase() + ".md",
                new DocumentContent(metadata, "Body of " + title),
                DocumentRevision.sha256(bytes));
    }

    static final class FakeStore implements ContentRepositoryStore {

        private final List<WorkspaceId> scannedWorkspaces = new java.util.ArrayList<>();
        private List<StoredDocument> documents = List.of();
        private RuntimeException failure;

        @Override
        public void ensureReady(WorkspaceId workspaceId) {}

        @Override
        public List<StoredDocument> scan(WorkspaceId workspaceId) {
            scannedWorkspaces.add(workspaceId);
            if (failure != null) {
                throw failure;
            }
            return documents;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        FakeStore fakeStore() {
            return new FakeStore();
        }

        @Bean
        WorkspaceCatalog workspaceCatalog() {
            return new WorkspaceCatalog() {
                @Override
                public Workspace defaultWorkspace() {
                    return DEFAULT_WORKSPACE;
                }

                @Override
                public Optional<Workspace> findById(WorkspaceId workspaceId) {
                    return DEFAULT_WORKSPACE.id().equals(workspaceId)
                            ? Optional.of(DEFAULT_WORKSPACE)
                            : Optional.empty();
                }
            };
        }

        @Bean
        PublicDocuments publicDocuments(FakeStore store, WorkspaceCatalog workspaces) {
            return new PublicDocuments(store, workspaces);
        }
    }
}
