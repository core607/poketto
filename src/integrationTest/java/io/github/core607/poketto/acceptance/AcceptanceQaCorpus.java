package io.github.core607.poketto.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Optional fixed public corpus for the real application and model entrance; never part of production seeding. */
final class AcceptanceQaCorpus {
    private AcceptanceQaCorpus() {}

    static void seed(Path directory) throws IOException {
        String source = System.getenv("POKETTO_ACCEPTANCE_QA_CORPUS");
        if (source == null || source.isBlank()) {
            return;
        }
        Corpus corpus = JsonMapper.shared().readValue(Path.of(source).toFile(), Corpus.class);
        for (Document document : corpus.documents()) {
            if (!document.space().matches("[a-z-]+") || !document.slug().matches("[a-z-]+")) {
                throw new IllegalArgumentException("Invalid synthetic QA corpus path");
            }
            String relative = "public/qa/" + document.space() + "/" + document.slug() + ".md";
            Path target = directory.resolve(relative);
            Files.createDirectories(target.getParent());
            String text = "---\nid: " + UUID.nameUUIDFromBytes(relative.getBytes(StandardCharsets.UTF_8))
                    + "\ntitle: " + JsonMapper.shared().writeValueAsString(document.title())
                    + "\ntags: " + JsonMapper.shared().writeValueAsString(document.tags())
                    + "\ncreated_at: 2026-10-09T00:00:00Z\n---\n\n" + document.body() + "\n";
            Files.writeString(target, text);
        }
    }

    private record Corpus(List<Document> documents, JsonNode cases) {}

    private record Document(String space, String slug, String title, List<String> tags, String body) {}
}
