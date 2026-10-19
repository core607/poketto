package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.content.DocumentSearch;
import io.github.core607.poketto.content.MarkdownText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Keyword matching and BM25 ordering for agent search over the current public catalogue. Whitespace
 * separates keywords, and every keyword must occur in the title or the visible text. Letter case is
 * ignored through per-character folding, which keeps every offset aligned with the original text.
 * Statistics come from the documents searched by one request; nothing is indexed or kept between
 * requests. notes/implemented/2026-10-19-ranked-plaza-keyword-search.md owns the rationale.
 */
final class KeywordRanking {
    static final int MAX_KEYWORDS = 8;

    // Customary BM25 constants, untuned. A title occurrence weighs as much as three body occurrences.
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int TITLE_WEIGHT = 3;

    private final List<String> keywords;
    private final boolean folding;

    private KeywordRanking(List<String> keywords, boolean folding) {
        this.keywords = keywords;
        this.folding = folding;
    }

    /**
     * Splits a query on Unicode spaces, including the ideographic space. Repeated keywords count
     * once. More than {@link #MAX_KEYWORDS} distinct keywords is refused rather than truncated,
     * because a silently dropped keyword would widen the result without the caller knowing.
     */
    static KeywordRanking parse(String query) {
        var distinct = new LinkedHashSet<String>();
        int start = -1;
        for (int index = 0; index <= query.length(); index++) {
            boolean separator = index == query.length() || separator(query.charAt(index));
            if (!separator && start < 0) {
                start = index;
            } else if (separator && start >= 0) {
                distinct.add(fold(query.substring(start, index)));
                start = -1;
            }
        }
        if (distinct.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException("Use at most " + MAX_KEYWORDS + " keywords");
        }
        boolean folding = distinct.stream().anyMatch(KeywordRanking::cased);
        return new KeywordRanking(List.copyOf(distinct), folding);
    }

    boolean isEmpty() {
        return keywords.isEmpty();
    }

    <T> Scan<T> scan() {
        return new Scan<>();
    }

    /**
     * The excerpt cut around the earliest occurrence of any keyword in the summary text, under the
     * same length and edge rules as {@link DocumentSearch#snippet}. A document whose keywords occur
     * only in its title starts at the beginning.
     */
    String snippet(String title, String markdown) {
        String body = MarkdownText.summary(title, markdown);
        String text = folding ? fold(body) : body;
        int match = -1;
        int length = 0;
        for (String keyword : keywords) {
            int found = text.indexOf(keyword);
            if (found >= 0 && (match < 0 || found < match)) {
                match = found;
                length = keyword.length();
            }
        }
        return DocumentSearch.excerpt(body, Math.max(0, match), length);
    }

    private static boolean separator(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    private static boolean cased(String keyword) {
        for (int index = 0; index < keyword.length(); index++) {
            char value = keyword.charAt(index);
            if (Character.toUpperCase(value) != value || Character.toLowerCase(value) != value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folds one UTF-16 unit at a time through upper and then lower case, so that forms such as the
     * long s meet their ordinary letter. Surrogates stay unchanged and the length never changes;
     * case pairs outside the Basic Multilingual Plane are therefore not folded.
     */
    static String fold(String text) {
        char[] folded = null;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            char target = Character.isSurrogate(value) ? value : Character.toLowerCase(Character.toUpperCase(value));
            if (target != value) {
                if (folded == null) {
                    folded = text.toCharArray();
                }
                folded[index] = target;
            }
        }
        return folded == null ? text : new String(folded);
    }

    private static int occurrences(String text, String keyword) {
        int count = 0;
        int from = text.indexOf(keyword);
        while (from >= 0) {
            count++;
            from = text.indexOf(keyword, from + keyword.length());
        }
        return count;
    }

    record Scored<T>(T item, double score) {}

    /**
     * Collects one request's documents. Every added document contributes to the document count,
     * the average length and each keyword's document frequency, whether or not it contains every
     * keyword; only documents that contain all keywords are kept for scoring.
     */
    final class Scan<T> {
        private final int[] documentFrequency = new int[keywords.size()];
        private final List<T> items = new ArrayList<>();
        private final List<int[]> frequencies = new ArrayList<>();
        private final List<Integer> lengths = new ArrayList<>();
        private int documents;
        private long totalLength;

        private Scan() {}

        void add(T item, String title, String visible) {
            String foldedTitle = folding ? fold(title) : title;
            String foldedText = folding ? fold(visible) : visible;
            int[] weighted = new int[keywords.size()];
            boolean all = true;
            for (int index = 0; index < weighted.length; index++) {
                String keyword = keywords.get(index);
                weighted[index] = TITLE_WEIGHT * occurrences(foldedTitle, keyword) + occurrences(foldedText, keyword);
                if (weighted[index] > 0) {
                    documentFrequency[index]++;
                } else {
                    all = false;
                }
            }
            int length = title.length() + visible.length();
            documents++;
            totalLength += length;
            if (all) {
                items.add(item);
                frequencies.add(weighted);
                lengths.add(length);
            }
        }

        /** Documents containing every keyword, in insertion order, each with its BM25 score. */
        List<Scored<T>> matches() {
            double average = documents == 0 ? 0 : (double) totalLength / documents;
            double[] idf = new double[documentFrequency.length];
            for (int index = 0; index < idf.length; index++) {
                idf[index] =
                        Math.log(1 + (documents - documentFrequency[index] + 0.5) / (documentFrequency[index] + 0.5));
            }
            var result = new ArrayList<Scored<T>>(items.size());
            for (int item = 0; item < items.size(); item++) {
                double norm = average == 0 ? 1 : 1 - B + B * lengths.get(item) / average;
                double score = 0;
                int[] weighted = frequencies.get(item);
                for (int index = 0; index < weighted.length; index++) {
                    score += idf[index] * weighted[index] * (K1 + 1) / (weighted[index] + K1 * norm);
                }
                result.add(new Scored<>(items.get(item), score));
            }
            return result;
        }
    }
}
