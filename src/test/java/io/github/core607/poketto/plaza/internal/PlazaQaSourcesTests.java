package io.github.core607.poketto.plaza.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.qa.QaSources;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Fixed query plans calibrate retrieval, not the model's ability to choose those queries. */
class PlazaQaSourcesTests {
    @Test
    void theSiteCorpusPinsTagsRephrasingCrossArticleEvidenceAndMissingAnswers() {
        Corpus corpus = JsonMapper.shared()
                .readValue(Path.of("acceptance/qa/corpus.json").toFile(), Corpus.class);
        QaSources sources = sources(corpus);
        for (Case scenario : corpus.cases()) {
            var found = new HashSet<String>();
            for (Query query : scenario.queries()) {
                QaSources.Page result = sources.search(query.query(), query.tag(), 0);
                result.items().forEach(item -> found.add(item.reference()));
            }
            assertThat(found).as(scenario.id()).containsAll(scenario.expected());
            if (scenario.expected().isEmpty()) {
                assertThat(found).isEmpty();
            }
            List<String> text = found.stream()
                    .map(reference -> sources.read(reference, 0).text())
                    .toList();
            for (String quote : scenario.quotes()) {
                assertThat(text).as(scenario.id()).anyMatch(value -> value.contains(quote));
            }
        }
        assertThat(sources.read("reading/paper", 0).text()).contains("spend_candy", "PRIVATE_QA_INJECTION_SENTINEL");
    }

    private static QaSources sources(Corpus corpus) {
        WorkspacePublications publications = mock(WorkspacePublications.class);
        var snapshots = new Snapshots();
        var catalogue = new ArrayList<WorkspacePublications.Publication>();
        Set<String> spaces =
                new HashSet<>(corpus.documents().stream().map(Document::space).toList());
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        for (String space : spaces) {
            var workspace = WorkspaceId.random();
            var publication =
                    new WorkspacePublications.Publication(workspace, space, space, true, true, "Fixture", "", false);
            catalogue.add(publication);
            when(publications.findPublished(space)).thenReturn(Optional.of(publication));
            when(publications.settings(workspace)).thenReturn(publication);
            List<PublicArticle> articles = corpus.documents().stream()
                    .filter(value -> value.space().equals(space))
                    .map(value -> new PublicArticle(
                            "public/" + value.slug() + ".md",
                            "/" + value.slug(),
                            value.title(),
                            value.body(),
                            value.tags(),
                            now,
                            now,
                            false,
                            "Fixture",
                            null,
                            false))
                    .toList();
            snapshots.values.put(
                    workspace,
                    new PublicContentSnapshot(
                            workspace, Optional.of("a".repeat(40)), now, now.plusSeconds(600), articles));
        }
        when(publications.publishedAfter(any(), anyInt())).thenReturn(catalogue);
        return new PlazaQaSources(new PublicPlazaReads(publications, snapshots));
    }

    private record Corpus(List<Document> documents, List<Case> cases) {}

    private record Document(String space, String slug, String title, List<String> tags, String body) {}

    private record Case(String id, String question, List<Query> queries, List<String> expected, List<String> quotes) {}

    private record Query(String query, String tag) {}

    private static final class Snapshots implements PublicContentSnapshots {
        private final Map<WorkspaceId, PublicContentSnapshot> values = new HashMap<>();

        @Override
        public void ensureReady(WorkspaceId workspace) {
            throw new AssertionError("QA cannot fetch Git");
        }

        @Override
        public PublicContentSnapshot refresh(WorkspaceId workspace) {
            throw new AssertionError("QA cannot refresh Git");
        }

        @Override
        public PublicContentSnapshot current(WorkspaceId workspace) {
            return values.get(workspace);
        }

        @Override
        public <T> T withCurrent(WorkspaceId workspace, Function<PublicContentSnapshot, T> action) {
            return action.apply(current(workspace));
        }
    }
}
