package io.github.core607.poketto.content;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Synthetic, bounded timing probe; run explicitly, never as a CI latency assertion. */
public final class SearchParsingProbe {
    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");
    private static volatile long sink;

    private SearchParsingProbe() {}

    public static void main(String[] args) {
        var allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        List<Document> warmup = corpus(1000, 4096);
        var warmQuery = new DocumentSearch("needle-rare", "", null, null, 0, 20);
        for (int iteration = 0; iteration < 10; iteration++) {
            search(warmQuery, warmup, false);
        }
        for (int[] size : List.of(new int[] {100, 4096}, new int[] {1000, 4096}, new int[] {1000, 32768})) {
            List<Document> documents = corpus(size[0], size[1]);
            for (String scenario : List.of("body-hit", "no-hit", "tag-rejected")) {
                var query = new DocumentSearch(
                        scenario.equals("no-hit") ? "not-present-unique" : "needle-rare",
                        scenario.equals("tag-rejected") ? "missing-tag" : "",
                        null,
                        null,
                        0,
                        20);
                measure(allocations, documents, size[1], scenario, query, false);
                measure(allocations, documents, size[1], scenario, query, true);
            }
        }
    }

    private static void measure(
            ThreadMXBean allocations,
            List<Document> documents,
            int characters,
            String scenario,
            DocumentSearch query,
            boolean plain) {
        for (int iteration = 0; iteration < 4; iteration++) {
            search(query, documents, plain);
        }
        long[] wall = new long[9];
        long[] cpu = new long[9];
        long[] allocated = new long[9];
        for (int sample = 0; sample < wall.length; sample++) {
            long before =
                    allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long cpuStart = allocations.getCurrentThreadCpuTime();
            long start = System.nanoTime();
            search(query, documents, plain);
            wall[sample] = System.nanoTime() - start;
            cpu[sample] = allocations.getCurrentThreadCpuTime() - cpuStart;
            allocated[sample] =
                    allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        }
        Arrays.sort(wall);
        Arrays.sort(cpu);
        Arrays.sort(allocated);
        System.out.printf(
                Locale.ROOT,
                "docs=%d charsEach=%d scenario=%s mode=%s medianMs=%.3f maxMs=%.3f cpuMs=%.3f allocatedMiB=%.3f checksum=%d%n",
                documents.size(),
                characters,
                scenario,
                plain ? "pre-extracted-scan" : "current",
                wall[4] / 1e6,
                wall[8] / 1e6,
                cpu[4] / 1e6,
                allocated[4] / 1048576.0,
                sink);
    }

    private static void search(DocumentSearch query, List<Document> documents, boolean plain) {
        var matches = new ArrayList<Document>();
        for (Document document : documents) {
            boolean match = plain
                    ? query.tag().isEmpty()
                            && (document.title().contains(query.query())
                                    || document.plain().contains(query.query()))
                    : query.matches(document.title(), document.source(), List.of("sample"), CREATED);
            if (match) {
                matches.add(document);
            }
        }
        long result = matches.size();
        for (Document document : query.page(matches)) {
            // This lower-bound control deliberately excludes snippet construction and projection cost.
            result += plain
                    ? Math.min(240, document.plain().length())
                    : query.snippet(document.title(), document.source()).length();
        }
        sink = result;
    }

    private static List<Document> corpus(int count, int characters) {
        var documents = new ArrayList<Document>();
        String block =
                "A paragraph with **bold**, _emphasis_, [a link](https://example.test/path), and `inline code`.\n\n"
                        + "- first item\n- second item\n\n> quoted reading text\n\n| A | B |\n|---|---|\n| one | two |\n\n";
        for (int index = 0; index < count; index++) {
            String title = "Document " + index;
            String body =
                    "# " + title + "\n\n" + (index % 20 == 0 ? "needle-rare target\n\n" : "ordinary beginning\n\n")
                            + block.repeat(characters / block.length() + 1);
            body = body.substring(0, characters);
            documents.add(new Document(title, body, MarkdownText.visible(body)));
        }
        return documents;
    }

    private record Document(String title, String source, String plain) {}
}
