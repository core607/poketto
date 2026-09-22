package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.community.Community.ArticleCard;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.content.ContentRepositoryException;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.content.PublicContentSnapshot;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.content.WebsiteContentSnapshots;
import io.github.core607.poketto.workspace.PublicAuthorNames;
import io.github.core607.poketto.workspace.PublicationUnavailableException;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.github.core607.poketto.workspace.WorkspacePublications;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

final class CommunityTargets {
    private final WorkspacePublications publications;
    private final PublicContentSnapshots snapshots;

    CommunityTargets(WorkspacePublications publications, PublicContentSnapshots snapshots) {
        this.publications = publications;
        this.snapshots = new WebsiteContentSnapshots(snapshots, publications);
    }

    WorkspaceId workspace(String space) {
        return publications
                .findPublished(space)
                .orElseThrow(CommunityTargets::unavailable)
                .workspaceId();
    }

    <T> T read(WorkspaceId workspace, Function<PublicContentSnapshot, T> operation) {
        return snapshots.withCurrent(workspace, operation);
    }

    static PublicArticle article(PublicContentSnapshot snapshot, UUID id) {
        if (id == null) {
            throw unavailable();
        }
        return snapshot.articles().stream()
                .filter(article -> id.equals(article.articleId()))
                .findFirst()
                .orElseThrow(CommunityTargets::unavailable);
    }

    Optional<ArticleCard> card(WorkspaceId workspace, UUID articleId) {
        try {
            WorkspacePublications.Publication publication = publications.settings(workspace);
            return read(
                    workspace,
                    snapshot -> snapshot.articles().stream()
                            .filter(article -> articleId.equals(article.articleId()))
                            .findFirst()
                            .map(article -> card(publication, article)));
        } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
            return Optional.empty();
        }
    }

    WorkspacePublications.Publication publication(WorkspaceId workspace) {
        return publications.settings(workspace);
    }

    Optional<ArticleCard> currentCard(ArticleCard expected) {
        try {
            Optional<WorkspacePublications.Publication> publication = publications.findPublished(expected.space());
            if (publication.isEmpty()) {
                return Optional.empty();
            }
            return read(
                    publication.get().workspaceId(),
                    snapshot -> snapshot.articles().stream()
                            .filter(article -> article.route().equals(expected.route())
                                    && Objects.equals(article.articleId(), expected.articleId()))
                            .findFirst()
                            .map(article -> card(publication.get(), article)));
        } catch (ContentRepositoryException | PublicationUnavailableException unavailable) {
            return Optional.empty();
        }
    }

    static ArticleCard card(WorkspacePublications.Publication publication, PublicArticle article) {
        return new ArticleCard(
                publication.slug(),
                publication.displayName(),
                article.articleId(),
                article.route(),
                article.title(),
                PublicAuthorNames.select(article.publicAuthor(), publication.authorName()),
                article.createdAt());
    }

    static CommunityException unavailable() {
        return new CommunityException(CommunityException.Code.UNAVAILABLE);
    }
}
