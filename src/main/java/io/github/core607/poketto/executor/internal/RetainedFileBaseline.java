package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.ContentLimits;
import io.github.core607.poketto.content.DocumentRevision;
import io.github.core607.poketto.content.RepositoryFile;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Exact acknowledged Git text; a null source records deletion, never an unreadable file. */
record RetainedFileBaseline(String commit, String source) {
    RetainedFileBaseline {
        commit = ProtocolValues.hex(commit, 40, "retained file commit");
        if (source != null) {
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

    RepositoryFile file(WorkspaceId workspace, String path) {
        Optional<String> text = Optional.ofNullable(source);
        return new RepositoryFile(
                workspace,
                Optional.of(commit),
                path,
                source == null,
                text,
                text.map(value -> DocumentRevision.sha256(value.getBytes(StandardCharsets.UTF_8))),
                List.of(),
                false);
    }
}
