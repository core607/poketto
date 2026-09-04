package io.github.core607.poketto.content;

/**
 * Fixed resource bounds of the content format. A document or workspace beyond a bound is invalid
 * content rather than a configuration matter, so the bounds are constants, not properties.
 */
public final class ContentLimits {

    /** Largest managed document, measured over its exact blob bytes. */
    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

    /** Largest frontmatter section between the delimiters, measured in UTF-8 bytes. */
    public static final int MAX_FRONTMATTER_BYTES = 16 * 1024;

    /** Longest title after trimming, in Unicode code points. */
    public static final int MAX_TITLE_LENGTH = 200;

    public static final int MAX_TAGS = 32;

    /** Longest tag after trimming, in Unicode code points. */
    public static final int MAX_TAG_LENGTH = 64;

    /** Longest managed repository path, in characters. */
    public static final int MAX_PATH_LENGTH = 255;

    /** Most managed documents one workspace snapshot may hold. */
    public static final int MAX_DOCUMENTS_PER_WORKSPACE = 10_000;

    /** Largest sum of managed document bytes one workspace snapshot may hold. */
    public static final long MAX_WORKSPACE_BYTES = 256L * 1024 * 1024;

    private ContentLimits() {}
}
