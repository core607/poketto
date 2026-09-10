package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepositoryPublishingPolicyTests {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "note.md", "notes/page.md", "private/public/page.md", "PUBLIC/page.md",
                "Public/page.md", "publicity/page.md", "public", "public/AGENTS.md",
                "public/notes/agents.MD", "public/.hidden/page.md", "public/notes/.secret.md"
            })
    void onlyExactPublicRootContentCanPublish(String path) {
        assertThat(parse("enabled: true\nmode: public-root\n").permitsPath(path))
                .isFalse();
    }

    @Test
    void categoryNamesDoNotIntroduceAdditionalPrivacyRoots() {
        var policy = parse("enabled: true\nmode: public-root\n");
        assertThat(policy.permitsPath("public/private/notes.md")).isTrue();
        assertThat(policy.permitsPath("public/public/notes.md")).isTrue();
    }

    @Test
    void missingDisabledAndMalformedPoliciesNeverPublish() {
        assertThat(RepositoryPublishingPolicy.missing().state()).isEqualTo(RepositoryPublishingPolicy.State.MISSING);
        assertThat(RepositoryPublishingPolicy.missing().permitsPath("hello.md")).isFalse();
        var disabled = parse("enabled: false\nmode: public-root\n");
        assertThat(disabled.state()).isEqualTo(RepositoryPublishingPolicy.State.DISABLED);
        assertThat(disabled.permitsPath("hello.md")).isFalse();
        assertThat(parse("enabled: false\nmode: broken\n").state()).isEqualTo(RepositoryPublishingPolicy.State.INVALID);
    }

    @Test
    void enabledPolicyProtectsPrivateAndReservedPathsRegardlessOfReferences() {
        var policy = parse("enabled: true\nmode: public-root\n");
        assertThat(policy.state()).isEqualTo(RepositoryPublishingPolicy.State.ENABLED);
        assertThat(policy.permitsPath("public/中文/旅行.md")).isTrue();
        assertThat(policy.permitsPath("private/secret.md")).isFalse();
        assertThat(policy.permitsPath("PRIVATE/secret.md")).isFalse();
        assertThat(policy.permitsPath("private/photo.png")).isFalse();
        assertThat(policy.permitsPath(".poketto/publishing.yaml")).isFalse();
        assertThat(policy.permitsPath("nested/.git/config")).isFalse();
        assertThat(policy.permitsPath(".GIT/config")).isFalse();
        assertThat(policy.permitsPath("public/private-notes.md")).isTrue();
    }

    @Test
    void exclusionsMatchSegmentsWithoutRegexAndDoubleStarIncludesZeroDirectories() {
        var policy = parse("""
                enabled: true
                mode: public-root
                exclude:
                  - public/drafts/**
                  - '**/secret?.md'
                  - 'public/*.hidden.md'
                  - 'public/notes/a+b.md'
                """);
        assertThat(policy.permitsPath("public/drafts/page.md")).isFalse();
        assertThat(policy.permitsPath("public/drafts/a/b.png")).isFalse();
        assertThat(policy.permitsPath("public/secret1.md")).isFalse();
        assertThat(policy.permitsPath("public/notes/secret2.md")).isFalse();
        assertThat(policy.permitsPath("public/notes/secret🐈.md")).isFalse();
        assertThat(policy.permitsPath("public/one.hidden.md")).isFalse();
        assertThat(policy.permitsPath("public/notes/one.hidden.md")).isTrue();
        assertThat(policy.permitsPath("public/notes/a+b.md")).isFalse();
        assertThat(policy.permitsPath("public/notes/ab.md")).isTrue();
        assertThat(policy.permitsPath("public/notes/secret12.md")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "enabled: true",
                "enabled: true\nmode: public-by-default",
                "[]",
                "enabled: 'true'\nmode: public-root",
                "enabled: true\nenabled: false\nmode: public-root",
                "enabled: true\nmode: public-root\nunknown: value",
                "enabled: true\nmode: public-root\nexclude: null",
                "enabled: true\nmode: public-root\nexclude: '../private/**'",
                "enabled: true\nmode: public-root\nexclude: ['a/**b']",
                "enabled: true\nmode: public-root\nexclude: ['[ab]']",
                "enabled: true\nmode: public-root\nexclude: [42]",
                "enabled: true\nmode: public-root\n---\nenabled: false",
                "<<: {enabled: true, mode: public-root}",
                "<<: {enabled: false, mode: public-root}\n<<: {enabled: true, exclude: []}",
                "enabled: true\nmode: public-root\n<<: {exclude: ['**']}\n<<: {exclude: []}",
                "!!java.net.URL [https://example.invalid]"
            })
    void invalidPolicyCannotRetainAnEnabledDecision(String source) {
        var policy = parse(source);
        assertThat(policy.state()).isEqualTo(RepositoryPublishingPolicy.State.INVALID);
        assertThat(policy.permitsPath("hello.md")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/absolute.md", "../private/x.md", "a/../x.md", "a//x.md", "a\\x.md", "C:/x.md", "a/./x.md"})
    void invalidPathsCannotBePublished(String path) {
        assertThat(parse("enabled: true\nmode: public-root").permitsPath(path)).isFalse();
    }

    @Test
    void parserBoundsRejectOversizedMalformedAndExcessivePolicies() {
        assertThat(RepositoryPublishingPolicy.parse(new byte[] {(byte) 0xc3, 0x28})
                        .state())
                .isEqualTo(RepositoryPublishingPolicy.State.INVALID);
        assertThat(parse("#".repeat(RepositoryPublishingPolicy.MAX_BYTES + 1)).state())
                .isEqualTo(RepositoryPublishingPolicy.State.INVALID);
        assertThat(parse("enabled: true\nmode: public-root\nexclude:\n" + "  - draft/**\n".repeat(65))
                        .state())
                .isEqualTo(RepositoryPublishingPolicy.State.INVALID);
        assertThat(parse("enabled: true\nmode: public-root\nexclude: ['" + "x".repeat(256) + "']")
                        .state())
                .isEqualTo(RepositoryPublishingPolicy.State.INVALID);
    }

    private static RepositoryPublishingPolicy parse(String source) {
        return RepositoryPublishingPolicy.parse(source.getBytes(StandardCharsets.UTF_8));
    }
}
