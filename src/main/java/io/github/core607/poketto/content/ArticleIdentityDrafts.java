package io.github.core607.poketto.content;

import java.util.UUID;

/** Prepares source supplied by an editor; performs no repository read, write, or publication. */
public interface ArticleIdentityDrafts {
    /** Preserves a valid existing ID and refuses to overwrite a malformed authored ID. */
    Draft prepare(String path, String source);

    record Draft(String source, UUID articleId) {}
}
