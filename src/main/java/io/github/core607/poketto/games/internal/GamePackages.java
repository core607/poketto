package io.github.core607.poketto.games.internal;

import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.RepositoryBlob;
import io.github.core607.poketto.content.RepositoryBlobReader;
import io.github.core607.poketto.games.GameBundle;
import io.github.core607.poketto.games.GameException;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Cache-only reads at one server-selected public snapshot; never resolves author paths on the host. */
final class GamePackages {
    private final RepositoryBlobReader blobs;
    private final ObjectMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    GamePackages(RepositoryBlobReader blobs) {
        this.blobs = blobs;
    }

    Optional<Package> find(PublicContentSnapshot snapshot, PublicArticle article) {
        if (snapshot.commit().isEmpty()) {
            return Optional.empty();
        }
        String manifestPath = article.repositoryPath() + ".game.json";
        Optional<RepositoryBlob> descriptor =
                blobs.find(snapshot.workspaceId(), snapshot.commit().orElseThrow(), manifestPath);
        if (descriptor.isEmpty() || !descriptor.orElseThrow().publicPath()) {
            return Optional.empty();
        }
        if (article.articleId() == null) {
            throw new GameException("INVALID_PACKAGE", "The introduction article requires a valid article ID");
        }
        byte[] bytes = read(descriptor.orElseThrow(), 16 * 1024);
        GameManifest manifest = json.readValue(bytes, GameManifest.class);
        if (!manifest.articleId().equals(article.articleId())) {
            throw new GameException("INVALID_PACKAGE", "Game manifest must name its introduction article ID");
        }
        String directory = manifestPath.substring(0, manifestPath.lastIndexOf('/') + 1);
        var budget = new Budget(bytes.length);
        GameBundle bundle = bundle(snapshot, directory, manifest, budget);
        byte[] encoded = json.writeValueAsBytes(new Versioned(manifest, bundle));
        if (encoded.length > GameBundle.MAX_BYTES) {
            throw new GameException("INVALID_PACKAGE", "Encoded game package exceeds 384 KiB");
        }
        return Optional.of(new Package(DocumentRevision.sha256(encoded).value(), manifest.help(), bundle));
    }

    private GameBundle bundle(PublicContentSnapshot snapshot, String directory, GameManifest manifest, Budget budget) {
        String source = text(file(snapshot, directory + manifest.entry(), budget));
        String presentation = manifest.presentation() == null
                ? null
                : text(file(snapshot, directory + manifest.presentation(), budget));
        var resources = new TreeMap<String, GameBundle.Resource>();
        for (String path : manifest.resources()) {
            byte[] bytes = file(snapshot, directory + path, budget);
            resources.put(
                    path,
                    new GameBundle.Resource(type(path), Base64.getEncoder().encodeToString(bytes)));
        }
        return new GameBundle(manifest.protocol(), source, presentation, resources);
    }

    private byte[] file(PublicContentSnapshot snapshot, String path, Budget budget) {
        WorkspaceId workspace = snapshot.workspaceId();
        RepositoryBlob descriptor = blobs.find(workspace, snapshot.commit().orElseThrow(), path)
                .filter(RepositoryBlob::publicPath)
                .orElseThrow(
                        () -> new GameException("INVALID_PACKAGE", "A declared game file is missing or not public"));
        budget.add(descriptor.size());
        return read(descriptor, 256 * 1024);
    }

    private byte[] read(RepositoryBlob descriptor, int maximum) {
        if (descriptor.size() > maximum) {
            throw new GameException("INVALID_PACKAGE", "Game file exceeds its byte limit");
        }
        byte[] result = blobs.read(descriptor);
        if (result.length != descriptor.size() || result.length > maximum) {
            throw new GameException("INVALID_PACKAGE", "Game file does not match its descriptor");
        }
        return result;
    }

    private static String text(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new GameException("INVALID_PACKAGE", "Game module must use UTF-8", failure);
        }
    }

    private static String type(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        return name.endsWith(".txt") ? "text/plain" : "application/octet-stream";
    }

    record Package(String version, String help, GameBundle bundle) {}

    private record Versioned(GameManifest manifest, GameBundle bundle) {}

    private static final class Budget {
        private long bytes;

        Budget(long initial) {
            bytes = initial;
        }

        void add(long size) {
            bytes += size;
            if (bytes > GameBundle.MAX_BYTES) {
                throw new GameException("INVALID_PACKAGE", "Game package exceeds 384 KiB");
            }
        }
    }
}
