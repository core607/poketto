package io.github.core607.poketto.plaza.internal;

import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.plaza.PlazaException;
import io.github.core607.poketto.qa.QaException;
import io.github.core607.poketto.qa.QaSources;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import java.util.function.Supplier;

final class PlazaQaSources implements QaSources {
    private final PublicPlazaReads reads;

    PlazaQaSources(PublicPlazaReads reads) {
        this.reads = reads;
    }

    @Override
    public Page search(String query, String tag, int offset) {
        return read(() -> {
            PublicPlazaReads.Page page = reads.search(query, tag, offset);
            return new Page(
                    page.items().stream().map(PlazaQaSources::card).toList(),
                    page.total(),
                    page.nextOffset(),
                    page.refineQuery());
        });
    }

    @Override
    public Reading read(String reference, int offset) {
        return read(() -> {
            PublicPlazaReads.Reading value = reads.read(reference, offset);
            return new Reading(card(value.article()), value.text(), value.offset(), value.nextOffset());
        });
    }

    private static Card card(PublicPlazaReads.Card value) {
        return new Card(value.reference(), value.url(), value.title(), value.snippet(), value.tags());
    }

    private static <T> T read(Supplier<T> action) {
        try {
            return action.get();
        } catch (PlazaException failure) {
            throw new QaException(failure.code(), "The current public read could not be completed", failure);
        } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
            throw new QaException("SOURCE_UNAVAILABLE", "The current public source is unavailable", unavailable);
        }
    }
}
