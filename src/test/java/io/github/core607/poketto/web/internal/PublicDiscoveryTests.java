package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.AssetService;
import io.github.core607.poketto.assets.PublicAlbumCover;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PublicDiscoveryTests {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void tagsBelongToTheBatchAndContinuationWhileWithdrawalStillWins() {
        var fixture = new Fixture();
        var first = fixture.discovery.page(null, null, 0, "猫");
        assertThat(first.tag()).isEqualTo("猫");
        assertThat(first.items()).extracting(PublicDiscovery.Card::route).containsExactly("/tagged");
        assertThat(fixture.discovery.page(first.batch(), null, 0, null)).isEqualTo(first);
        assertThat(fixture.discovery.page(null, first.batch(), 0, null).tag()).isEqualTo("猫");
        assertThatThrownBy(() -> fixture.discovery.page(first.batch(), null, 0, "other"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.discovery.page(null, first.batch(), 0, "other"))
                .isInstanceOf(IllegalArgumentException.class);
        when(fixture.publications.findPublished("space")).thenReturn(Optional.empty());
        assertThat(fixture.discovery.page(first.batch(), null, 0, null).items()).isEmpty();
    }

    @Test
    void invalidTagsAreRefusedAndExpiredBatchesDoNotSilentlyReshuffle() {
        var fixture = new Fixture();
        assertThat(fixture.discovery.page(null, null, 0, "😸".repeat(64)).tag()).isEqualTo("😸".repeat(64));
        assertThatThrownBy(() -> fixture.discovery.page(null, null, 0, "😸".repeat(64) + "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.discovery.page(null, null, 0, "x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        var first = fixture.discovery.page(null, null, 0, "");
        when(fixture.clock.instant()).thenReturn(NOW.plusSeconds(1800));
        assertThatThrownBy(() -> fixture.discovery.page(first.batch(), null, 0, null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GONE));
    }

    private static final class Fixture {
        private final WorkspacePublications publications = mock(WorkspacePublications.class);
        private final Clock clock = mock(Clock.class);
        private final PublicDiscovery discovery;

        private Fixture() {
            var workspace = new WorkspaceId(UUID.randomUUID());
            var publication = new WorkspacePublications.Publication(workspace, "space", "Space", true, true, "");
            var articles = List.of(article("tagged", List.of("猫")), article("other", List.of("java")));
            var snapshot = new PublicContentSnapshot(
                    workspace, Optional.of("a".repeat(40)), NOW, NOW.plusSeconds(3600), articles);
            var snapshots = mock(PublicContentSnapshots.class);
            when(clock.instant()).thenReturn(NOW);
            when(publications.publishedAfter(any(), anyInt())).thenReturn(List.of(publication));
            when(publications.findPublished("space")).thenReturn(Optional.of(publication));
            when(snapshots.withCurrent(any(), any())).thenAnswer(invocation -> {
                Function<PublicContentSnapshot, ?> action = invocation.getArgument(1);
                return action.apply(snapshot);
            });
            var assets = mock(AssetService.class);
            // Every current card gets a cover entry; a missing entry would mean the card is stale.
            when(assets.publicCovers(any(), any())).thenAnswer(invocation -> {
                List<PublicArticle> requested = invocation.getArgument(1);
                return requested.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                PublicArticle::route, value -> new PublicAlbumCover(false, null)));
            });
            discovery = new PublicDiscovery(publications, snapshots, assets, clock);
        }
    }

    private static PublicArticle article(String name, List<String> tags) {
        return new PublicArticle(
                "public/" + name + ".md", "/" + name, name, "Body", tags, NOW, NOW, false, "", null, true);
    }
}
