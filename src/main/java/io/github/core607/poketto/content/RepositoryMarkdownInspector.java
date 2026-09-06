package io.github.core607.poketto.content;

/** Parses an authorized draft using the same metadata and body rules as repository discovery. */
public interface RepositoryMarkdownInspector {
    Draft inspect(String path, String source);

    record Draft(String body, String route, boolean folderPage) {}
}
