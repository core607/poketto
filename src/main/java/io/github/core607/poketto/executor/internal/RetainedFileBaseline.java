package io.github.core607.poketto.executor.internal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryDiagnostic;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.content.RepositoryPaths;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An acknowledged file version distinguishes non-text presence from a known absence. */
record RetainedFileBaseline(
        String commit, Content content, List<RepositoryDiagnostic> diagnostics, boolean publicScope) {
    RetainedFileBaseline {
        commit = ProtocolValues.hex(commit, 40, "retained file commit");
        Objects.requireNonNull(content, "retained file content must be present");
        diagnostics = List.copyOf(diagnostics);
        for (var diagnostic : diagnostics) {
            Objects.requireNonNull(diagnostic.path(), "retained diagnostic path must be present");
            Objects.requireNonNull(diagnostic.code(), "retained diagnostic code must be present");
            Objects.requireNonNull(diagnostic.message(), "retained diagnostic message must be present");
        }
    }

    static RetainedFileBaseline saved(String commit, String source) {
        return new RetainedFileBaseline(commit, source == null ? new Absent() : new Text(source), List.of(), false);
    }

    static RetainedFileBaseline capture(WorkspaceId workspace, String commit, String path, RepositoryFile file) {
        Content content;
        if (file.expectedAbsence()) {
            content = new Absent();
        } else if (file.source().isPresent()) {
            content = new Text(file.source().orElseThrow());
        } else {
            content = new NonText(file.revision());
        }
        var retained = new RetainedFileBaseline(commit, content, file.diagnostics(), file.publicScope());
        ProtocolValues.require(
                retained.file(workspace, path).equals(file),
                "acknowledged file baseline",
                "must match its workspace, path, commit and revision");
        return retained;
    }

    void requirePath(String path) {
        RepositoryPaths.validate(path);
        for (var diagnostic : diagnostics) {
            ProtocolValues.require(diagnostic.path().equals(path), "retained diagnostic", "must match its file path");
        }
    }

    String source() {
        return content instanceof Text text ? text.source() : null;
    }

    RepositoryFile file(WorkspaceId workspace, String path) {
        requirePath(path);
        Optional<DocumentRevision> revision =
                switch (content) {
                    case Text text ->
                        Optional.of(DocumentRevision.sha256(text.source().getBytes(StandardCharsets.UTF_8)));
                    case NonText nonText -> nonText.revision();
                    case Absent ignored -> Optional.empty();
                };
        return new RepositoryFile(
                workspace,
                Optional.of(commit),
                path,
                content instanceof Absent,
                Optional.ofNullable(source()),
                revision,
                diagnostics,
                publicScope);
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Text.class, name = "text"),
        @JsonSubTypes.Type(value = NonText.class, name = "nontext"),
        @JsonSubTypes.Type(value = Absent.class, name = "absent")
    })
    sealed interface Content permits Text, NonText, Absent {}

    record Text(String source) implements Content {
        Text {
            Objects.requireNonNull(source, "retained text source must be present");
            ProtocolValues.require(
                    source.length() <= ContentLimits.MAX_DOCUMENT_BYTES,
                    "retained file source",
                    "exceeds the text length limit");
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            ProtocolValues.require(
                    bytes.length <= ContentLimits.MAX_DOCUMENT_BYTES,
                    "retained file source",
                    "exceeds the text byte limit");
            ProtocolValues.require(
                    source.indexOf('\0') < 0 && source.equals(new String(bytes, StandardCharsets.UTF_8)),
                    "retained file source",
                    "must be UTF-8 text without NUL");
        }
    }

    record NonText(Optional<DocumentRevision> revision) implements Content {
        NonText {
            Objects.requireNonNull(revision, "retained non-text revision option must be present");
        }
    }

    record Absent() implements Content {}
}
