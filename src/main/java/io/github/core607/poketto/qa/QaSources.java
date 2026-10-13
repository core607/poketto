package io.github.core607.poketto.qa;

import java.util.List;

/** Implemented by the same current public reading boundary used by wander. Never execute author code. */
public interface QaSources {
    Page search(String query, String tag, int offset);

    Reading read(String reference, int offset);

    record Card(String reference, String url, String title, String snippet, List<String> tags) {
        public Card {
            tags = List.copyOf(tags);
        }
    }

    record Page(List<Card> items, int total, Integer nextOffset, boolean refineQuery) {
        public Page {
            items = List.copyOf(items);
        }
    }

    record Reading(Card article, String text, int offset, Integer nextOffset) {}
}
