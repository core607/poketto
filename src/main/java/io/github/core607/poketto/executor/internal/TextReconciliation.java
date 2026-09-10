package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.ContentLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeFormatter;

final class TextReconciliation {
    private static final int MAX_BYTES = 4 * ContentLimits.MAX_DOCUMENT_BYTES;

    static Result merge(Optional<String> base, Optional<String> local, Optional<String> remote) {
        for (var text : List.of(base, local, remote)) {
            if (text.orElse("").getBytes(StandardCharsets.UTF_8).length > ContentLimits.MAX_DOCUMENT_BYTES)
                throw new IllegalArgumentException("text reconciliation input exceeds one document");
        }
        if (local.equals(remote)) return new Result(local, false);
        if (base.equals(local)) return new Result(remote, false);
        if (base.equals(remote)) return new Result(local, false);
        // Absence differs from an existing empty file. Add/add and modify/delete
        // conflicts retain all versions instead of collapsing either to an empty sequence.
        if (base.isEmpty() || local.isEmpty() || remote.isEmpty()) {
            String merged = "<<<<<<< LOCAL\n" + terminated(local) + "||||||| BASE\n" + terminated(base) + "=======\n"
                    + terminated(remote) + ">>>>>>> REMOTE\n";
            return new Result(Optional.of(merged), true);
        }
        var diff = new HistogramDiff();
        // Highly repetitive input becomes a larger conflict rather than an unbounded fallback diff.
        diff.setFallbackAlgorithm(null);
        var merged = new MergeAlgorithm(diff)
                .merge(
                        RawTextComparator.DEFAULT,
                        text(base.orElseThrow()),
                        text(local.orElseThrow()),
                        text(remote.orElseThrow()));
        var output = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(int value) {
                bounded(1);
                super.write(value);
            }

            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                bounded(length);
                super.write(bytes, offset, length);
            }

            private void bounded(int length) {
                if (length < 0 || count > MAX_BYTES - length)
                    throw new IllegalArgumentException("merged text exceeds its bound");
            }
        };
        try {
            new MergeFormatter()
                    .formatMergeDiff3(output, merged, List.of("BASE", "LOCAL", "REMOTE"), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("in-memory merge could not be formatted", failure);
        }
        return new Result(Optional.of(output.toString(StandardCharsets.UTF_8)), merged.containsConflicts());
    }

    private static RawText text(String value) {
        RawText text = new RawText(value.getBytes(StandardCharsets.UTF_8));
        if (text.size() > 100000) throw new IllegalArgumentException("text reconciliation line bound exceeded");
        return text;
    }

    private static String terminated(Optional<String> text) {
        String value = text.orElse("");
        return value.isEmpty() || value.endsWith("\n") ? value : value + "\n";
    }

    record Result(Optional<String> content, boolean conflicted) {}

    private TextReconciliation() {}
}
